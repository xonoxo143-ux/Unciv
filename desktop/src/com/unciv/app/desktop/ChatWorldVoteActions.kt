package com.unciv.app.desktop

import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Explicit World Leader vote or abstention. */
internal object ChatWorldVoteActions {
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
        println("World vote actions complete: actions=${results.size} applied=${results.count { it.applied }} mayVote=${civ.mayVoteForDiplomaticVictory()}")
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
            .findAll(text).map { unescapeVoteJson(it.groupValues[1]) }.toList()
        return ids.ifEmpty { text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList() }
    }

    private fun apply(civ: Civilization, actionId: String): VoteResult {
        val legal = legalActions(civ).associateBy { it.actionId }[actionId]
            ?: return VoteResult(actionId, false, false, "Rejected: unknown or currently illegal World Leader vote")
        if (!civ.mayVoteForDiplomaticVictory()) return VoteResult(actionId, false, false, "Rejected: this civilization may no longer vote")
        return runCatching {
            val chosen = if (legal.category == "abstain") null else legal.targetCivId
            civ.diplomaticVoteForCiv(chosen)
            VoteResult(actionId, true, false, if (chosen == null) "Abstained from the World Leader vote" else "Voted for ${civ.gameInfo.getCivilization(chosen).civName} as World Leader")
        }.getOrElse { VoteResult(actionId, false, true, "Error while casting World Leader vote: ${it.message ?: it::class.simpleName}") }
    }

    private fun legalActions(civ: Civilization): List<LegalVoteAction> = buildList {
        if (!civ.mayVoteForDiplomaticVictory()) return@buildList
        for (candidate in civ.diplomacyFunctions.getKnownCivsSorted(false)) {
            if (!candidate.isMajorCiv() || candidate.isDefeated()) continue
            add(LegalVoteAction("vote:worldLeader:${candidate.civID}", "vote", "Vote for ${candidate.civName} as World Leader", candidate.civID))
        }
        add(LegalVoteAction("vote:worldLeader:abstain", "abstain", "Abstain from the World Leader vote", null))
    }

    private fun writeOutputs(config: Config, game: GameInfo, results: List<VoteResult>) {
        val civ = game.currentPlayerCiv
        val out = File(config.outputDir).apply { mkdirs() }
        File(out, "world-vote-legal-actions.json").writeText(voteArrayJson(legalActions(civ).map { it.toJson() }) + "\n")
        File(out, "world-vote-result.json").writeText("{" + listOf(
            voteJson("turn", game.turns), voteJson("mayVote", civ.mayVoteForDiplomaticVictory()),
            voteJson("voteCastFor", game.diplomaticVictoryVotesCast[civ.civID]), voteJson("automationUsed", false),
            "\"results\":${voteArrayJson(results.map { it.toJson() })}"
        ).joinToString(",") + "}\n")
    }

    private data class LegalVoteAction(val actionId: String, val category: String, val label: String, val targetCivId: String?) {
        fun toJson() = "{" + listOf(voteJson("actionId", actionId), voteJson("category", category), voteJson("label", label), voteJson("targetCivId", targetCivId)).joinToString(",") + "}"
    }
    private data class VoteResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson() = "{" + listOf(voteJson("actionId", actionId), voteJson("applied", applied), voteJson("error", error), voteJson("message", message)).joinToString(",") + "}"
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

private fun voteArrayJson(values: Iterable<String>) = values.joinToString(prefix = "[", postfix = "]")
private fun voteJson(name: String, value: String?) = "\"$name\":${if (value == null) "null" else "\"${escapeVoteJson(value)}\""}"
private fun voteJson(name: String, value: Int) = "\"$name\":$value"
private fun voteJson(name: String, value: Boolean) = "\"$name\":$value"
private fun escapeVoteJson(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
private fun unescapeVoteJson(value: String) = value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
