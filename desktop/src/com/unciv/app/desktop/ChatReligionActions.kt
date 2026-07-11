package com.unciv.app.desktop

import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionManager
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.civilization.managers.foundReligionHeadless
import com.unciv.logic.files.UncivFiles
import com.unciv.models.Counter
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Explicit pantheon, religion-founding, enhancement, and free-belief choices. */
internal object ChatReligionActions {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = ReligionConfig.fromArgs(args)
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
        println("Religion chat actions complete: state=${civ.religionManager.religionState} actions=${results.size} applied=${results.count { it.applied }} rejected=${results.count { !it.applied }}")
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
            .map { unescapeReligionJson(it.groupValues[1]) }
            .toList()
        if (fromJson.isNotEmpty()) return fromJson
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }

    private fun applyAction(civ: Civilization, actionId: String): ReligionResult {
        val manager = civ.religionManager
        return runCatching {
            when {
                actionId.startsWith("religion:pantheon:") -> {
                    val beliefName = actionId.removePrefix("religion:pantheon:")
                    applyBeliefChoice(civ, listOf(beliefName), expectedState = ReligionState.None)
                }
                actionId.startsWith("religion:found:") -> applyFounding(civ, actionId)
                actionId.startsWith("religion:enhance:") -> {
                    val names = parseBeliefList(actionId.removePrefix("religion:enhance:"))
                    applyBeliefChoice(civ, names, expectedState = ReligionState.EnhancingReligion)
                }
                actionId.startsWith("religion:addBeliefs:") -> {
                    val names = parseBeliefList(actionId.removePrefix("religion:addBeliefs:"))
                    if (!manager.hasFreeBeliefs()) ReligionOutcome(false, "Rejected: no free belief choices are pending")
                    else applyBeliefChoice(civ, names, expectedState = manager.religionState)
                }
                else -> ReligionOutcome(false, "Rejected: malformed religion action")
            }
        }.fold(
            onSuccess = { ReligionResult(actionId, it.applied, false, it.message) },
            onFailure = { ReligionResult(actionId, false, true, "Error while applying religion action: ${it.message ?: it::class.simpleName}") }
        )
    }

    private fun applyFounding(civ: Civilization, actionId: String): ReligionOutcome {
        val parts = actionId.split(":", limit = 5)
        if (parts.size != 5 || parts[0] != "religion" || parts[1] != "found")
            return ReligionOutcome(false, "Rejected: malformed religion founding action")
        val symbol = parts[2]
        val displayName = parts[3]
        val beliefNames = parseBeliefList(parts[4])
        val manager = civ.religionManager
        if (manager.religionState != ReligionState.FoundingReligion)
            return ReligionOutcome(false, "Rejected: civilization is not currently founding a religion")
        if (displayName.isBlank()) return ReligionOutcome(false, "Rejected: religion display name is blank")
        if (symbol !in availableReligionSymbols(civ))
            return ReligionOutcome(false, "Rejected: religion symbol $symbol is unavailable")
        val beliefs = validateBeliefSelection(civ, beliefNames, manager.getBeliefsToChooseAtFounding())
            ?: return ReligionOutcome(false, "Rejected: belief set does not match the required founding slots or contains an unavailable belief")
        if (!manager.foundReligionHeadless(displayName, symbol))
            return ReligionOutcome(false, "Rejected: religion founding transition was not valid")
        manager.chooseBeliefs(beliefs, manager.usingFreeBeliefs())
        return ReligionOutcome(true, "Founded $displayName using $symbol with beliefs ${beliefs.joinToString { it.name }}")
    }

    private fun applyBeliefChoice(civ: Civilization, beliefNames: List<String>, expectedState: ReligionState): ReligionOutcome {
        val manager = civ.religionManager
        if (manager.religionState != expectedState)
            return ReligionOutcome(false, "Rejected: religion state is ${manager.religionState}, expected $expectedState")
        val required = requiredBeliefs(manager)
        val beliefs = validateBeliefSelection(civ, beliefNames, required)
            ?: return ReligionOutcome(false, "Rejected: belief set does not match required slots or contains an unavailable belief")
        val faithBefore = manager.storedFaith
        manager.chooseBeliefs(beliefs, manager.usingFreeBeliefs())
        return ReligionOutcome(
            true,
            "Selected beliefs ${beliefs.joinToString { it.name }}; religionState=${manager.religionState}; faith $faithBefore -> ${manager.storedFaith}"
        )
    }

    private fun requiredBeliefs(manager: ReligionManager): Counter<BeliefType> = when (manager.religionState) {
        ReligionState.None -> Counter<BeliefType>().apply {
            if (manager.canFoundOrExpandPantheon()) add(BeliefType.Pantheon, 1)
        }
        ReligionState.FoundingReligion -> manager.getBeliefsToChooseAtFounding()
        ReligionState.EnhancingReligion -> manager.getBeliefsToChooseAtEnhancing()
        ReligionState.Pantheon, ReligionState.Religion, ReligionState.EnhancedReligion ->
            if (manager.hasFreeBeliefs()) manager.freeBeliefsAsEnums() else Counter()
    }

    private fun validateBeliefSelection(
        civ: Civilization,
        beliefNames: List<String>,
        required: Counter<BeliefType>
    ): List<Belief>? {
        val requiredCount = required.sumValues()
        if (requiredCount <= 0 || beliefNames.size != requiredCount || beliefNames.distinct().size != beliefNames.size)
            return null
        val beliefs = beliefNames.map { civ.gameInfo.ruleset.beliefs[it] ?: return null }
        if (beliefs.any { !isBeliefAvailable(civ, it) }) return null

        val remaining = beliefs.toMutableList()
        for ((type, count) in required.entries.sortedBy { if (it.key == BeliefType.Any) 1 else 0 }) {
            repeat(count) {
                val index = if (type == BeliefType.Any) 0 else remaining.indexOfFirst { it.type == type }
                if (index !in remaining.indices) return null
                remaining.removeAt(index)
            }
        }
        return if (remaining.isEmpty()) beliefs else null
    }

    private fun isBeliefAvailable(civ: Civilization, belief: Belief): Boolean {
        val manager = civ.religionManager
        val currentReligion = manager.religion
        if (currentReligion?.hasBelief(belief.name) == true) return false
        val religionUsingBelief = manager.getReligionWithBelief(belief)
        if (religionUsingBelief != null && religionUsingBelief != currentReligion) return false
        if (belief.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals)
                .any { !it.conditionalsApply(civ.state) }) return false
        if (belief.getMatchingUniques(UniqueType.Unavailable, civ.state).any()) return false
        return belief.type != BeliefType.None
    }

    private fun availableBeliefs(civ: Civilization): List<Belief> =
        civ.gameInfo.ruleset.beliefs.values.asSequence()
            .filter { isBeliefAvailable(civ, it) }
            .sortedWith(compareBy<Belief> { it.type.ordinal }.thenBy { it.name })
            .toList()

    private fun availableReligionSymbols(civ: Civilization): List<String> =
        civ.gameInfo.ruleset.religions.filter { it !in civ.gameInfo.religions }.sorted()

    private fun parseBeliefList(text: String): List<String> =
        text.split("|").map { it.trim() }.filter { it.isNotEmpty() }

    private fun writeOutputs(config: ReligionConfig, gameInfo: GameInfo, results: List<ReligionResult>) {
        val civ = gameInfo.currentPlayerCiv
        val manager = civ.religionManager
        val outputDir = File(config.outputDir).apply { mkdirs() }
        val required = requiredBeliefs(manager)
        val available = availableBeliefs(civ)
        val directActions = ArrayList<ReligionLegalAction>()
        if (manager.religionState == ReligionState.None && required[BeliefType.Pantheon] == 1) {
            for (belief in available.filter { it.type == BeliefType.Pantheon }) {
                directActions += ReligionLegalAction(
                    "religion:pantheon:${belief.name}",
                    "pantheon",
                    "Found pantheon ${belief.name}"
                )
            }
        }
        val beliefOptions = available.map { belief ->
            "{" + listOf(
                religionJson("name", belief.name),
                religionJson("type", belief.type.name)
            ).joinToString(",") + "}"
        }
        val requiredSlots = required.entries.sortedBy { it.key.ordinal }.map { entry ->
            "{" + listOf(
                religionJson("type", entry.key.name),
                religionJson("count", entry.value)
            ).joinToString(",") + "}"
        }
        File(outputDir, "religion-legal-actions.json").writeText(religionArrayJson(directActions.map { it.toJson() }) + "\n")
        File(outputDir, "religion-options.json").writeText(
            "{" + listOf(
                religionJson("turn", gameInfo.turns),
                religionJson("religionState", manager.religionState.name),
                religionJson("storedFaith", manager.storedFaith),
                religionJson("pantheonFaithCost", manager.faithForPantheon()),
                religionJson("usingFreeBeliefs", manager.usingFreeBeliefs()),
                religionJson("remainingFoundableReligions", manager.remainingFoundableReligions()),
                religionJson("currentReligion", manager.religion?.name),
                religionJson("currentReligionDisplayName", manager.religion?.displayName),
                "\"requiredBeliefSlots\":${religionArrayJson(requiredSlots)}",
                "\"availableReligionSymbols\":${religionStringArrayJson(availableReligionSymbols(civ))}",
                "\"availableBeliefs\":${religionArrayJson(beliefOptions)}",
                religionJson("foundCommandFormat", "religion:found:<symbol>:<displayName>:<belief1>|<belief2>|..."),
                religionJson("enhanceCommandFormat", "religion:enhance:<belief1>|<belief2>|..."),
                religionJson("freeBeliefCommandFormat", "religion:addBeliefs:<belief1>|<belief2>|..."),
                religionJson("automationUsed", false)
            ).joinToString(",") + "}\n"
        )
        File(outputDir, "religion-result.json").writeText(
            "{" + listOf(
                religionJson("religionState", manager.religionState.name),
                religionJson("automationUsed", false),
                "\"results\":${religionArrayJson(results.map { it.toJson() })}"
            ).joinToString(",") + "}\n"
        )
    }

    private data class ReligionOutcome(val applied: Boolean, val message: String)

    private data class ReligionLegalAction(val actionId: String, val category: String, val label: String) {
        fun toJson(): String = "{" + listOf(
            religionJson("actionId", actionId),
            religionJson("category", category),
            religionJson("label", label)
        ).joinToString(",") + "}"
    }

    private data class ReligionResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson(): String = "{" + listOf(
            religionJson("actionId", actionId),
            religionJson("applied", applied),
            religionJson("error", error),
            religionJson("message", message)
        ).joinToString(",") + "}"
    }

    private data class ReligionConfig(val saveFile: String, val outputDir: String, val commandsFile: String?) {
        companion object {
            fun fromArgs(args: Array<String>): ReligionConfig {
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
                return ReligionConfig(
                    saveFile = values["save-file"] ?: "$output/chat-player-save.json",
                    outputDir = output,
                    commandsFile = values["commands"]
                )
            }
        }
    }
}

private fun religionArrayJson(values: Iterable<String>): String = values.joinToString(prefix = "[", postfix = "]")
private fun religionStringArrayJson(values: Iterable<String>): String = religionArrayJson(values.map { "\"${escapeReligionJson(it)}\"" })
private fun religionJson(name: String, value: String?): String = "\"$name\":${if (value == null) "null" else "\"${escapeReligionJson(value)}\""}"
private fun religionJson(name: String, value: Int): String = "\"$name\":$value"
private fun religionJson(name: String, value: Boolean): String = "\"$name\":$value"
private fun escapeReligionJson(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
private fun unescapeReligionJson(value: String): String = value
    .replace("\\n", "\n")
    .replace("\\\"", "\"")
    .replace("\\\\", "\\")
