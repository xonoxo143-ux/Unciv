package com.unciv.app.desktop

import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.models.Spy
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Explicit spy assignments; no AI spy automation. */
internal object ChatEspionageActions {
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
        println("Espionage actions complete: actions=${results.size} applied=${results.count { it.applied }} idle=${civ.espionageManager.getIdleSpies().size}")
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
            .findAll(text).map { unescapeSpyJson(it.groupValues[1]) }.toList()
        return ids.ifEmpty { text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList() }
    }

    private fun apply(game: GameInfo, civ: Civilization, actionId: String): SpyResult {
        val legal = legalActions(game, civ).associateBy { it.actionId }[actionId]
            ?: return SpyResult(actionId, false, false, "Rejected: unknown or currently illegal espionage action")
        return runCatching {
            when (legal.category) {
                "assign" -> {
                    val parts = actionId.split(":", limit = 4)
                    val spyIndex = parts.getOrNull(1)?.toIntOrNull()
                        ?: return@runCatching SpyOutcome(false, "Rejected: invalid spy index")
                    val spy = civ.espionageManager.spyList.getOrNull(spyIndex)
                        ?: return@runCatching SpyOutcome(false, "Rejected: spy no longer exists")
                    val city = game.getCities().firstOrNull { it.id == parts.getOrNull(3) }
                        ?: return@runCatching SpyOutcome(false, "Rejected: city no longer exists")
                    if (!spy.canMoveTo(city)) return@runCatching SpyOutcome(false, "Rejected: ${spy.name} can no longer move to ${city.name}")
                    val old = spy.getLocationName()
                    spy.moveTo(city)
                    civ.espionageManager.dismissedShouldMoveSpies = false
                    SpyOutcome(true, "Assigned ${spy.name} from $old to ${city.name} (${city.civ.civName}); action=${spy.action}; turns=${spy.turnsRemainingForAction}")
                }
                "hideout" -> {
                    val parts = actionId.split(":", limit = 3)
                    val spy = parts.getOrNull(1)?.toIntOrNull()?.let { civ.espionageManager.spyList.getOrNull(it) }
                        ?: return@runCatching SpyOutcome(false, "Rejected: spy no longer exists")
                    val old = spy.getLocationName()
                    spy.moveTo(null)
                    SpyOutcome(true, "Recalled ${spy.name} from $old to the hideout")
                }
                "dismiss" -> {
                    civ.espionageManager.dismissedShouldMoveSpies = true
                    SpyOutcome(true, "Explicitly left ${civ.espionageManager.getIdleSpies().size} spy/spies idle for this turn")
                }
                else -> SpyOutcome(false, "Rejected: unsupported espionage category")
            }
        }.fold(
            onSuccess = { SpyResult(actionId, it.applied, false, it.message) },
            onFailure = { SpyResult(actionId, false, true, "Error while applying espionage action: ${it.message ?: it::class.simpleName}") }
        )
    }

    private fun legalActions(game: GameInfo, civ: Civilization): List<LegalSpyAction> = buildList {
        if (!game.isEspionageEnabled()) return@buildList
        val manager = civ.espionageManager
        for ((index, spy) in manager.spyList.withIndex()) {
            if (!spy.isAlive()) continue
            for (city in game.getCities().filter { spy.canMoveTo(it) }.sortedWith(compareBy<City> { it.civ.civName }.thenBy { it.name })) {
                if (spy.getCityOrNull() == city) continue
                add(LegalSpyAction("spy:$index:move:${city.id}", "assign", "Move ${spy.name} to ${city.name} (${city.civ.civName})"))
            }
            if (spy.getCityOrNull() != null) add(LegalSpyAction("spy:$index:hideout", "hideout", "Recall ${spy.name} to the hideout"))
        }
        if (manager.shouldShowMoveSpies()) add(LegalSpyAction("spy:dismiss", "dismiss", "Leave all remaining idle spies in the hideout this turn"))
    }

    private fun writeOutputs(config: Config, game: GameInfo, results: List<SpyResult>) {
        val civ = game.currentPlayerCiv
        val out = File(config.outputDir).apply { mkdirs() }
        val spies = civ.espionageManager.spyList.mapIndexed { index, spy -> spyJson(index, spy) }
        File(out, "espionage-legal-actions.json").writeText(spyArrayJson(legalActions(game, civ).map { it.toJson() }) + "\n")
        File(out, "espionage-result.json").writeText("{" + listOf(
            spyJsonValue("turn", game.turns), spyJsonValue("idleSpies", civ.espionageManager.getIdleSpies().size),
            spyJsonValue("dismissedShouldMoveSpies", civ.espionageManager.dismissedShouldMoveSpies), spyJsonValue("automationUsed", false),
            "\"spies\":${spyArrayJson(spies)}", "\"results\":${spyArrayJson(results.map { it.toJson() })}"
        ).joinToString(",") + "}\n")
    }

    private fun spyJson(index: Int, spy: Spy) = "{" + listOf(
        spyJsonValue("index", index), spyJsonValue("name", spy.name), spyJsonValue("rank", spy.rank),
        spyJsonValue("location", spy.getLocationName()), spyJsonValue("action", spy.action.name),
        spyJsonValue("turnsRemaining", spy.turnsRemainingForAction), spyJsonValue("idle", spy.isIdle()), spyJsonValue("alive", spy.isAlive())
    ).joinToString(",") + "}"

    private data class SpyOutcome(val applied: Boolean, val message: String)
    private data class LegalSpyAction(val actionId: String, val category: String, val label: String) {
        fun toJson() = "{" + listOf(spyJsonValue("actionId", actionId), spyJsonValue("category", category), spyJsonValue("label", label)).joinToString(",") + "}"
    }
    private data class SpyResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson() = "{" + listOf(spyJsonValue("actionId", actionId), spyJsonValue("applied", applied), spyJsonValue("error", error), spyJsonValue("message", message)).joinToString(",") + "}"
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

private fun spyArrayJson(values: Iterable<String>) = values.joinToString(prefix = "[", postfix = "]")
private fun spyJsonValue(name: String, value: String?) = "\"$name\":${if (value == null) "null" else "\"${escapeSpyJson(value)}\""}"
private fun spyJsonValue(name: String, value: Int) = "\"$name\":$value"
private fun spyJsonValue(name: String, value: Boolean) = "\"$name\":$value"
private fun escapeSpyJson(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
private fun unescapeSpyJson(value: String) = value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
