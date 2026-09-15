package com.unciv.logic.automation.world

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import kotlin.math.abs
import kotlin.math.max

/**
 * Experimental world-level observer for Unking.
 *
 * The Crown does not replace civilization automation. It observes the complete
 * world, estimates whether power and survival are drifting out of a viable
 * range, and produces high-level pressure that can later be translated into
 * taxes, decrees, diplomatic pressure, subsidies, or other in-world levers.
 *
 * Version 1 is deliberately read-only. That lets us measure whether the model
 * identifies sensible imbalances before it is allowed to alter a game.
 */
class CrownWorldModel {
    private var previousPowerShares: Map<String, Double> = emptyMap()

    fun observe(gameInfo: GameInfo): CrownWorldAssessment {
        val civs = gameInfo.civilizations
            .filter { it.isMajorCiv() && !it.isSpectator() && !it.isDefeated() }
            .sortedBy { it.civID }

        if (civs.isEmpty()) {
            previousPowerShares = emptyMap()
            return CrownWorldAssessment(
                turn = gameInfo.turns,
                globalImbalance = 0.0,
                activeWars = 0,
                dominantCiv = null,
                distressedCiv = null,
                realms = emptyList()
            )
        }

        val raw = civs.map { snapshot(it) }
        val totals = WorldTotals.from(raw)
        val equalShare = 1.0 / raw.size

        val preliminary = raw.map { snapshot ->
            val populationShare = share(snapshot.population.toDouble(), totals.population)
            val unitShare = share(snapshot.units.toDouble(), totals.units)
            val techShare = share(snapshot.techs.toDouble(), totals.techs)
            val productionShare = share(snapshot.production, totals.production)
            val scienceShare = share(snapshot.science, totals.science)

            // This is a deliberately transparent proxy, not a learned score.
            // We can later replace or rerank this with measured outcome data.
            val powerShare =
                populationShare * 0.22 +
                unitShare * 0.25 +
                techShare * 0.17 +
                productionShare * 0.18 +
                scienceShare * 0.18

            val distress = distress(snapshot)
            val previous = previousPowerShares[snapshot.civID] ?: powerShare
            RealmAssessment(
                civID = snapshot.civID,
                cities = snapshot.cities,
                population = snapshot.population,
                units = snapshot.units,
                techs = snapshot.techs,
                goldReserve = snapshot.goldReserve,
                production = snapshot.production,
                food = snapshot.food,
                goldPerTurn = snapshot.goldPerTurn,
                science = snapshot.science,
                culture = snapshot.culture,
                happiness = snapshot.happiness,
                populationShare = populationShare,
                unitShare = unitShare,
                powerShare = powerShare,
                powerTrend = powerShare - previous,
                distress = distress,
                crownPressure = 0.0,
                stance = CrownStance.Observe
            )
        }

        val wars = countWars(civs)
        val meanDistress = preliminary.map { it.distress }.average().coerceIn(0.0, 1.0)
        val powerInequality = preliminary.sumOf { abs(it.powerShare - equalShare) } / 2.0
        val possibleWars = max(1, civs.size * (civs.size - 1) / 2)
        val warDensity = wars.toDouble() / possibleWars.toDouble()

        // A world can contain wars and inequality without being unhealthy.
        // This score is mainly useful as a trend signal across turns.
        val globalImbalance = (
            powerInequality * 0.55 +
            meanDistress * 0.30 +
            warDensity.coerceIn(0.0, 1.0) * 0.15
        ).coerceIn(0.0, 1.0)

        val realms = preliminary.map { realm ->
            val dominance = ((realm.powerShare - equalShare) / max(equalShare, 0.01)).coerceAtLeast(0.0)
            val weakness = ((equalShare - realm.powerShare) / max(equalShare, 0.01)).coerceAtLeast(0.0)

            // Positive pressure means "support this realm". Negative means
            // "restrain this realm". Distress can override simple weakness.
            val support = realm.distress * 0.75 + weakness * 0.25
            val restrain = dominance * 0.65 + realm.powerTrend.coerceAtLeast(0.0) * 4.0 * 0.35
            val pressure = (support - restrain).coerceIn(-1.0, 1.0)
            val stance = when {
                pressure >= 0.20 -> CrownStance.Support
                pressure <= -0.20 -> CrownStance.Restrain
                else -> CrownStance.Observe
            }
            realm.copy(crownPressure = pressure, stance = stance)
        }

        previousPowerShares = realms.associate { it.civID to it.powerShare }

        return CrownWorldAssessment(
            turn = gameInfo.turns,
            globalImbalance = globalImbalance,
            activeWars = wars,
            dominantCiv = realms.maxByOrNull { it.powerShare }?.civID,
            distressedCiv = realms.maxByOrNull { it.distress }?.takeIf { it.distress > 0.0 }?.civID,
            realms = realms
        )
    }

    private fun snapshot(civ: Civilization): RawRealmSnapshot {
        runCatching { civ.updateStatsForNextTurn() }
        val stats = runCatching { civ.stats.statsForNextTurn }.getOrNull()
        return RawRealmSnapshot(
            civID = civ.civID,
            cities = civ.cities.size,
            population = civ.cities.sumOf { it.population.population },
            units = civ.units.getCivUnitsSize(),
            techs = civ.tech.getNumberOfTechsResearched(),
            goldReserve = civ.gold,
            production = stats?.production?.toDouble() ?: 0.0,
            food = stats?.food?.toDouble() ?: 0.0,
            goldPerTurn = stats?.gold?.toDouble() ?: 0.0,
            science = stats?.science?.toDouble() ?: 0.0,
            culture = stats?.culture?.toDouble() ?: 0.0,
            happiness = civ.getHappiness().toDouble()
        )
    }

    private fun distress(snapshot: RawRealmSnapshot): Double {
        val foodStress = when {
            snapshot.food >= 0.0 -> 0.0
            else -> (-snapshot.food / max(5.0, snapshot.population.toDouble())).coerceIn(0.0, 1.0)
        }
        val happinessStress = when {
            snapshot.happiness >= 0.0 -> 0.0
            else -> (-snapshot.happiness / 20.0).coerceIn(0.0, 1.0)
        }
        val treasuryStress = when {
            snapshot.goldPerTurn >= 0.0 || snapshot.goldReserve > 0 -> 0.0
            else -> (-snapshot.goldPerTurn / 20.0).coerceIn(0.0, 1.0)
        }
        return (foodStress * 0.45 + happinessStress * 0.40 + treasuryStress * 0.15)
            .coerceIn(0.0, 1.0)
    }

    private fun countWars(civs: List<Civilization>): Int {
        val activeCivIds = civs.map { it.civID }.toHashSet()
        val pairs = HashSet<String>()
        for (civ in civs) {
            for (manager in civ.diplomacy.values) {
                if (manager.otherCivName !in activeCivIds) continue
                if (manager.diplomaticStatus != DiplomaticStatus.War) continue
                val first = minOf(civ.civID, manager.otherCivName)
                val second = maxOf(civ.civID, manager.otherCivName)
                if (first != second) pairs += "$first\u0000$second"
            }
        }
        return pairs.size
    }

    private fun share(value: Double, total: Double): Double =
        if (total <= 0.0) 0.0 else (value / total).coerceIn(0.0, 1.0)
}

enum class CrownStance {
    Support,
    Observe,
    Restrain
}

data class CrownWorldAssessment(
    val turn: Int,
    val globalImbalance: Double,
    val activeWars: Int,
    val dominantCiv: String?,
    val distressedCiv: String?,
    val realms: List<RealmAssessment>
) {
    fun toJson(): String = buildString {
        append('{')
        append("\"turn\":$turn,")
        append("\"globalImbalance\":$globalImbalance,")
        append("\"activeWars\":$activeWars,")
        append("\"dominantCiv\":${jsonString(dominantCiv)},")
        append("\"distressedCiv\":${jsonString(distressedCiv)},")
        append("\"realms\":[")
        append(realms.joinToString(",") { it.toJson() })
        append("]}")
    }
}

data class RealmAssessment(
    val civID: String,
    val cities: Int,
    val population: Int,
    val units: Int,
    val techs: Int,
    val goldReserve: Int,
    val production: Double,
    val food: Double,
    val goldPerTurn: Double,
    val science: Double,
    val culture: Double,
    val happiness: Double,
    val populationShare: Double,
    val unitShare: Double,
    val powerShare: Double,
    val powerTrend: Double,
    val distress: Double,
    val crownPressure: Double,
    val stance: CrownStance
) {
    fun toJson(): String = buildString {
        append('{')
        append("\"civ\":${jsonString(civID)},")
        append("\"cities\":$cities,")
        append("\"population\":$population,")
        append("\"units\":$units,")
        append("\"techs\":$techs,")
        append("\"goldReserve\":$goldReserve,")
        append("\"production\":$production,")
        append("\"food\":$food,")
        append("\"goldPerTurn\":$goldPerTurn,")
        append("\"science\":$science,")
        append("\"culture\":$culture,")
        append("\"happiness\":$happiness,")
        append("\"populationShare\":$populationShare,")
        append("\"unitShare\":$unitShare,")
        append("\"powerShare\":$powerShare,")
        append("\"powerTrend\":$powerTrend,")
        append("\"distress\":$distress,")
        append("\"crownPressure\":$crownPressure,")
        append("\"stance\":${jsonString(stance.name)}")
        append('}')
    }
}

private data class RawRealmSnapshot(
    val civID: String,
    val cities: Int,
    val population: Int,
    val units: Int,
    val techs: Int,
    val goldReserve: Int,
    val production: Double,
    val food: Double,
    val goldPerTurn: Double,
    val science: Double,
    val culture: Double,
    val happiness: Double
)

private data class WorldTotals(
    val population: Double,
    val units: Double,
    val techs: Double,
    val production: Double,
    val science: Double
) {
    companion object {
        fun from(snapshots: List<RawRealmSnapshot>): WorldTotals = WorldTotals(
            population = snapshots.sumOf { it.population }.toDouble(),
            units = snapshots.sumOf { it.units }.toDouble(),
            techs = snapshots.sumOf { it.techs }.toDouble(),
            production = snapshots.sumOf { it.production },
            science = snapshots.sumOf { it.science }
        )
    }
}

private fun jsonString(value: String?): String =
    if (value == null) "null" else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
