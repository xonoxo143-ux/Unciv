package com.unciv.app.desktop

import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Guarded turn advancement. Never automates unresolved player choices or units. */
internal object ChatTurnActions {
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
        val actionIds = config.commandsFile?.let { readActionIds(File(it)) } ?: emptyList()
        val results = actionIds.map { apply(game, civ, it) }
        if (results.none { it.error }) save.writeText(UncivFiles.gameInfoToString(game, forceZip = false, updateChecksum = false))
        writeOutputs(config, game, results)
        println("Guarded turn actions complete: turn=${game.turns} actions=${results.size} blockers=${blockers(game.currentPlayerCiv).size}")
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
            .findAll(text).map { unescapeTurnJson(it.groupValues[1]) }.toList()
        return ids.ifEmpty { text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList() }
    }

    private fun apply(game: GameInfo, civ: Civilization, actionId: String): TurnResult {
        if (actionId != "turn:end") return TurnResult(actionId, false, false, "Rejected: unknown turn action")
        val blocking = blockers(civ)
        if (blocking.isNotEmpty()) return TurnResult(
            actionId, false, false,
            "Rejected: ${blocking.size} unresolved choice(s): ${blocking.joinToString { "${it.category}=${it.message}" }}"
        )
        return runCatching {
            val turnBefore = game.turns
            val playerBefore = game.currentPlayer
            game.nextTurn()
            TurnResult(actionId, true, false, "Ended turn $turnBefore for $playerBefore; control returned to ${game.currentPlayer} on turn ${game.turns}")
        }.getOrElse { TurnResult(actionId, false, true, "Error while ending turn: ${it.message ?: it::class.simpleName}") }
    }

    private fun blockers(civ: Civilization): List<Blocker> = buildList {
        if (civ.cities.isEmpty()) add(Blocker("foundCity", "No city has been founded"))
        for (city in civ.cities.filter { !it.isPuppet && it.cityConstructions.currentConstructionName().isEmpty() })
            add(Blocker("construction", "${city.name} has no construction selected"))
        if (civ.shouldOpenTechPicker()) add(Blocker("technology", if (civ.tech.freeTechs > 0) "${civ.tech.freeTechs} free technology choice(s) pending" else "No research target selected"))
        if (civ.policies.shouldShowPolicyPicker()) add(Blocker("policy", "A policy choice is pending"))
        if (civ.gameInfo.isEspionageEnabled() && civ.espionageManager.shouldShowMoveSpies()) add(Blocker("espionage", "A spy assignment is pending"))
        when (civ.religionManager.religionState) {
            ReligionState.FoundingReligion -> add(Blocker("religion", "Religion founding choices are pending"))
            ReligionState.EnhancingReligion -> add(Blocker("religion", "Religion enhancement choices are pending"))
            else -> if (civ.religionManager.canFoundOrExpandPantheon()) add(Blocker("religion", "A pantheon choice is pending"))
        }
        if (civ.religionManager.hasFreeBeliefs()) add(Blocker("religion", "Free belief choice(s) are pending"))
        if (civ.mayVoteForDiplomaticVictory()) add(Blocker("worldCongress", "A World Leader vote is pending"))
        if (civ.greatPeople.freeGreatPeople > 0) add(Blocker("greatPerson", "${civ.greatPeople.freeGreatPeople} free great-person choice(s) pending"))
        for (unit in civ.units.getCivUnits().filter { it.promotions.canBePromoted() })
            add(Blocker("promotion", "${unit.name} #${unit.id} can be promoted"))
        if (civ.units.shouldGoToDueUnit()) {
            val due = civ.units.getDueUnits().joinToString { "${it.name} #${it.id}" }
            add(Blocker("unit", "Idle unit orders remain${if (due.isEmpty()) "" else ": $due"}"))
        }
        for (alert in mandatoryAlerts(civ)) add(Blocker(alertCategory(alert), "Unresolved ${alert.type.name} alert: ${alert.value}"))
        for (request in civ.tradeRequests) {
            val requester = civ.gameInfo.getCivilization(request.requestingCiv)
            if (!requester.isDefeated() && TradeEvaluation().isTradeValid(request.trade, civ, requester))
                add(Blocker("trade", "Trade request from ${requester.civName} is awaiting a response"))
        }
    }.distinctBy { it.category to it.message }

    private fun mandatoryAlerts(civ: Civilization): List<PopupAlert> {
        val mandatory = setOf(
            AlertType.CityConquered,
            AlertType.Event,
            AlertType.DemandToStopSettlingCitiesNear,
            AlertType.DemandToStopSpreadingReligion,
            AlertType.DemandToStopSpyingOnUs,
            AlertType.DemandToNotAttackUs
        )
        return civ.popupAlerts.filter { it.type in mandatory }
    }

    private fun alertCategory(alert: PopupAlert): String = when (alert.type) {
        AlertType.CityConquered -> "conquest"
        AlertType.Event -> "event"
        else -> "diplomaticDemand"
    }

    private fun writeOutputs(config: Config, game: GameInfo, results: List<TurnResult>) {
        val out = File(config.outputDir).apply { mkdirs() }
        val currentBlockers = blockers(game.currentPlayerCiv)
        val legal = if (currentBlockers.isEmpty()) listOf("{\"actionId\":\"turn:end\",\"category\":\"turn\",\"label\":\"End turn after all explicit decisions are resolved\"}") else emptyList()
        File(out, "turn-legal-actions.json").writeText(turnArrayJson(legal) + "\n")
        File(out, "turn-blockers.json").writeText("{" + listOf(
            turnJson("turn", game.turns), turnJson("currentPlayer", game.currentPlayer),
            turnJson("canEndTurn", currentBlockers.isEmpty()), turnJson("count", currentBlockers.size),
            "\"blockers\":${turnArrayJson(currentBlockers.map { it.toJson() })}"
        ).joinToString(",") + "}\n")
        File(out, "turn-result.json").writeText("{" + listOf(
            turnJson("turn", game.turns), turnJson("currentPlayer", game.currentPlayer), turnJson("automationUsed", false),
            "\"results\":${turnArrayJson(results.map { it.toJson() })}"
        ).joinToString(",") + "}\n")
    }

    private data class Blocker(val category: String, val message: String) {
        fun toJson() = "{" + listOf(turnJson("category", category), turnJson("message", message), turnJson("hardBlock", true)).joinToString(",") + "}"
    }
    private data class TurnResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson() = "{" + listOf(turnJson("actionId", actionId), turnJson("applied", applied), turnJson("error", error), turnJson("message", message)).joinToString(",") + "}"
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

private fun turnArrayJson(values: Iterable<String>) = values.joinToString(prefix = "[", postfix = "]")
private fun turnJson(name: String, value: String?) = "\"$name\":${if (value == null) "null" else "\"${escapeTurnJson(value)}\""}"
private fun turnJson(name: String, value: Int) = "\"$name\":$value"
private fun turnJson(name: String, value: Boolean) = "\"$name\":$value"
private fun escapeTurnJson(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
private fun unescapeTurnJson(value: String) = value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
