package com.unciv.app.desktop

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
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.ui.screens.worldscreen.unit.actions.executeHeadlessReligionUnitAction
import com.unciv.ui.screens.worldscreen.unit.actions.getHeadlessReligionUnitActions
import java.io.File
import kotlin.system.exitProcess

/** Explicit prophet, missionary, and inquisitor actions using the core game's action lambdas. */
internal object ChatReligionUnitActions {
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
        println("Religion unit actions complete: actions=${results.size} applied=${results.count { it.applied }} rejected=${results.count { !it.applied }}")
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
            .findAll(text).map { unescapeReligionUnitJson(it.groupValues[1]) }.toList()
        if (fromJson.isNotEmpty()) return fromJson
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }

    private fun applyAction(civ: Civilization, actionId: String): ReligionUnitResult {
        val legal = legalActions(civ).associateBy { it.actionId }
        val selected = legal[actionId]
            ?: return ReligionUnitResult(actionId, false, false, "Rejected: unknown or currently illegal religion unit action")
        val parts = actionId.split(":", limit = 4)
        if (parts.size != 4 || parts[0] != "unit" || parts[2] != "religion")
            return ReligionUnitResult(actionId, false, false, "Rejected: malformed religion unit action")
        val unit = findUnit(civ, parts[1]) ?: return ReligionUnitResult(actionId, false, false, "Rejected: unit no longer exists")
        val unitName = unit.name
        val unitId = unit.id
        val position = unit.currentTile.position
        return runCatching {
            val executed = unit.executeHeadlessReligionUnitAction(parts[3])
            if (!executed) ReligionUnitResult(actionId, false, false, "Rejected: ${selected.label} is no longer executable")
            else {
                val surviving = findUnit(civ, unitId.toString())
                ReligionUnitResult(
                    actionId,
                    true,
                    false,
                    "Executed ${selected.label} with $unitName #$unitId at $position; unitConsumed=${surviving == null}; religionState=${civ.religionManager.religionState}"
                )
            }
        }.getOrElse {
            ReligionUnitResult(actionId, false, true, "Error while executing religion unit action: ${it.message ?: it::class.simpleName}")
        }
    }

    private fun legalActions(civ: Civilization): List<ReligionUnitLegalAction> =
        civ.units.getCivUnits().sortedWith(compareBy<MapUnit> { it.id }.thenBy { it.name }).flatMap { unit ->
            unit.getHeadlessReligionUnitActions().map { action ->
                ReligionUnitLegalAction(
                    "unit:${unit.id}:religion:${action.type}",
                    action.type,
                    "${action.title} with ${unit.name} #${unit.id} at ${unit.currentTile.position}"
                )
            }
        }.toList()

    private fun findUnit(civ: Civilization, idText: String): MapUnit? =
        idText.toIntOrNull()?.let { id -> civ.units.getCivUnits().firstOrNull { it.id == id } }

    private fun writeOutputs(config: Config, gameInfo: GameInfo, results: List<ReligionUnitResult>) {
        val outputDir = File(config.outputDir).apply { mkdirs() }
        val legal = legalActions(gameInfo.currentPlayerCiv)
        File(outputDir, "religion-unit-legal-actions.json").writeText(religionUnitArrayJson(legal.map { it.toJson() }) + "\n")
        File(outputDir, "religion-unit-result.json").writeText(
            "{" + listOf(
                religionUnitJson("turn", gameInfo.turns),
                religionUnitJson("religionState", gameInfo.currentPlayerCiv.religionManager.religionState.name),
                religionUnitJson("automationUsed", false),
                "\"results\":${religionUnitArrayJson(results.map { it.toJson() })}"
            ).joinToString(",") + "}\n"
        )
    }

    private data class ReligionUnitLegalAction(val actionId: String, val category: String, val label: String) {
        fun toJson() = "{" + listOf(religionUnitJson("actionId", actionId), religionUnitJson("category", category), religionUnitJson("label", label)).joinToString(",") + "}"
    }
    private data class ReligionUnitResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson() = "{" + listOf(religionUnitJson("actionId", actionId), religionUnitJson("applied", applied), religionUnitJson("error", error), religionUnitJson("message", message)).joinToString(",") + "}"
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

private fun religionUnitArrayJson(values: Iterable<String>) = values.joinToString(prefix = "[", postfix = "]")
private fun religionUnitJson(name: String, value: String?) = "\"$name\":${if (value == null) "null" else "\"${escapeReligionUnitJson(value)}\""}"
private fun religionUnitJson(name: String, value: Int) = "\"$name\":$value"
private fun religionUnitJson(name: String, value: Boolean) = "\"$name\":$value"
private fun escapeReligionUnitJson(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
private fun unescapeReligionUnitJson(value: String) = value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
