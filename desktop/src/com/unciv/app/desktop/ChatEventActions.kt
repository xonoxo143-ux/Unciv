package com.unciv.app.desktop

import com.unciv.Constants
import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.EventChoice
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Explicit choices for ruleset events awaiting a player response. */
internal object ChatEventActions {
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
        println("Event choices complete: actions=${results.size} applied=${results.count { it.applied }} pending=${pendingEvents(game, civ).size}")
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
            .findAll(text).map { unescapeEventJson(it.groupValues[1]) }.toList()
        return ids.ifEmpty { text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList() }
    }

    private fun apply(game: GameInfo, civ: Civilization, actionId: String): EventResult {
        legalActions(game, civ).associateBy { it.actionId }[actionId]
            ?: return EventResult(actionId, false, false, "Rejected: unknown or currently illegal event choice")
        val parts = actionId.split(":", limit = 4)
        if (parts.size != 4 || parts[0] != "event" || parts[2] != "choose")
            return EventResult(actionId, false, false, "Rejected: malformed event choice")
        val alertIndex = parts[1].toIntOrNull() ?: return EventResult(actionId, false, false, "Rejected: invalid event alert index")
        val choiceIndex = parts[3].toIntOrNull() ?: return EventResult(actionId, false, false, "Rejected: invalid event choice index")
        val pending = pendingEvents(game, civ)
        val pendingEvent = pending.getOrNull(alertIndex)
            ?: return EventResult(actionId, false, false, "Rejected: event alert no longer exists")
        val choice = pendingEvent.choices.getOrNull(choiceIndex)
            ?: return EventResult(actionId, false, false, "Rejected: event choice no longer exists")
        return runCatching {
            val success = choice.triggerChoice(civ, pendingEvent.unit)
            civ.popupAlerts.remove(pendingEvent.alert)
            EventResult(
                actionId, true, false,
                "Selected '${choice.text}' for event ${pendingEvent.event.name}; triggerReportedSuccess=$success; alertRemoved=true"
            )
        }.getOrElse { EventResult(actionId, false, true, "Error while applying event choice: ${it.message ?: it::class.simpleName}") }
    }

    private fun legalActions(game: GameInfo, civ: Civilization): List<LegalEventAction> = buildList {
        for ((alertIndex, pending) in pendingEvents(game, civ).withIndex()) {
            for ((choiceIndex, choice) in pending.choices.withIndex()) add(LegalEventAction(
                "event:$alertIndex:choose:$choiceIndex", "eventChoice",
                "${pending.event.name}: ${choice.text}", pending.event.name, choice.text, pending.unit?.id
            ))
        }
    }

    private fun pendingEvents(game: GameInfo, civ: Civilization): List<PendingEvent> = buildList {
        for (alert in civ.popupAlerts.filter { it.type == AlertType.Event }) {
            val context = parseContext(civ, alert.value)
            val event = game.ruleset.events[context.eventName] ?: continue
            val choices = event.getMatchingChoices(GameContext(civ, unit = context.unit))?.toList() ?: continue
            if (choices.isEmpty()) continue
            add(PendingEvent(alert, event, context.unit, choices))
        }
    }

    private fun parseContext(civ: Civilization, value: String): EventContext {
        val split = value.split(Constants.stringSplitCharacter)
        var unit: MapUnit? = null
        for (part in split.drop(1)) {
            if (part.startsWith("unitId=")) unit = part.substringAfter("unitId=").toIntOrNull()?.let { civ.units.getUnitById(it) }
        }
        return EventContext(split.firstOrNull().orEmpty(), unit)
    }

    private fun writeOutputs(config: Config, game: GameInfo, results: List<EventResult>) {
        val out = File(config.outputDir).apply { mkdirs() }
        val pending = pendingEvents(game, game.currentPlayerCiv)
        File(out, "event-legal-actions.json").writeText(eventArrayJson(legalActions(game, game.currentPlayerCiv).map { it.toJson() }) + "\n")
        File(out, "event-result.json").writeText("{" + listOf(
            eventJson("turn", game.turns), eventJson("pendingEvents", pending.size), eventJson("automationUsed", false),
            "\"results\":${eventArrayJson(results.map { it.toJson() })}"
        ).joinToString(",") + "}\n")
    }

    private data class EventContext(val eventName: String, val unit: MapUnit?)
    private data class PendingEvent(val alert: PopupAlert, val event: Event, val unit: MapUnit?, val choices: List<EventChoice>)
    private data class LegalEventAction(val actionId: String, val category: String, val label: String, val event: String, val choice: String, val unitId: Int?) {
        fun toJson() = "{" + listOf(
            eventJson("actionId", actionId), eventJson("category", category), eventJson("label", label),
            eventJson("event", event), eventJson("choice", choice), eventJson("unitId", unitId)
        ).joinToString(",") + "}"
    }
    private data class EventResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson() = "{" + listOf(eventJson("actionId", actionId), eventJson("applied", applied), eventJson("error", error), eventJson("message", message)).joinToString(",") + "}"
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

private fun eventArrayJson(values: Iterable<String>) = values.joinToString(prefix = "[", postfix = "]")
private fun eventJson(name: String, value: String?) = "\"$name\":${if (value == null) "null" else "\"${escapeEventJson(value)}\""}"
private fun eventJson(name: String, value: Int?) = "\"$name\":${value ?: "null"}"
private fun eventJson(name: String, value: Boolean) = "\"$name\":$value"
private fun escapeEventJson(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
private fun unescapeEventJson(value: String) = value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
