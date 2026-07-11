package com.unciv.app.desktop

import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.ui.screens.worldscreen.unit.actions.executeHeadlessPillage
import com.unciv.ui.screens.worldscreen.unit.actions.getHeadlessPillageTitle
import java.io.File
import kotlin.system.exitProcess

/** Explicit non-automated special-unit actions that do not require a target-selection screen. */
internal object ChatSpecialUnitActions {
    private val mappedTypes = listOf(
        UnitActionType.HurryResearch,
        UnitActionType.HurryPolicy,
        UnitActionType.HurryWonder,
        UnitActionType.HurryBuilding,
        UnitActionType.ConductTradeMission,
        UnitActionType.SetUp,
        UnitActionType.Guard,
        UnitActionType.Transform,
        UnitActionType.CreateImprovement,
        UnitActionType.AddInCapital
    )

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
        println("Special unit actions complete: actions=${results.size} applied=${results.count { it.applied }} rejected=${results.count { !it.applied }}")
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
            .findAll(text).map { unescapeSpecialJson(it.groupValues[1]) }.toList()
        if (fromJson.isNotEmpty()) return fromJson
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }

    private fun applyAction(civ: Civilization, actionId: String): SpecialResult {
        val legal = legalActions(civ).associateBy { it.actionId }
        val selected = legal[actionId]
            ?: return SpecialResult(actionId, false, false, "Rejected: unknown or currently illegal special-unit action")
        val parts = actionId.split(":", limit = 5)
        if (parts.size < 4 || parts[0] != "unit" || parts[2] != "special")
            return SpecialResult(actionId, false, false, "Rejected: malformed special-unit action")
        val unit = findUnit(civ, parts[1]) ?: return SpecialResult(actionId, false, false, "Rejected: unit no longer exists")
        val id = unit.id
        val oldName = unit.name
        val oldGold = civ.gold
        return runCatching {
            val executed = when (selected.category) {
                "mapped" -> executeMapped(unit, parts[3], parts.getOrNull(4)?.toIntOrNull() ?: -1)
                "pillage" -> unit.executeHeadlessPillage()
                "escort" -> { unit.startEscorting(); true }
                "stopEscort" -> { unit.stopEscorting(); true }
                "disband" -> { unit.disband(); civ.updateStatsForNextTurn(); true }
                else -> false
            }
            if (!executed) SpecialResult(actionId, false, false, "Rejected: ${selected.label} is no longer executable")
            else {
                val surviving = findUnit(civ, id.toString())
                SpecialResult(
                    actionId,
                    true,
                    false,
                    "Executed ${selected.label}; unit=$oldName #$id; unitConsumedOrReplaced=${surviving == null || surviving.name != oldName}; gold $oldGold -> ${civ.gold}"
                )
            }
        }.getOrElse {
            SpecialResult(actionId, false, true, "Error while executing special-unit action: ${it.message ?: it::class.simpleName}")
        }
    }

    private fun executeMapped(unit: MapUnit, typeName: String, ordinal: Int): Boolean {
        val type = mappedTypes.firstOrNull { it.name == typeName } ?: return false
        val action = executableMappedActions(unit, type).getOrNull(ordinal)?.action ?: return false
        action.invoke()
        return true
    }

    private fun executableMappedActions(unit: MapUnit, type: UnitActionType): List<UnitAction> =
        UnitActions.getUnitActions(unit, type).filter { it.action != null }.toList()

    private fun legalActions(civ: Civilization): List<SpecialLegalAction> {
        val actions = ArrayList<SpecialLegalAction>()
        for (unit in civ.units.getCivUnits().sortedWith(compareBy<MapUnit> { it.id }.thenBy { it.name })) {
            for (type in mappedTypes) {
                for ((ordinal, action) in executableMappedActions(unit, type).withIndex()) {
                    actions += SpecialLegalAction(
                        "unit:${unit.id}:special:${type.name}:$ordinal",
                        "mapped",
                        "${action.title} with ${unit.name} #${unit.id} at ${unit.currentTile.position}"
                    )
                }
            }
            val pillageTitle = unit.getHeadlessPillageTitle()
            if (pillageTitle != null) {
                actions += SpecialLegalAction(
                    "unit:${unit.id}:special:pillage",
                    "pillage",
                    "$pillageTitle with ${unit.name} #${unit.id} at ${unit.currentTile.position}"
                )
            }
            if (!unit.baseUnit.movesLikeAirUnits && unit.getOtherEscortUnit() != null) {
                if (unit.isEscorting()) {
                    actions += SpecialLegalAction(
                        "unit:${unit.id}:special:stopEscort",
                        "stopEscort",
                        "Stop escort formation for ${unit.name} #${unit.id}"
                    )
                } else {
                    actions += SpecialLegalAction(
                        "unit:${unit.id}:special:escort",
                        "escort",
                        "Start escort formation for ${unit.name} #${unit.id}"
                    )
                }
            }
            if (unit.hasMovement()) {
                val disbandGold = if (unit.currentTile.getOwner() == civ) unit.baseUnit.getDisbandGold(civ) else 0
                actions += SpecialLegalAction(
                    "unit:${unit.id}:special:disband",
                    "disband",
                    "Disband ${unit.name} #${unit.id}${if (disbandGold > 0) " for $disbandGold gold" else ""}"
                )
            }
        }
        return actions
    }

    private fun findUnit(civ: Civilization, idText: String): MapUnit? =
        idText.toIntOrNull()?.let { id -> civ.units.getCivUnits().firstOrNull { it.id == id } }

    private fun writeOutputs(config: Config, gameInfo: GameInfo, results: List<SpecialResult>) {
        val outputDir = File(config.outputDir).apply { mkdirs() }
        val legal = legalActions(gameInfo.currentPlayerCiv)
        File(outputDir, "special-unit-legal-actions.json").writeText(specialArrayJson(legal.map { it.toJson() }) + "\n")
        File(outputDir, "special-unit-result.json").writeText(
            "{" + listOf(
                specialJson("turn", gameInfo.turns),
                specialJson("automationUsed", false),
                "\"results\":${specialArrayJson(results.map { it.toJson() })}"
            ).joinToString(",") + "}\n"
        )
    }

    private data class SpecialLegalAction(val actionId: String, val category: String, val label: String) {
        fun toJson() = "{" + listOf(specialJson("actionId", actionId), specialJson("category", category), specialJson("label", label)).joinToString(",") + "}"
    }
    private data class SpecialResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson() = "{" + listOf(specialJson("actionId", actionId), specialJson("applied", applied), specialJson("error", error), specialJson("message", message)).joinToString(",") + "}"
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

private fun specialArrayJson(values: Iterable<String>) = values.joinToString(prefix = "[", postfix = "]")
private fun specialJson(name: String, value: String?) = "\"$name\":${if (value == null) "null" else "\"${escapeSpecialJson(value)}\""}"
private fun specialJson(name: String, value: Int) = "\"$name\":$value"
private fun specialJson(name: String, value: Boolean) = "\"$name\":$value"
private fun escapeSpecialJson(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
private fun unescapeSpecialJson(value: String) = value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
