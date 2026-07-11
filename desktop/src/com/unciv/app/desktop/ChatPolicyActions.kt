package com.unciv.app.desktop

import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Explicit policy adoption for the headless chat benchmark. */
internal object ChatPolicyActions {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = PolicyConfig.fromArgs(args)
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
        println("Policy chat actions complete: actions=${results.size} applied=${results.count { it.applied }} rejected=${results.count { !it.applied }}")
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
            .map { unescapePolicyJson(it.groupValues[1]) }
            .toList()
        if (fromJson.isNotEmpty()) return fromJson
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }

    private fun applyAction(civ: Civilization, actionId: String): PolicyResult {
        val policyName = actionId.removePrefix("policy:adopt:").takeIf { actionId.startsWith("policy:adopt:") }
            ?: return PolicyResult(actionId, false, false, "Rejected: malformed policy action")
        val policy = legalPolicies(civ).firstOrNull { it.name == policyName }
            ?: return PolicyResult(actionId, false, false, "Rejected: $policyName is not currently adoptable")
        return runCatching {
            val cultureBefore = civ.policies.storedCulture
            val freeBefore = civ.policies.freePolicies
            civ.policies.adopt(policy)
            PolicyResult(
                actionId,
                true,
                false,
                "Adopted ${policy.name}; culture $cultureBefore -> ${civ.policies.storedCulture}; freePolicies $freeBefore -> ${civ.policies.freePolicies}"
            )
        }.getOrElse {
            PolicyResult(actionId, false, true, "Error while adopting $policyName: ${it.message ?: it::class.simpleName}")
        }
    }

    private fun legalPolicies(civ: Civilization): List<Policy> {
        if (!civ.policies.canAdoptPolicy()) return emptyList()
        return civ.gameInfo.ruleset.policies.values.asSequence()
            .filter { civ.policies.isAdoptable(it) }
            .sortedBy { it.name }
            .toList()
    }

    private fun writeOutputs(config: PolicyConfig, gameInfo: GameInfo, results: List<PolicyResult>) {
        val civ = gameInfo.currentPlayerCiv
        val outputDir = File(config.outputDir).apply { mkdirs() }
        val legal = legalPolicies(civ).map { policy ->
            "{" + listOf(
                policyJson("actionId", "policy:adopt:${policy.name}"),
                policyJson("category", "policy"),
                policyJson("label", "Adopt ${policy.name}"),
                policyJson("branch", policy.branch.name)
            ).joinToString(",") + "}"
        }
        File(outputDir, "policy-legal-actions.json").writeText(policyArrayJson(legal) + "\n")
        File(outputDir, "policy-result.json").writeText(
            "{" + listOf(
                policyJson("turn", gameInfo.turns),
                policyJson("storedCulture", civ.policies.storedCulture),
                policyJson("freePolicies", civ.policies.freePolicies),
                policyJson("cultureNeededForNextPolicy", civ.policies.getCultureNeededForNextPolicy()),
                policyJson("canAdoptPolicy", civ.policies.canAdoptPolicy()),
                policyJson("automationUsed", false),
                "\"adoptedPolicies\":${policyStringArrayJson(civ.policies.getAdoptedPolicies().sorted())}",
                "\"results\":${policyArrayJson(results.map { it.toJson() })}"
            ).joinToString(",") + "}\n"
        )
    }

    private data class PolicyResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson(): String = "{" + listOf(
            policyJson("actionId", actionId),
            policyJson("applied", applied),
            policyJson("error", error),
            policyJson("message", message)
        ).joinToString(",") + "}"
    }

    private data class PolicyConfig(val saveFile: String, val outputDir: String, val commandsFile: String?) {
        companion object {
            fun fromArgs(args: Array<String>): PolicyConfig {
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
                return PolicyConfig(
                    saveFile = values["save-file"] ?: "$output/chat-player-save.json",
                    outputDir = output,
                    commandsFile = values["commands"]
                )
            }
        }
    }
}

private fun policyArrayJson(values: Iterable<String>): String = values.joinToString(prefix = "[", postfix = "]")
private fun policyStringArrayJson(values: Iterable<String>): String = policyArrayJson(values.map { "\"${escapePolicyJson(it)}\"" })
private fun policyJson(name: String, value: String?): String = "\"$name\":${if (value == null) "null" else "\"${escapePolicyJson(value)}\""}"
private fun policyJson(name: String, value: Int): String = "\"$name\":$value"
private fun policyJson(name: String, value: Boolean): String = "\"$name\":$value"
private fun escapePolicyJson(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
private fun unescapePolicyJson(value: String): String = value
    .replace("\\n", "\n")
    .replace("\\\"", "\"")
    .replace("\\\\", "\\")
