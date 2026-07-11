package com.unciv.app.desktop

import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.AirInterception
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import java.io.File
import kotlin.system.exitProcess

/** Explicit target selection for paradrop and air sweep. */
internal object ChatTargetUnitActions {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = Config.fromArgs(args)
        initializeUnciv()
        val saveFile = File(config.saveFile)
        if (!saveFile.isFile) {
            System.err.println("Save file not found: ${saveFile.absolutePath}")
            exitProcess(2)
        }
        ensureBenchmarkNations()
        val gameInfo = UncivFiles.gameInfoFromString(saveFile.readText())
        UncivGame.Current.gameInfo = gameInfo
        val civ = gameInfo.currentPlayerCiv
        val actionIds = config.commandsFile?.let { readActionIds(File(it)) } ?: emptyList()
        val results = actionIds.map { applyAction(civ, it) }
        if (results.none { it.error })
            saveFile.writeText(UncivFiles.gameInfoToString(gameInfo, forceZip = false, updateChecksum = false))
        writeOutputs(config, gameInfo, results)
        println("Target unit actions complete: actions=${results.size} applied=${results.count { it.applied }} rejected=${results.count { !it.applied }}")
        exitProcess(if (results.any { it.error }) 1 else 0)
    }

    private fun initializeUnciv() {
        UncivGame.Current = UncivGame(true)
        UncivGame.Current.settings = GameSettings().apply {
            showTutorials = false
            turnsBetweenAutosaves = 10000
        }
        RulesetCache.loadRulesets(true)
        SkinCache.loadSkinConfigs(true)
        TileSetCache.loadTileSetConfigs(true)
    }

    private fun ensureBenchmarkNations() =
        RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!.also { ruleset ->
            if (!ruleset.nations.containsKey(simulationCiv1)) ruleset.nations[simulationCiv1] = Nation().apply { name = simulationCiv1 }
            if (!ruleset.nations.containsKey(simulationCiv2)) ruleset.nations[simulationCiv2] = Nation().apply { name = simulationCiv2 }
        }

    private fun readActionIds(file: File): List<String> {
        if (!file.isFile) return emptyList()
        val text = file.readText()
        val fromJson = Regex("\"actionId\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .findAll(text).map { unescapeTargetJson(it.groupValues[1]) }.toList()
        if (fromJson.isNotEmpty()) return fromJson
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }

    private fun applyAction(civ: Civilization, actionId: String): TargetResult {
        val legal = legalActions(civ).associateBy { it.actionId }
        val selected = legal[actionId]
            ?: return TargetResult(actionId, false, false, "Rejected: unknown or currently illegal target action")
        val parts = actionId.split(":", limit = 5)
        if (parts.size != 5 || parts[0] != "unit" || parts[2] != "target")
            return TargetResult(actionId, false, false, "Rejected: malformed target action")
        val unit = findUnit(civ, parts[1]) ?: return TargetResult(actionId, false, false, "Rejected: unit no longer exists")
        val destination = parseTile(unit, parts[4]) ?: return TargetResult(actionId, false, false, "Rejected: malformed or off-map target")
        return runCatching {
            when (selected.category) {
                "paradrop" -> applyParadrop(unit, destination, actionId, selected.label)
                "airSweep" -> applyAirSweep(unit, destination, actionId, selected.label)
                else -> TargetResult(actionId, false, false, "Rejected: unsupported target category")
            }
        }.getOrElse {
            TargetResult(actionId, false, true, "Error while executing target action: ${it.message ?: it::class.simpleName}")
        }
    }

    private fun applyParadrop(unit: MapUnit, destination: Tile, actionId: String, label: String): TargetResult {
        if (destination !in paradropTargets(unit))
            return TargetResult(actionId, false, false, "Rejected: paradrop destination is no longer legal")
        val origin = unit.currentTile.position
        val healthBefore = unit.health
        unit.action = UnitActionType.Paradrop.value
        unit.movement.moveToTile(destination)
        return TargetResult(
            actionId,
            true,
            false,
            "Executed $label; position $origin -> ${unit.currentTile.position}; health $healthBefore -> ${unit.health}; movement=${unit.currentMovement}"
        )
    }

    private fun applyAirSweep(unit: MapUnit, target: Tile, actionId: String, label: String): TargetResult {
        if (target !in airSweepTargets(unit))
            return TargetResult(actionId, false, false, "Rejected: air-sweep target is no longer legal")
        val healthBefore = unit.health
        unit.action = UnitActionType.AirSweep.value
        AirInterception.airSweep(MapUnitCombatant(unit), target)
        val surviving = findUnit(unit.civ, unit.id.toString())
        return TargetResult(
            actionId,
            true,
            false,
            "Executed $label; target=${target.position}; aircraft health $healthBefore -> ${surviving?.health ?: 0}; aircraftDestroyed=${surviving == null}; hidden interceptor identity not exported"
        )
    }

    private fun legalActions(civ: Civilization): List<TargetLegalAction> {
        val actions = ArrayList<TargetLegalAction>()
        for (unit in civ.units.getCivUnits().sortedWith(compareBy<MapUnit> { it.id }.thenBy { it.name })) {
            for (tile in paradropTargets(unit)) {
                actions += TargetLegalAction(
                    "unit:${unit.id}:target:paradrop:${tile.position.x},${tile.position.y}",
                    "paradrop",
                    "Paradrop ${unit.name} #${unit.id} to ${tile.position}"
                )
            }
            for (tile in airSweepTargets(unit)) {
                actions += TargetLegalAction(
                    "unit:${unit.id}:target:airSweep:${tile.position.x},${tile.position.y}",
                    "airSweep",
                    "Air sweep visible tile ${tile.position} with ${unit.name} #${unit.id}"
                )
            }
        }
        return actions
    }

    private fun paradropTargets(unit: MapUnit): List<Tile> {
        val toggleAvailable = UnitActions.getUnitActions(unit, UnitActionType.Paradrop).any { it.action != null }
        if (!toggleAvailable) return emptyList()
        val oldAction = unit.action
        return try {
            unit.action = UnitActionType.Paradrop.value
            unit.movement.getReachableTilesInCurrentTurn()
                .filter { it != unit.currentTile && it.isVisible(unit.civ) }
                .distinctBy { it.position }
                .sortedWith(compareBy<Tile> { it.position.x }.thenBy { it.position.y })
                .toList()
        } finally {
            unit.action = oldAction
        }
    }

    private fun airSweepTargets(unit: MapUnit): List<Tile> {
        val toggleAvailable = UnitActions.getUnitActions(unit, UnitActionType.AirSweep).any { it.action != null }
        if (!toggleAvailable) return emptyList()
        val oldAction = unit.action
        return try {
            unit.action = UnitActionType.AirSweep.value
            TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles(), stayOnTile = true)
                .map { it.tileToAttack }
                .filter { it.isVisible(unit.civ) }
                .distinctBy { it.position }
                .sortedWith(compareBy<Tile> { it.position.x }.thenBy { it.position.y })
        } finally {
            unit.action = oldAction
        }
    }

    private fun parseTile(unit: MapUnit, text: String): Tile? {
        val coordinates = text.split(",", limit = 2)
        if (coordinates.size != 2) return null
        val x = coordinates[0].toIntOrNull() ?: return null
        val y = coordinates[1].toIntOrNull() ?: return null
        return unit.currentTile.tileMap.getOrNull(x, y)
    }

    private fun findUnit(civ: Civilization, idText: String): MapUnit? =
        idText.toIntOrNull()?.let { id -> civ.units.getCivUnits().firstOrNull { it.id == id } }

    private fun writeOutputs(config: Config, gameInfo: GameInfo, results: List<TargetResult>) {
        val outputDir = File(config.outputDir).apply { mkdirs() }
        val legal = legalActions(gameInfo.currentPlayerCiv)
        File(outputDir, "target-unit-legal-actions.json").writeText(targetArrayJson(legal.map { it.toJson() }) + "\n")
        File(outputDir, "target-unit-result.json").writeText(
            "{" + listOf(
                targetJson("turn", gameInfo.turns),
                targetJson("visibleTargetsOnly", true),
                targetJson("automationUsed", false),
                "\"results\":${targetArrayJson(results.map { it.toJson() })}"
            ).joinToString(",") + "}\n"
        )
    }

    private data class TargetLegalAction(val actionId: String, val category: String, val label: String) {
        fun toJson() = "{" + listOf(targetJson("actionId", actionId), targetJson("category", category), targetJson("label", label)).joinToString(",") + "}"
    }
    private data class TargetResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson() = "{" + listOf(targetJson("actionId", actionId), targetJson("applied", applied), targetJson("error", error), targetJson("message", message)).joinToString(",") + "}"
    }
    private data class Config(val saveFile: String, val outputDir: String, val commandsFile: String?) {
        companion object {
            fun fromArgs(args: Array<String>): Config {
                val values = LinkedHashMap<String, String>()
                var index = 0
                while (index < args.size) {
                    val arg = args[index]
                    if (!arg.startsWith("--")) { index++; continue }
                    val key = arg.removePrefix("--")
                    val next = args.getOrNull(index + 1)
                    if (next != null && !next.startsWith("--")) { values[key] = next; index += 2 }
                    else { values[key] = "true"; index++ }
                }
                val output = values["output"] ?: "chat-player-output"
                return Config(values["save-file"] ?: "$output/chat-player-save.json", output, values["commands"])
            }
        }
    }
}

private fun targetArrayJson(values: Iterable<String>) = values.joinToString(prefix = "[", postfix = "]")
private fun targetJson(name: String, value: String?) = "\"$name\":${if (value == null) "null" else "\"${escapeTargetJson(value)}\""}"
private fun targetJson(name: String, value: Int) = "\"$name\":$value"
private fun targetJson(name: String, value: Boolean) = "\"$name\":$value"
private fun escapeTargetJson(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
private fun unescapeTargetJson(value: String) = value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
