package com.unciv.app.desktop

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import java.io.File
import kotlin.math.max

/**
 * Writes the first learning dataset for chat-run Unciv simulations.
 *
 * This is intentionally simple and stable: checkpoint-level civ snapshots are
 * labeled after the game finishes. The label is 1.0 for the winner, 0.0 for the
 * loser, and 0.5 for unresolved draws. That gives us a value-learning target
 * before we start overriding individual AI decisions.
 */
internal object HeadlessTrainingLogger {
    fun captureValueSamples(gameInfo: GameInfo, seed: Int, phase: String, samples: MutableList<HeadlessValueSample>) {
        val civs = gameInfo.civilizations
            .filter { it.isMajorCiv() && !it.isSpectator() }
            .sortedBy { it.civID }
        if (civs.isEmpty()) return

        val snapshots = civs.associateWith { snapshotFor(it) }
        for (civ in civs) {
            val own = snapshots[civ] ?: continue
            val rivals = snapshots.entries
                .filter { it.key != civ }
                .map { it.value }
            val rivalMax = aggregateMax(rivals)
            samples += HeadlessValueSample(
                seed = seed,
                turn = gameInfo.turns,
                phase = phase,
                civ = civ.civID,
                features = valueFeatures(own, rivalMax)
            )
        }
    }

    fun writeValueSamples(
        file: File,
        samples: List<HeadlessValueSample>,
        winner: String?,
        victoryType: String?,
        finalTurn: Int
    ) {
        file.parentFile?.mkdirs()
        file.writeText("")
        for (sample in samples) {
            val label = when {
                winner == null -> 0.5
                sample.civ == winner -> 1.0
                else -> 0.0
            }
            file.appendText(sample.toJson(label, winner, victoryType, finalTurn) + "\n")
        }
    }

    private fun snapshotFor(civ: Civilization): CivValueSnapshot {
        runCatching { civ.updateStatsForNextTurn() }
        val stats = runCatching { civ.stats.statsForNextTurn }.getOrNull()
        return CivValueSnapshot(
            cities = civ.cities.size,
            population = civ.cities.sumOf { it.population.population },
            units = civ.units.getCivUnitsSize(),
            techs = civ.tech.getNumberOfTechsResearched(),
            goldReserve = civ.gold,
            production = stats?.production ?: 0f,
            food = stats?.food ?: 0f,
            goldPerTurn = stats?.gold ?: 0f,
            science = stats?.science ?: 0f,
            culture = stats?.culture ?: 0f,
            happiness = civ.getHappiness().toFloat()
        )
    }

    private fun aggregateMax(snapshots: List<CivValueSnapshot>): CivValueSnapshot {
        if (snapshots.isEmpty()) return CivValueSnapshot()
        return CivValueSnapshot(
            cities = snapshots.maxOf { it.cities },
            population = snapshots.maxOf { it.population },
            units = snapshots.maxOf { it.units },
            techs = snapshots.maxOf { it.techs },
            goldReserve = snapshots.maxOf { it.goldReserve },
            production = snapshots.maxOf { it.production },
            food = snapshots.maxOf { it.food },
            goldPerTurn = snapshots.maxOf { it.goldPerTurn },
            science = snapshots.maxOf { it.science },
            culture = snapshots.maxOf { it.culture },
            happiness = snapshots.maxOf { it.happiness }
        )
    }

    private fun valueFeatures(own: CivValueSnapshot, rivalMax: CivValueSnapshot): LinkedHashMap<String, Double> {
        fun ratio(value: Number, denom: Number): Double = value.toDouble() / max(1.0, denom.toDouble())
        fun delta(value: Number, other: Number): Double = value.toDouble() - other.toDouble()

        return linkedMapOf(
            "cities" to own.cities.toDouble(),
            "population" to own.population.toDouble(),
            "units" to own.units.toDouble(),
            "techs" to own.techs.toDouble(),
            "gold_reserve" to own.goldReserve.toDouble(),
            "production" to own.production.toDouble(),
            "food" to own.food.toDouble(),
            "gold_per_turn" to own.goldPerTurn.toDouble(),
            "science" to own.science.toDouble(),
            "culture" to own.culture.toDouble(),
            "happiness" to own.happiness.toDouble(),
            "cities_ratio" to ratio(own.cities, rivalMax.cities),
            "population_ratio" to ratio(own.population, rivalMax.population),
            "units_ratio" to ratio(own.units, rivalMax.units),
            "techs_ratio" to ratio(own.techs, rivalMax.techs),
            "science_ratio" to ratio(own.science, rivalMax.science),
            "production_ratio" to ratio(own.production, rivalMax.production),
            "cities_delta" to delta(own.cities, rivalMax.cities),
            "population_delta" to delta(own.population, rivalMax.population),
            "units_delta" to delta(own.units, rivalMax.units),
            "techs_delta" to delta(own.techs, rivalMax.techs),
            "science_delta" to delta(own.science, rivalMax.science),
            "production_delta" to delta(own.production, rivalMax.production)
        )
    }
}

internal data class HeadlessValueSample(
    val seed: Int,
    val turn: Int,
    val phase: String,
    val civ: String,
    val features: LinkedHashMap<String, Double>
) {
    fun toJson(label: Double, winner: String?, victoryType: String?, finalTurn: Int): String = buildString {
        append("{")
        append("\"seed\":$seed,")
        append("\"turn\":$turn,")
        append("\"phase\":\"${jsonEscape(phase)}\",")
        append("\"civ\":\"${jsonEscape(civ)}\",")
        append("\"winner\":${jsonString(winner)},")
        append("\"victoryType\":${jsonString(victoryType)},")
        append("\"finalTurn\":$finalTurn,")
        append("\"label\":$label,")
        append("\"features\":{")
        append(features.entries.joinToString(",") { "\"${jsonEscape(it.key)}\":${it.value}" })
        append("}")
        append("}")
    }

    private fun jsonString(value: String?): String = if (value == null) "null" else "\"${jsonEscape(value)}\""
    private fun jsonEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
}

private data class CivValueSnapshot(
    val cities: Int = 0,
    val population: Int = 0,
    val units: Int = 0,
    val techs: Int = 0,
    val goldReserve: Int = 0,
    val production: Float = 0f,
    val food: Float = 0f,
    val goldPerTurn: Float = 0f,
    val science: Float = 0f,
    val culture: Float = 0f,
    val happiness: Float = 0f
)
