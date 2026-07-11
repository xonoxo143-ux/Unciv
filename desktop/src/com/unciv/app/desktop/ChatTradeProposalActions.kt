package com.unciv.app.desktop

import com.unciv.Constants
import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Consent-preserving outbound diplomatic proposals using currently available symmetric offers. */
internal object ChatTradeProposalActions {
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
        println("Trade proposals complete: actions=${results.size} applied=${results.count { it.applied }}")
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
            .findAll(text).map { unescapeProposalJson(it.groupValues[1]) }.toList()
        return ids.ifEmpty { text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList() }
    }

    private fun apply(game: GameInfo, civ: Civilization, actionId: String): ProposalResult {
        val legal = legalActions(civ).associateBy { it.actionId }[actionId]
            ?: return ProposalResult(actionId, false, false, "Rejected: unknown or currently illegal trade proposal action")
        return runCatching {
            when (legal.category) {
                "propose" -> {
                    val parts = actionId.split(":", limit = 4)
                    val target = game.getCivilization(parts[2])
                    val template = parts[3]
                    val trade = buildTemplate(civ, target, template)
                        ?: return@runCatching ProposalOutcome(false, "Rejected: $template is no longer available with ${target.civName}")
                    val requestForTarget = trade.reverse()
                    val duplicate = target.tradeRequests.any { it.requestingCiv == civ.civID && it.trade.equalTrade(requestForTarget) }
                    if (duplicate) return@runCatching ProposalOutcome(false, "Rejected: identical proposal is already pending")
                    target.tradeRequests.add(TradeRequest(civ.civID, requestForTarget))
                    civ.cache.updateCivResources()
                    ProposalOutcome(true, "Sent ${templateLabel(template)} proposal to ${target.civName}; opponent consent is required")
                }
                "retract" -> {
                    val parts = actionId.split(":", limit = 4)
                    val target = game.getCivilization(parts[2])
                    val ordinal = parts[3].toIntOrNull() ?: return@runCatching ProposalOutcome(false, "Rejected: invalid proposal ordinal")
                    val outgoing = target.tradeRequests.filter { it.requestingCiv == civ.civID }
                    val request = outgoing.getOrNull(ordinal) ?: return@runCatching ProposalOutcome(false, "Rejected: proposal no longer exists")
                    target.tradeRequests.remove(request)
                    ProposalOutcome(true, "Retracted pending proposal #$ordinal to ${target.civName}")
                }
                else -> ProposalOutcome(false, "Rejected: unsupported proposal category")
            }
        }.fold(
            onSuccess = { ProposalResult(actionId, it.applied, false, it.message) },
            onFailure = { ProposalResult(actionId, false, true, "Error while managing trade proposal: ${it.message ?: it::class.simpleName}") }
        )
    }

    private fun legalActions(civ: Civilization): List<LegalProposalAction> = buildList {
        for (target in civ.getKnownCivs().filter { it != civ && it.isMajorCiv() && !it.isDefeated() }.sortedBy { it.civName }) {
            for (template in listOf("peace", "embassies", "openBorders", "researchAgreement", "defensivePact")) {
                val trade = buildTemplate(civ, target, template) ?: continue
                val duplicate = target.tradeRequests.any { it.requestingCiv == civ.civID && it.trade.equalTrade(trade.reverse()) }
                if (!duplicate) add(LegalProposalAction(
                    "trade:propose:${target.civID}:$template", "propose", "Propose ${templateLabel(template)} to ${target.civName}", target.civID
                ))
            }
            for ((ordinal, request) in target.tradeRequests.filter { it.requestingCiv == civ.civID }.withIndex()) {
                add(LegalProposalAction(
                    "trade:retract:${target.civID}:$ordinal", "retract", "Retract proposal #$ordinal to ${target.civName}: ${summary(request.trade)}", target.civID
                ))
            }
        }
    }

    private fun buildTemplate(civ: Civilization, target: Civilization, template: String): Trade? {
        val logic = TradeLogic(civ, target)
        val pair = when (template) {
            "peace" -> symmetricOffer(logic, Constants.peaceTreaty, TradeOfferType.Treaty)
            "embassies" -> symmetricOffer(logic, Constants.acceptEmbassy, TradeOfferType.Embassy)
            "openBorders" -> symmetricOffer(logic, Constants.openBorders, TradeOfferType.Agreement)
            "researchAgreement" -> {
                val pair = symmetricOffer(logic, Constants.researchAgreement, TradeOfferType.Treaty) ?: return null
                val cost = pair.first.amount
                if (civ.gold < cost || target.gold < pair.second.amount) return null
                pair
            }
            "defensivePact" -> symmetricOffer(logic, Constants.defensivePact, TradeOfferType.Treaty)
            else -> null
        } ?: return null
        return Trade().apply { ourOffers.add(pair.first.copy()); theirOffers.add(pair.second.copy()) }
    }

    private fun symmetricOffer(logic: TradeLogic, name: String, type: TradeOfferType): Pair<TradeOffer, TradeOffer>? {
        val ours = logic.ourAvailableOffers.firstOrNull { it.name == name && it.type == type && it.isTradable() } ?: return null
        val theirs = logic.theirAvailableOffers.firstOrNull { it.name == name && it.type == type && it.isTradable() } ?: return null
        return ours to theirs
    }

    private fun templateLabel(template: String) = when (template) {
        "peace" -> "a mutual peace treaty"
        "embassies" -> "an exchange of embassies"
        "openBorders" -> "mutual open borders"
        "researchAgreement" -> "a research agreement"
        "defensivePact" -> "a defensive pact"
        else -> template
    }

    private fun summary(trade: Trade): String {
        fun offers(values: Iterable<TradeOffer>) = values.joinToString("; ") { "${it.amount} ${it.name}" }.ifEmpty { "nothing" }
        return "we offered ${offers(trade.theirOffers)} / requested ${offers(trade.ourOffers)}"
    }

    private fun writeOutputs(config: Config, game: GameInfo, results: List<ProposalResult>) {
        val civ = game.currentPlayerCiv
        val out = File(config.outputDir).apply { mkdirs() }
        File(out, "trade-proposal-legal-actions.json").writeText(proposalArrayJson(legalActions(civ).map { it.toJson() }) + "\n")
        File(out, "trade-proposal-result.json").writeText("{" + listOf(
            proposalJson("turn", game.turns), proposalJson("automationUsed", false), proposalJson("opponentConsentRequired", true),
            "\"results\":${proposalArrayJson(results.map { it.toJson() })}"
        ).joinToString(",") + "}\n")
    }

    private data class ProposalOutcome(val applied: Boolean, val message: String)
    private data class LegalProposalAction(val actionId: String, val category: String, val label: String, val targetCivId: String) {
        fun toJson() = "{" + listOf(proposalJson("actionId", actionId), proposalJson("category", category), proposalJson("label", label), proposalJson("targetCivId", targetCivId)).joinToString(",") + "}"
    }
    private data class ProposalResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson() = "{" + listOf(proposalJson("actionId", actionId), proposalJson("applied", applied), proposalJson("error", error), proposalJson("message", message)).joinToString(",") + "}"
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

private fun proposalArrayJson(values: Iterable<String>) = values.joinToString(prefix = "[", postfix = "]")
private fun proposalJson(name: String, value: String?) = "\"$name\":${if (value == null) "null" else "\"${escapeProposalJson(value)}\""}"
private fun proposalJson(name: String, value: Int) = "\"$name\":$value"
private fun proposalJson(name: String, value: Boolean) = "\"$name\":$value"
private fun escapeProposalJson(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
private fun unescapeProposalJson(value: String) = value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
