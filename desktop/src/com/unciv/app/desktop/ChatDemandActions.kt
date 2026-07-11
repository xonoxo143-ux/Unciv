package com.unciv.app.desktop

import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Explicit responses to AI diplomatic demands. */
internal object ChatDemandActions {
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
        val results = (config.commandsFile?.let { readActionIds(File(it)) } ?: emptyList()).map { apply(game, civ, it) }
        if (results.none { it.error }) save.writeText(UncivFiles.gameInfoToString(game, forceZip = false, updateChecksum = false))
        writeOutputs(config, game, results)
        println("Demand responses complete: actions=${results.size} applied=${results.count { it.applied }} pending=${pendingDemands(civ).size}")
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
        val ids = Regex("\"actionId\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .findAll(text).map { unescapeDemandJson(it.groupValues[1]) }.toList()
        return ids.ifEmpty { text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList() }
    }

    private fun apply(game: GameInfo, civ: Civilization, actionId: String): DemandResult {
        val legal = legalActions(civ).associateBy { it.actionId }[actionId]
            ?: return DemandResult(actionId, false, false, "Rejected: unknown or currently illegal demand response")
        val parts = actionId.split(":", limit = 4)
        if (parts.size != 4 || parts[0] != "demand") return DemandResult(actionId, false, false, "Rejected: malformed demand response")
        val index = parts[1].toIntOrNull() ?: return DemandResult(actionId, false, false, "Rejected: invalid demand index")
        val pending = pendingDemands(civ).getOrNull(index) ?: return DemandResult(actionId, false, false, "Rejected: demand no longer exists")
        val other = game.getCivilization(pending.alert.value)
        if (other.isDefeated()) {
            civ.popupAlerts.remove(pending.alert)
            return DemandResult(actionId, false, false, "Rejected: demanding civilization is defeated; stale alert removed")
        }
        val manager = civ.getDiplomacyManager(other)
            ?: return DemandResult(actionId, false, false, "Rejected: diplomacy relationship no longer exists")
        return runCatching {
            when (legal.category) {
                "agree" -> manager.agreeToDemand(pending.demand)
                "refuse" -> {
                    manager.refuseDemand(pending.demand)
                    if (pending.demand == Demand.DoNotAttackUs && manager.canDeclareWar()) manager.declareWar()
                }
            }
            civ.popupAlerts.remove(pending.alert)
            DemandResult(
                actionId, true, false,
                "${if (legal.category == "agree") "Agreed to" else "Refused"} ${pending.demand.name} requested by ${other.civName}; atWar=${civ.isAtWarWith(other)}"
            )
        }.getOrElse { DemandResult(actionId, false, true, "Error while responding to demand: ${it.message ?: it::class.simpleName}") }
    }

    private fun legalActions(civ: Civilization): List<LegalDemandAction> = buildList {
        for ((index, pending) in pendingDemands(civ).withIndex()) {
            val other = civ.gameInfo.getCivilization(pending.alert.value)
            if (other.isDefeated() || civ.getDiplomacyManager(other) == null) continue
            add(LegalDemandAction("demand:$index:${pending.demand.name}:agree", "agree", "Agree to ${pending.demand.demandText} from ${other.civName}"))
            add(LegalDemandAction("demand:$index:${pending.demand.name}:refuse", "refuse", "Refuse ${pending.demand.demandText} from ${other.civName}${if (pending.demand == Demand.DoNotAttackUs) " and declare war" else ""}"))
        }
    }

    private fun pendingDemands(civ: Civilization): List<PendingDemand> = civ.popupAlerts.mapNotNull { alert ->
        val demand = when (alert.type) {
            AlertType.DemandToStopSettlingCitiesNear -> Demand.DoNotSettleNearUs
            AlertType.DemandToStopSpreadingReligion -> Demand.DoNotSpreadReligion
            AlertType.DemandToStopSpyingOnUs -> Demand.DontSpyOnUs
            AlertType.DemandToNotAttackUs -> Demand.DoNotAttackUs
            else -> null
        } ?: return@mapNotNull null
        PendingDemand(alert, demand)
    }

    private fun writeOutputs(config: Config, game: GameInfo, results: List<DemandResult>) {
        val out = File(config.outputDir).apply { mkdirs() }
        val civ = game.currentPlayerCiv
        File(out, "demand-legal-actions.json").writeText(demandArrayJson(legalActions(civ).map { it.toJson() }) + "\n")
        File(out, "demand-result.json").writeText("{" + listOf(
            demandJson("turn", game.turns), demandJson("pendingDemands", pendingDemands(civ).size), demandJson("automationUsed", false),
            "\"results\":${demandArrayJson(results.map { it.toJson() })}"
        ).joinToString(",") + "}\n")
    }

    private data class PendingDemand(val alert: PopupAlert, val demand: Demand)
    private data class LegalDemandAction(val actionId: String, val category: String, val label: String) {
        fun toJson() = "{" + listOf(demandJson("actionId", actionId), demandJson("category", category), demandJson("label", label)).joinToString(",") + "}"
    }
    private data class DemandResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson() = "{" + listOf(demandJson("actionId", actionId), demandJson("applied", applied), demandJson("error", error), demandJson("message", message)).joinToString(",") + "}"
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

private fun demandArrayJson(values: Iterable<String>) = values.joinToString(prefix = "[", postfix = "]")
private fun demandJson(name: String, value: String?) = "\"$name\":${if (value == null) "null" else "\"${escapeDemandJson(value)}\""}"
private fun demandJson(name: String, value: Int) = "\"$name\":$value"
private fun demandJson(name: String, value: Boolean) = "\"$name\":$value"
private fun escapeDemandJson(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
private fun unescapeDemandJson(value: String) = value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
