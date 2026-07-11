package com.unciv.app.desktop

import com.unciv.Constants
import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques
import java.io.File
import kotlin.system.exitProcess

/** Explicit, no-automation worker improvement controls for the chat benchmark. */
internal object ChatWorkerActions {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = Config.fromArgs(args)
        initializeUnciv()
        val save = File(config.saveFile)
        if (!save.isFile) { System.err.println("Save file not found: ${save.absolutePath}"); exitProcess(2) }
        ensureBenchmarkNations()
        val game = UncivFiles.gameInfoFromString(save.readText())
        UncivGame.Current.gameInfo = game
        val civ = game.currentPlayerCiv
        val results = (config.commandsFile?.let { readActionIds(File(it)) } ?: emptyList()).map { apply(civ, it) }
        if (results.none { it.error }) save.writeText(UncivFiles.gameInfoToString(game, forceZip = false, updateChecksum = false))
        writeOutputs(config, game, results)
        println("Worker chat actions complete: actions=${results.size} applied=${results.count { it.applied }} rejected=${results.count { !it.applied }}")
        exitProcess(if (results.any { it.error }) 1 else 0)
    }

    private fun initializeUnciv() {
        UncivGame.Current = UncivGame(true)
        UncivGame.Current.settings = GameSettings().apply { showTutorials = false; turnsBetweenAutosaves = 10000 }
        RulesetCache.loadRulesets(true); SkinCache.loadSkinConfigs(true); TileSetCache.loadTileSetConfigs(true)
    }

    private fun ensureBenchmarkNations() = RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!.also { ruleset ->
        if (simulationCiv1 !in ruleset.nations) ruleset.nations[simulationCiv1] = Nation().apply { name = simulationCiv1 }
        if (simulationCiv2 !in ruleset.nations) ruleset.nations[simulationCiv2] = Nation().apply { name = simulationCiv2 }
    }

    private fun readActionIds(file: File): List<String> {
        if (!file.isFile) return emptyList()
        val text = file.readText()
        val jsonIds = Regex("\"actionId\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .findAll(text).map { unescapeWorkerJson(it.groupValues[1]) }.toList()
        return jsonIds.ifEmpty { text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList() }
    }

    private fun apply(civ: Civilization, actionId: String): Result {
        val legal = legalActions(civ).associateBy { it.id }[actionId]
            ?: return Result(actionId, false, false, "Rejected: unknown or currently illegal worker action")
        return runCatching {
            val parts = actionId.split(":", limit = 4)
            val unit = parts.getOrNull(1)?.toIntOrNull()?.let { findUnit(civ, it) }
                ?: return@runCatching Outcome(false, "Rejected: worker not found")
            when (legal.category) {
                "build" -> {
                    val improvement = legalBuilds(unit).firstOrNull { it.name == parts.getOrNull(3) }
                        ?: return@runCatching Outcome(false, "Rejected: improvement is no longer legal")
                    val tile = unit.currentTile
                    tile.startWorkingOnImprovement(improvement, civ, unit)
                    unit.action = null; unit.due = false
                    Outcome(true, "Unit ${unit.id} started ${improvement.name} at ${tile.position}; turns=${tile.turnsToImprovement}")
                }
                "repair" -> {
                    val repair = UnitActionsFromUniques.getRepairAction(unit)?.action
                        ?: return@runCatching Outcome(false, "Rejected: repair is no longer executable")
                    repair.invoke(); unit.due = false
                    Outcome(true, "Unit ${unit.id} started repair at ${unit.currentTile.position}; turns=${unit.currentTile.turnsToImprovement}")
                }
                "cancel" -> {
                    val tile = unit.currentTile
                    val old = tile.improvementInProgress ?: return@runCatching Outcome(false, "Rejected: no work is in progress")
                    tile.stopWorkingOnImprovement(); unit.due = true
                    Outcome(true, "Cancelled $old at ${tile.position}")
                }
                else -> Outcome(false, "Rejected: unsupported worker category")
            }
        }.fold(
            onSuccess = { Result(actionId, it.applied, false, it.message) },
            onFailure = { Result(actionId, false, true, "Error while applying worker action: ${it.message ?: it::class.simpleName}") }
        )
    }

    private fun legalActions(civ: Civilization): List<Legal> = buildList {
        for (unit in civ.units.getCivUnits().sortedBy { it.id }) {
            if (!availableWorker(unit)) continue
            val tile = unit.currentTile
            for (improvement in legalBuilds(unit)) add(Legal(
                "worker:${unit.id}:build:${improvement.name}", "build",
                "Build ${improvement.name} with ${unit.name} #${unit.id} at ${tile.position} (${improvement.getTurnsToBuild(civ, unit)} turns)"
            ))
            if (UnitActionsFromUniques.getRepairAction(unit)?.action != null) add(Legal(
                "worker:${unit.id}:repair", "repair", "Repair ${tile.getImprovementToRepair()?.name ?: "tile"} at ${tile.position}"
            ))
            if (tile.improvementInProgress != null) add(Legal(
                "worker:${unit.id}:cancel", "cancel", "Cancel ${tile.improvementInProgress} at ${tile.position}"
            ))
        }
    }

    private fun availableWorker(unit: MapUnit) = unit.hasMovement() && !unit.isEmbarked() && unit.cache.hasUniqueToBuildImprovements

    private fun legalBuilds(unit: MapUnit): List<TileImprovement> {
        if (!availableWorker(unit) || unit.currentTile.isCityCenter()) return emptyList()
        val tile = unit.currentTile
        return tile.ruleset.tileImprovements.values.asSequence()
            .filter { it.name != Constants.repair && it.name != Constants.cancelImprovementOrder }
            .filter { unit.canBuildImprovement(it, tile) }
            .filter { tile.improvementFunctions.canBuildImprovement(it, unit.cache.state) }
            .sortedBy { it.name }.toList()
    }

    private fun findUnit(civ: Civilization, id: Int) = civ.units.getCivUnits().firstOrNull { it.id == id }

    private fun writeOutputs(config: Config, game: GameInfo, results: List<Result>) {
        val out = File(config.outputDir).apply { mkdirs() }
        File(out, "worker-legal-actions.json").writeText(workerArrayJson(legalActions(game.currentPlayerCiv).map { it.toJson() }) + "\n")
        File(out, "worker-result.json").writeText("{" + listOf(
            workerJson("turn", game.turns), workerJson("currentPlayer", game.currentPlayer),
            workerJson("automationUsed", false), "\"results\":${workerArrayJson(results.map { it.toJson() })}"
        ).joinToString(",") + "}\n")
    }

    private data class Outcome(val applied: Boolean, val message: String)
    private data class Legal(val id: String, val category: String, val label: String) {
        fun toJson() = "{" + listOf(workerJson("actionId", id), workerJson("category", category), workerJson("label", label)).joinToString(",") + "}"
    }
    private data class Result(val id: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson() = "{" + listOf(workerJson("actionId", id), workerJson("applied", applied), workerJson("error", error), workerJson("message", message)).joinToString(",") + "}"
    }
    private data class Config(val saveFile: String, val outputDir: String, val commandsFile: String?) {
        companion object {
            fun fromArgs(args: Array<String>): Config {
                val values = linkedMapOf<String, String>(); var i = 0
                while (i < args.size) {
                    val arg = args[i]
                    if (!arg.startsWith("--")) { i++; continue }
                    val next = args.getOrNull(i + 1)
                    if (next != null && !next.startsWith("--")) { values[arg.removePrefix("--")] = next; i += 2 }
                    else { values[arg.removePrefix("--")] = "true"; i++ }
                }
                val out = values["output"] ?: "chat-player-output"
                return Config(values["save-file"] ?: "$out/chat-player-save.json", out, values["commands"])
            }
        }
    }
}

private fun workerArrayJson(values: Iterable<String>) = values.joinToString(prefix = "[", postfix = "]")
private fun workerJson(name: String, value: String?) = "\"$name\":${if (value == null) "null" else "\"${escapeWorkerJson(value)}\""}"
private fun workerJson(name: String, value: Int) = "\"$name\":$value"
private fun workerJson(name: String, value: Boolean) = "\"$name\":$value"
private fun escapeWorkerJson(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
private fun unescapeWorkerJson(value: String) = value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
