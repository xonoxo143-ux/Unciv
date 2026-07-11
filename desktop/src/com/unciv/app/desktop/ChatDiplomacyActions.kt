package com.unciv.app.desktop

import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Explicit war declarations and incoming trade responses for the chat benchmark. */
internal object ChatDiplomacyActions {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = DiplomacyConfig.fromArgs(args)
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
        removeInvalidTradeRequests(civ)
        val actionIds = config.commandsFile?.let { readActionIds(File(it)) } ?: emptyList()
        val results = actionIds.map { applyAction(gameInfo, civ, it) }
        if (results.none { it.error })
            saveFile.writeText(UncivFiles.gameInfoToString(gameInfo, forceZip = false, updateChecksum = false))
        writeOutputs(config, gameInfo, results)
        println("Diplomacy chat actions complete: actions=${results.size} applied=${results.count { it.applied }} rejected=${results.count { !it.applied }}")
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
            .map { unescapeDiplomacyJson(it.groupValues[1]) }
            .toList()
        if (fromJson.isNotEmpty()) return fromJson
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }

    private fun applyAction(gameInfo: GameInfo, civ: Civilization, actionId: String): DiplomacyResult {
        val legal = legalActions(gameInfo, civ).associateBy { it.actionId }
        val action = legal[actionId]
            ?: return DiplomacyResult(actionId, false, false, "Rejected: unknown or currently illegal diplomacy action")
        return runCatching {
            when (action.category) {
                "declareWar" -> applyDeclareWar(gameInfo, civ, actionId)
                "acceptTrade" -> applyTradeResponse(gameInfo, civ, actionId, accept = true)
                "declineTrade" -> applyTradeResponse(gameInfo, civ, actionId, accept = false)
                else -> DiplomacyOutcome(false, "Rejected: unsupported diplomacy category")
            }
        }.fold(
            onSuccess = { DiplomacyResult(actionId, it.applied, false, it.message) },
            onFailure = { DiplomacyResult(actionId, false, true, "Error while applying diplomacy action: ${it.message ?: it::class.simpleName}") }
        )
    }

    private fun applyDeclareWar(gameInfo: GameInfo, civ: Civilization, actionId: String): DiplomacyOutcome {
        val targetId = actionId.removePrefix("diplomacy:declareWar:").takeIf { actionId.startsWith("diplomacy:declareWar:") }
            ?: return DiplomacyOutcome(false, "Rejected: malformed war declaration")
        val target = gameInfo.getCivilization(targetId)
        val manager = civ.getDiplomacyManager(target)
            ?: return DiplomacyOutcome(false, "Rejected: no diplomacy relationship with $targetId")
        if (!manager.canDeclareWar()) return DiplomacyOutcome(false, "Rejected: war cannot currently be declared on ${target.civName}")
        manager.declareWar()
        return DiplomacyOutcome(true, "Declared war on ${target.civName}")
    }

    private fun applyTradeResponse(gameInfo: GameInfo, civ: Civilization, actionId: String, accept: Boolean): DiplomacyOutcome {
        val parts = actionId.split(":", limit = 4)
        val expected = if (accept) "accept" else "decline"
        if (parts.size != 4 || parts[0] != "trade" || parts[3] != expected)
            return DiplomacyOutcome(false, "Rejected: malformed trade response")
        val requester = parts[1]
        val ordinal = parts[2].toIntOrNull() ?: return DiplomacyOutcome(false, "Rejected: invalid trade request ordinal")
        val requests = civ.tradeRequests.filter { it.requestingCiv == requester }
        val request = requests.getOrNull(ordinal) ?: return DiplomacyOutcome(false, "Rejected: trade request no longer exists")
        val requestingCiv = gameInfo.getCivilization(request.requestingCiv)
        if (!TradeEvaluation().isTradeValid(request.trade, civ, requestingCiv)) {
            civ.tradeRequests.remove(request)
            return DiplomacyOutcome(false, "Rejected: trade request is no longer valid")
        }
        if (accept) {
            val tradeLogic = TradeLogic(civ, requestingCiv)
            tradeLogic.currentTrade.set(request.trade)
            tradeLogic.acceptTrade()
            civ.tradeRequests.remove(request)
            return DiplomacyOutcome(true, "Accepted trade request from ${requestingCiv.civName}")
        }
        request.decline(civ)
        civ.tradeRequests.remove(request)
        return DiplomacyOutcome(true, "Declined trade request from ${requestingCiv.civName}")
    }

    private fun legalActions(gameInfo: GameInfo, civ: Civilization): List<DiplomacyLegalAction> {
        val actions = ArrayList<DiplomacyLegalAction>()
        val relationshipsLocked = gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange)
        if (!relationshipsLocked) {
            for (other in civ.getKnownCivs().filter { it != civ && !it.isDefeated() }.sortedBy { it.civName }) {
                val manager = civ.getDiplomacyManager(other) ?: continue
                if (manager.canDeclareWar()) {
                    actions += DiplomacyLegalAction(
                        "diplomacy:declareWar:${other.civID}",
                        "declareWar",
                        "Declare war on ${other.civName}"
                    )
                }
            }
        }
        val byRequester = civ.tradeRequests.groupBy { it.requestingCiv }
        for ((requester, requests) in byRequester.toSortedMap()) {
            val requestingCiv = gameInfo.getCivilization(requester)
            for ((ordinal, request) in requests.withIndex()) {
                if (!TradeEvaluation().isTradeValid(request.trade, civ, requestingCiv)) continue
                val summary = tradeSummary(request)
                actions += DiplomacyLegalAction(
                    "trade:$requester:$ordinal:accept",
                    "acceptTrade",
                    "Accept trade from ${requestingCiv.civName}: $summary"
                )
                actions += DiplomacyLegalAction(
                    "trade:$requester:$ordinal:decline",
                    "declineTrade",
                    "Decline trade from ${requestingCiv.civName}: $summary"
                )
            }
        }
        return actions
    }

    private fun removeInvalidTradeRequests(civ: Civilization) {
        for (request in civ.tradeRequests.toList()) {
            val requester = civ.gameInfo.getCivilization(request.requestingCiv)
            if (requester.isDefeated() || !TradeEvaluation().isTradeValid(request.trade, civ, requester))
                civ.tradeRequests.remove(request)
        }
    }

    private fun tradeSummary(request: TradeRequest): String {
        fun offers(values: Iterable<TradeOffer>) = values.joinToString("; ") { offer ->
            "${offer.amount} ${offer.name} (${offer.type.name})"
        }.ifEmpty { "nothing" }
        return "they offer ${offers(request.trade.theirOffers)}; they request ${offers(request.trade.ourOffers)}"
    }

    private fun writeOutputs(config: DiplomacyConfig, gameInfo: GameInfo, results: List<DiplomacyResult>) {
        val civ = gameInfo.currentPlayerCiv
        val outputDir = File(config.outputDir).apply { mkdirs() }
        val legal = legalActions(gameInfo, civ)
        val relations = civ.getKnownCivs().filter { it != civ }.sortedBy { it.civName }.map { other ->
            val manager = civ.getDiplomacyManager(other)
            "{" + listOf(
                diplomacyJson("civ", other.civName),
                diplomacyJson("civId", other.civID),
                diplomacyJson("status", manager?.diplomaticStatus?.name),
                diplomacyJson("atWar", civ.isAtWarWith(other)),
                diplomacyJson("canDeclareWar", manager?.canDeclareWar() == true)
            ).joinToString(",") + "}"
        }.toList()
        File(outputDir, "diplomacy-legal-actions.json").writeText(diplomacyArrayJson(legal.map { it.toJson() }) + "\n")
        File(outputDir, "diplomacy-result.json").writeText(
            "{" + listOf(
                diplomacyJson("turn", gameInfo.turns),
                diplomacyJson("pendingTradeRequests", civ.tradeRequests.size),
                diplomacyJson("automationUsed", false),
                "\"relations\":${diplomacyArrayJson(relations)}",
                "\"results\":${diplomacyArrayJson(results.map { it.toJson() })}"
            ).joinToString(",") + "}\n"
        )
    }

    private data class DiplomacyOutcome(val applied: Boolean, val message: String)

    private data class DiplomacyLegalAction(val actionId: String, val category: String, val label: String) {
        fun toJson(): String = "{" + listOf(
            diplomacyJson("actionId", actionId),
            diplomacyJson("category", category),
            diplomacyJson("label", label)
        ).joinToString(",") + "}"
    }

    private data class DiplomacyResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson(): String = "{" + listOf(
            diplomacyJson("actionId", actionId),
            diplomacyJson("applied", applied),
            diplomacyJson("error", error),
            diplomacyJson("message", message)
        ).joinToString(",") + "}"
    }

    private data class DiplomacyConfig(val saveFile: String, val outputDir: String, val commandsFile: String?) {
        companion object {
            fun fromArgs(args: Array<String>): DiplomacyConfig {
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
                return DiplomacyConfig(
                    saveFile = values["save-file"] ?: "$output/chat-player-save.json",
                    outputDir = output,
                    commandsFile = values["commands"]
                )
            }
        }
    }
}

private fun diplomacyArrayJson(values: Iterable<String>): String = values.joinToString(prefix = "[", postfix = "]")
private fun diplomacyJson(name: String, value: String?): String = "\"$name\":${if (value == null) "null" else "\"${escapeDiplomacyJson(value)}\""}"
private fun diplomacyJson(name: String, value: Int): String = "\"$name\":$value"
private fun diplomacyJson(name: String, value: Boolean): String = "\"$name\":$value"
private fun escapeDiplomacyJson(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
private fun unescapeDiplomacyJson(value: String): String = value
    .replace("\\n", "\n")
    .replace("\\\"", "\"")
    .replace("\\\\", "\\")
