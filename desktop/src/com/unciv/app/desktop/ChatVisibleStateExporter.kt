package com.unciv.app.desktop

import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/**
 * Read-only, visible-information export for the headless chat benchmark.
 *
 * This intentionally lives beside ChatPlayerHarness instead of replacing it. The exporter can be
 * called after any command batch to give the deciding model enough visible context without exposing
 * unexplored terrain, hidden enemy queues, or AI internals.
 */
internal object ChatVisibleStateExporter {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = VisibleStateConfig.fromArgs(args)
        initializeUnciv()
        val saveFile = File(config.saveFile)
        if (!saveFile.isFile) {
            System.err.println("Save file not found: ${saveFile.absolutePath}")
            exitProcess(2)
        }
        ensureBenchmarkNations()
        val gameInfo = UncivFiles.gameInfoFromString(saveFile.readText())
        UncivGame.Current.gameInfo = gameInfo

        val outputDir = File(config.outputDir).apply { mkdirs() }
        val state = visibleStateJson(gameInfo, config)
        val blockers = blockingChoices(gameInfo.currentPlayerCiv)
        File(outputDir, "visible-state-v2.json").writeText(state)
        File(outputDir, "blocking-choices.json").writeText(blockingChoicesJson(gameInfo, blockers))
        File(outputDir, "visible-state-v2.md").writeText(visibleStateMarkdown(gameInfo, blockers, config))
        println("Visible state export complete: turn=${gameInfo.turns} player=${gameInfo.currentPlayer} blockers=${blockers.size} output=${outputDir.path}")
    }

    private fun initializeUnciv() {
        UncivGame.Current = UncivGame(true)
        UncivGame.Current.settings = GameSettings().apply {
            showTutorials = false
            turnsBetweenAutosaves = 10000
        }
        RulesetCache.loadRulesets(true)
        TileSetCache.loadTileSetConfigs(true)
        SkinCache.loadSkinConfigs(true)
    }

    private fun ensureBenchmarkNations() =
        RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!.also { ruleset ->
            if (!ruleset.nations.containsKey(simulationCiv1)) ruleset.nations[simulationCiv1] = Nation().apply { name = simulationCiv1 }
            if (!ruleset.nations.containsKey(simulationCiv2)) ruleset.nations[simulationCiv2] = Nation().apply { name = simulationCiv2 }
        }

    private fun visibleStateJson(gameInfo: GameInfo, config: VisibleStateConfig): String {
        val civ = gameInfo.currentPlayerCiv
        val cities = civ.cities.sortedBy { it.name }.map { cityJson(it) }
        val units = civ.units.getCivUnits().sortedWith(compareBy<MapUnit> { it.id }.thenBy { it.name }).map { unitJson(it) }
        val visibleTiles = civ.viewableTiles
            .sortedWith(compareBy<Tile> { it.position.x }.thenBy { it.position.y })
            .map { tileJson(it, civ) }
        val knownCivs = civ.getKnownCivs()
            .filter { it != civ && !it.isSpectator() }
            .sortedBy { it.civName }
            .map { knownCivJson(civ, it) }
        val blockers = blockingChoices(civ)

        return "{" + listOf(
            json("schemaVersion", 2),
            json("matchId", config.matchId),
            json("visibleInformationOnly", true),
            json("turn", gameInfo.turns),
            json("currentPlayer", gameInfo.currentPlayer),
            json("civ", civ.civName),
            json("gold", civ.gold),
            json("happiness", civ.getHappiness()),
            json("sciencePerTurn", civ.stats.statsForNextTurn.science.toDouble()),
            json("culturePerTurn", civ.stats.statsForNextTurn.culture.toDouble()),
            json("faithPerTurn", civ.stats.statsForNextTurn.faith.toDouble()),
            json("goldPerTurn", civ.stats.statsForNextTurn.gold.toDouble()),
            json("currentTechnology", civ.tech.currentTechnologyName()),
            json("researchedTechnologyCount", civ.tech.getNumberOfTechsResearched()),
            json("goldenAge", civ.goldenAges.isGoldenAge()),
            json("blockingChoiceCount", blockers.size),
            "\"blockingChoices\":${arrayJson(blockers.map { it.toJson() })}",
            "\"cities\":${arrayJson(cities)}",
            "\"units\":${arrayJson(units)}",
            "\"knownCivilizations\":${arrayJson(knownCivs)}",
            "\"visibleTiles\":${arrayJson(visibleTiles)}"
        ).joinToString(",") + "}\n"
    }

    private fun cityJson(city: City): String {
        val stats = city.cityStats.currentCityStats
        val current = city.cityConstructions.currentConstructionName()
        val perpetual = current in PerpetualConstruction.perpetualConstructionsMap
        val progress = if (current.isEmpty() || perpetual) 0 else city.cityConstructions.getWorkDone(current)
        val remaining = if (current.isEmpty() || perpetual) 0 else city.cityConstructions.getRemainingWork(current)
        val turns = if (current.isEmpty() || perpetual) 0 else city.cityConstructions.turnsToConstruction(current)
        return "{" + listOf(
            json("id", city.id),
            json("name", city.name),
            json("x", city.location.x),
            json("y", city.location.y),
            json("population", city.population.population),
            json("health", city.health),
            json("isCapital", city.isCapital()),
            json("isPuppet", city.isPuppet),
            json("isBeingRazed", city.isBeingRazed),
            json("inResistance", city.isInResistance()),
            json("connectedToCapital", city.isConnectedToCapital()),
            json("cityFocus", city.getCityFocus().name),
            json("avoidGrowth", city.avoidGrowth),
            json("foodPerTurn", stats.food.toDouble()),
            json("productionPerTurn", stats.production.toDouble()),
            json("goldPerTurn", stats.gold.toDouble()),
            json("sciencePerTurn", stats.science.toDouble()),
            json("culturePerTurn", stats.culture.toDouble()),
            json("faithPerTurn", stats.faith.toDouble()),
            json("currentConstruction", current),
            json("constructionProgress", progress),
            json("constructionRemaining", remaining),
            json("constructionTurns", turns),
            "\"constructionQueue\":${stringArrayJson(city.cityConstructions.constructionQueue)}",
            json("workedTileCount", city.workedTiles.size),
            json("lockedTileCount", city.lockedTiles.size),
            json("specialistsManual", city.manualSpecialists),
            json("garrisonUnitId", city.getGarrison()?.id)
        ).joinToString(",") + "}"
    }

    private fun unitJson(unit: MapUnit): String {
        val promotions = unit.promotions.getPromotions(sorted = true).map { it.name }.toList()
        val availablePromotions = unit.promotions.getAvailablePromotions().map { it.name }.sorted().toList()
        return "{" + listOf(
            json("id", unit.id),
            json("name", unit.name),
            json("unitType", unit.baseUnit.unitType),
            json("x", unit.currentTile.position.x),
            json("y", unit.currentTile.position.y),
            json("health", unit.health),
            json("movement", unit.currentMovement.toDouble()),
            json("maxMovement", unit.getMaxMovement()),
            json("strength", unit.baseUnit.strength),
            json("rangedStrength", unit.baseUnit.rangedStrength),
            json("range", unit.getRange()),
            json("isCivilian", unit.isCivilian()),
            json("isEmbarked", unit.isEmbarked()),
            json("fortified", unit.isFortified()),
            json("sleeping", unit.isSleeping()),
            json("automated", unit.automated),
            json("due", unit.due),
            json("action", unit.action),
            json("xp", unit.promotions.XP),
            json("xpForNextPromotion", unit.promotions.xpForNextPromotion()),
            json("canPromote", unit.promotions.canBePromoted()),
            "\"promotions\":${stringArrayJson(promotions)}",
            "\"availablePromotions\":${stringArrayJson(availablePromotions)}"
        ).joinToString(",") + "}"
    }

    private fun tileJson(tile: Tile, civ: Civilization): String {
        val stats = tile.stats.getTileStats(tile.owningCity, civ)
        val visibleResource = tile.tileResource?.takeIf { civ.canSeeResource(it) }?.name
        val units = tile.getUnits().map { visibleUnitJson(it) }.toList()
        return "{" + listOf(
            json("x", tile.position.x),
            json("y", tile.position.y),
            json("terrain", tile.baseTerrain),
            "\"features\":${stringArrayJson(tile.terrainFeatures)}",
            json("naturalWonder", tile.naturalWonder),
            json("resource", visibleResource),
            json("resourceAmount", if (visibleResource == null) 0 else tile.resourceAmount),
            json("improvement", tile.improvement),
            json("improvementPillaged", tile.improvementIsPillaged),
            json("improvementInProgress", tile.improvementInProgress),
            json("improvementTurns", tile.turnsToImprovement),
            json("road", tile.roadStatus.name),
            json("roadPillaged", tile.roadIsPillaged),
            json("riverAdjacent", tile.isAdjacentToRiver()),
            json("owner", tile.getOwner()?.civName),
            json("owningCity", tile.owningCity?.name),
            json("cityCenter", tile.isCityCenter()),
            json("food", stats.food.toDouble()),
            json("production", stats.production.toDouble()),
            json("gold", stats.gold.toDouble()),
            json("science", stats.science.toDouble()),
            json("culture", stats.culture.toDouble()),
            json("faith", stats.faith.toDouble()),
            "\"units\":${arrayJson(units)}"
        ).joinToString(",") + "}"
    }

    private fun visibleUnitJson(unit: MapUnit): String = "{" + listOf(
        json("id", unit.id),
        json("civ", unit.civ.civName),
        json("name", unit.name),
        json("health", unit.health),
        json("strength", unit.baseUnit.strength),
        json("rangedStrength", unit.baseUnit.rangedStrength),
        json("range", unit.getRange()),
        json("civilian", unit.isCivilian())
    ).joinToString(",") + "}"

    private fun knownCivJson(observer: Civilization, other: Civilization): String {
        val visibleCities = other.cities.filter { it.getCenterTile().isVisible(observer) }
        val visibleUnits = observer.viewableTiles.asSequence().flatMap { it.getUnits() }.count { it.civ == other }
        return "{" + listOf(
            json("civ", other.civName),
            json("atWar", observer.isAtWarWith(other)),
            json("defeated", other.isDefeated()),
            json("visibleCityCount", visibleCities.size),
            json("visibleUnitCount", visibleUnits)
        ).joinToString(",") + "}"
    }

    private fun blockingChoices(civ: Civilization): List<BlockingChoice> {
        val blockers = ArrayList<BlockingChoice>()
        if (civ.cities.isEmpty()) blockers += BlockingChoice("foundCity", "No city has been founded", true)
        if (civ.tech.currentTechnologyName().isNullOrEmpty() && civ.gameInfo.ruleset.technologies.values.any { civ.tech.canBeResearched(it.name) })
            blockers += BlockingChoice("research", "No research target selected", true)
        for (city in civ.cities) {
            if (city.cityConstructions.currentConstructionName().isEmpty())
                blockers += BlockingChoice("construction", "${city.name} has no construction selected", true)
        }
        for (unit in civ.units.getCivUnits()) {
            if (unit.promotions.canBePromoted())
                blockers += BlockingChoice("promotion", "${unit.name} #${unit.id} can be promoted", true)
        }
        return blockers
    }

    private fun blockingChoicesJson(gameInfo: GameInfo, blockers: List<BlockingChoice>): String =
        "{" + listOf(
            json("turn", gameInfo.turns),
            json("currentPlayer", gameInfo.currentPlayer),
            json("canSafelyEndTurn", blockers.none { it.hardBlock }),
            json("count", blockers.size),
            "\"choices\":${arrayJson(blockers.map { it.toJson() })}"
        ).joinToString(",") + "}\n"

    private fun visibleStateMarkdown(gameInfo: GameInfo, blockers: List<BlockingChoice>, config: VisibleStateConfig): String {
        val civ = gameInfo.currentPlayerCiv
        return buildString {
            appendLine("# Visible State V2")
            appendLine()
            appendLine("- Match: `${config.matchId}`")
            appendLine("- Turn: ${gameInfo.turns}")
            appendLine("- Player: ${gameInfo.currentPlayer}")
            appendLine("- Cities: ${civ.cities.size}")
            appendLine("- Units: ${civ.units.getCivUnitsSize()}")
            appendLine("- Gold: ${civ.gold}")
            appendLine("- Happiness: ${civ.getHappiness()}")
            appendLine("- Visible tiles: ${civ.viewableTiles.size}")
            appendLine("- Safe to end turn: ${blockers.none { it.hardBlock }}")
            if (blockers.isNotEmpty()) {
                appendLine()
                appendLine("## Blocking choices")
                for (blocker in blockers) appendLine("- ${blocker.category}: ${blocker.message}")
            }
        }
    }

    private data class BlockingChoice(val category: String, val message: String, val hardBlock: Boolean) {
        fun toJson(): String = "{" + listOf(
            json("category", category),
            json("message", message),
            json("hardBlock", hardBlock)
        ).joinToString(",") + "}"
    }

    private data class VisibleStateConfig(
        val saveFile: String,
        val outputDir: String,
        val matchId: String
    ) {
        companion object {
            fun fromArgs(args: Array<String>): VisibleStateConfig {
                val values = LinkedHashMap<String, String>()
                var index = 0
                while (index < args.size) {
                    val arg = args[index]
                    if (!arg.startsWith("--")) { index++; continue }
                    val key = arg.removePrefix("--")
                    val next = args.getOrNull(index + 1)
                    if (next != null && !next.startsWith("--")) {
                        values[key] = next
                        index += 2
                    } else {
                        values[key] = "true"
                        index++
                    }
                }
                val output = values["output"] ?: "chat-player-output"
                return VisibleStateConfig(
                    saveFile = values["save-file"] ?: "$output/chat-player-save.json",
                    outputDir = output,
                    matchId = values["match-id"] ?: "chat-visible-state"
                )
            }
        }
    }
}

private fun arrayJson(values: Iterable<String>): String = values.joinToString(prefix = "[", postfix = "]")
private fun arrayJson(values: Sequence<String>): String = values.joinToString(prefix = "[", postfix = "]")
private fun stringArrayJson(values: Iterable<String>): String = arrayJson(values.map { "\"${escapeJsonV2(it)}\"" })
private fun json(name: String, value: String?): String = "\"$name\":${if (value == null) "null" else "\"${escapeJsonV2(value)}\""}"
private fun json(name: String, value: Int?): String = "\"$name\":${value ?: "null"}"
private fun json(name: String, value: Double): String = "\"$name\":$value"
private fun json(name: String, value: Boolean): String = "\"$name\":$value"
private fun escapeJsonV2(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
