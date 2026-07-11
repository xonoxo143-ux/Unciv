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
        val config = WorkerConfig.fromArgs(args)
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
        println("Worker chat actions complete: actions=${results.size} applied=${results.count { it.applied }} rejected=${results.count { !it.applied }}")
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
            .findAll(text)
            .map { unescapeWorkerJson(it.groupValues[1]) }
            .toList()
        if (fromJson.isNotEmpty()) return fromJson
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }

    private fun applyAction(civ: Civilization, actionId: String): WorkerResult {
        val legal = legalActions(civ).associateBy { it.actionId }
        val action = legal[actionId]
            ?: return WorkerResult(actionId, false, false, "Rejected: unknown or currently illegal worker action")
        return runCatching {
            when (action.category) {
                "build" -> applyBuild(civ, actionId)
                "repair" -> applyRepair(civ, actionId)
                "cancel" -> applyCancel(civ, actionId)
                else -> WorkerOutcome(false, "Rejected: unsupported worker action category")
            }
        }.fold(
            onSuccess = { WorkerResult(actionId, it.applied, false, it.message) },
            onFailure = { WorkerResult(actionId, false, true, "Error while applying worker action: ${it.message ?: it::class.simpleName}") }
        )
    }

    private fun applyBuild(civ: Civilization, actionId: String): WorkerOutcome {
        val parts = actionId.split(":", limit = 4)
        if (parts.size != 4) return WorkerOutcome(false, "Rejected: malformed build action")
        val unit = findUnit(civ, parts[1]) ?: return WorkerOutcome(false, "Rejected: worker ${parts[1]} not found")
        val improvement = legalBuildImprovements(unit).firstOrNull { it.name == parts[3] }
            ?: return WorkerOutcome(false, "Rejected: ${parts[3]} cannot be built here by unit ${unit.id}")
        val tile = unit.getTile()
        tile.startWorkingOnImprovement(improvement, civ, unit)
        unit.action = null
        unit.due = false
        return WorkerOutcome(true, "Unit ${unit.id} (${unit.name}) started ${improvement.name} at ${tile.position}; turns=${tile.turnsToImprovement}")
    }

    private fun applyRepair(civ: Civilization, actionId: String): WorkerOutcome {
        val parts = actionId.split(":", limit = 3)
        if (parts.size != 3) return WorkerOutcome(false, "Rejected: malformed repair action")
        val unit = findUnit(civ, parts[1]) ?: return WorkerOutcome(false, "Rejected: worker ${parts[1]} not found")
        val action = UnitActionsFromUniques.getRepairAction(unit)
            ?: return WorkerOutcome(false, "Rejected: unit ${unit.id} cannot repair this tile")
        val executable = action.action ?: return WorkerOutcome(false, "Rejected: repair is not currently executable")
        executable.invoke()
        unit.due = false
        return WorkerOutcome(true, "Unit ${unit.id} (${unit.name}) started repair at ${unit.currentTile.position}; turns=${unit.currentTile.turnsToImprovement}")
    }

    private fun applyCancel(civ: Civilization, actionId: String): WorkerOutcome {
        val parts = actionId.split(":", limit = 3)
        if (parts.size != 3) return WorkerOutcome(false, "Rejected: malformed cancel action")
        val unit = findUnit(civ, parts[1]) ?: return WorkerOutcome(false, "Rejected: worker ${parts[1]} not found")
        val tile = unit.getTile()
        val old = tile.improvementInProgress ?: return WorkerOutcome(false, "Rejected: no improvement is in progress")
        tile.stopWorkingOnImprovement()
        unit.due = true
        return WorkerOutcome(true, "Cancelled $old at ${tile.position} for unit ${unit.id}")
    }

    private fun legalActions(civ: Civilization): List<WorkerLegalAction> {
        val actions = ArrayList<WorkerLegalAction>()
        for (unit in civ.units.getCivUnits().sortedWith(compareBy<MapUnit> { it.id }.thenBy { it.name })) {
            if (!isAvailableWorker(unit)) continue
            val tile = unit.getTile()
            for (improvement in legalBuildImprovements(unit)) {
                actions += WorkerLegalAction(
                    "worker:${unit.id}:build:${improvement.name}",
                    "build",
                    "Build ${improvement.name} with ${unit.name} #${unit.id} at ${tile.position} (${improvement.getTurnsToBuild(civ, unit)} turns)"
                )
            }
            val repair = UnitActionsFromUniques.getRepairAction(unit)
            if (repair?.action != null) {
                actions += WorkerLegalAction(
                    "worker:${unit.id}:repair",
                    "repair",
                    "Repair ${tile.getImprovementToRepair()?.name ?: "tile"} with ${unit.name} #${unit.id} at ${tile.position}"
                )
            }
            if (tile.improvementInProgress != null) {
                actions += WorkerLegalAction(
                    "worker:${unit.id}:cancel",
                    "cancel",
                    "Cancel ${tile.improvementInProgress} at ${tile.position}"
                )
            }
        }
        return actions
    }

    private fun isAvailableWorker(unit: MapUnit): Boolean =
        unit.hasMovement() && !unit.isEmbarked() && unit.cache.hasUniqueToBuildImprovements

    private fun legalBuildImprovements(unit: MapUnit): List<TileImprovement> {
        if (!isAvailableWorker(unit)) return emptyList()
        val tile = unit.getTile()
        if (tile.isCityCenter()) return emptyList()
        return tile.ruleset.tileImprovements.values.asSequence()
            .filter { it.name != Constants.repair && it.name != Constants.cancelImprovementOrder }
            .filter { unit.canBuildImprovement(it, tile) }
            .filter { tile.improvementFunctions.canBuildImprovement(it, unit.cache.state) }
            .sortedBy { it.name }
            .toList()
    }

    private fun findUnit(civ: Civilization, idText: String): MapUnit? =
        idText.toIntOrNull()?.let { id -> civ.units.getCivUnits().firstOrNull { it.id == id } }

    private fun writeOutputs(config: WorkerConfig, gameInfo: GameInfo, results: List<WorkerResult>) {
        val outputDir = File(config.outputDir).apply { mkdirs() }
        val legal = legalActions(gameInfo.currentPlayerCiv)
        File(outputDir, "worker-legal-actions.json").writeText(workerArrayJson(legal.map { it.toJson() }) + "\n")
        File(outputDir, "worker-result.json").writeText(
            "{" + listOf(
                workerJson("turn", gameInfo.turns),
                workerJson("currentPlayer", gameInfo.currentPlayer),
                workerJson("automationUsed", false),
                "\"results\":${workerArrayJson(results.map { it.toJson() })}"
            ).joinToString(",") + "}\n"
        )
    }

    private data class WorkerOutcome(val applied: Boolean, val message: String)

    private data class WorkerLegalAction(val actionId: String, val category: String, val label: String) {
        fun toJson(): String = "{" + listOf(
            workerJson("actionId", actionId),
            workerJson("category", category),
            workerJson("label", label)
        ).joinToString(",") + "}"
    }

    private data class WorkerResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson(): String = "{" + listOf(
            workerJson("actionId", actionId),
            workerJson("applied", applied),
            workerJson("error", error),
            workerJson("message", message)
        ).joinToString(",") + "}"
    }

    private data class WorkerConfig(val saveFile: String, val outputDir: String, val commandsFile: String?) {
        companion object {
            fun fromArgs(args: Array<String>): WorkerConfig {
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
                return WorkerConfig(
                    saveFile = values["save-file"] ?: "$output/chat-player-save.json",
                    outputDir = output,
                    commandsFile = values["commands"]
                )
            }
        }
    }
}

private fun workerArrayJson(values: Iterable<String>): String = values.joinToString(prefix = "[", postfix = "]")
private fun workerJson(name: String, value: String?): String = "\"$name\":${if (value == null) "null" else "\"${escapeWorkerJson(value)}\"}"
private fun workerJson(name: String, value: Int): String = "\"$name\":$value"
private fun workerJson(name: String, value: Boolean): String = "\"$name\":$value"
private fun escapeWorkerJson(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
private fun unescapeWorkerJson(value: String): String = value
    .replace("\\n", "\n")
    .replace("\\\"", "\"")
    .replace("\\\\", "\\")
