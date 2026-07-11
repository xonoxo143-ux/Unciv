package com.unciv.app.desktop

import com.unciv.Constants
import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Explicit production queue editing without automatic production selection. */
internal object ChatQueueActions {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = QueueConfig.fromArgs(args)
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
        println("Queue chat actions complete: actions=${results.size} applied=${results.count { it.applied }} rejected=${results.count { !it.applied }}")
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
            .map { unescapeQueueJson(it.groupValues[1]) }
            .toList()
        if (fromJson.isNotEmpty()) return fromJson
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }

    private fun applyAction(civ: Civilization, actionId: String): QueueResult {
        val legal = legalActions(civ).associateBy { it.actionId }
        val action = legal[actionId]
            ?: return QueueResult(actionId, false, false, "Rejected: unknown or currently illegal queue action")
        return runCatching {
            val parts = actionId.split(":", limit = 5)
            if (parts.size != 5 || parts[2] != "queue") return@runCatching QueueOutcome(false, "Rejected: malformed queue action")
            val city = findCity(civ, parts[1]) ?: return@runCatching QueueOutcome(false, "Rejected: city ${parts[1]} not found")
            when (action.category) {
                "queueAdd" -> applyAdd(city, parts[4])
                "queueRemove" -> applyRemove(city, parts[4])
                "queueRaise" -> applyPriority(city, parts[4], true)
                "queueLower" -> applyPriority(city, parts[4], false)
                else -> QueueOutcome(false, "Rejected: unsupported queue category")
            }
        }.fold(
            onSuccess = { QueueResult(actionId, it.applied, false, it.message) },
            onFailure = { QueueResult(actionId, false, true, "Error while applying queue action: ${it.message ?: it::class.simpleName}") }
        )
    }

    private fun applyAdd(city: City, name: String): QueueOutcome {
        val construction = legalQueueAdditions(city).firstOrNull { it.name == name }
            ?: return QueueOutcome(false, "Rejected: $name cannot be queued in ${city.name}")
        city.cityConstructions.addToQueue(construction, addToTop = false)
        return QueueOutcome(true, "Added $name to ${city.name} queue at index ${city.cityConstructions.constructionQueue.indexOfLast { it == name }}")
    }

    private fun applyRemove(city: City, indexText: String): QueueOutcome {
        val index = indexText.toIntOrNull() ?: return QueueOutcome(false, "Rejected: invalid queue index")
        if (index !in city.cityConstructions.constructionQueue.indices) return QueueOutcome(false, "Rejected: queue index $index is invalid")
        val name = city.cityConstructions.constructionQueue[index]
        city.cityConstructions.removeFromQueue(index, automatic = false)
        return QueueOutcome(true, "Removed $name from ${city.name} queue index $index")
    }

    private fun applyPriority(city: City, indexText: String, raise: Boolean): QueueOutcome {
        val index = indexText.toIntOrNull() ?: return QueueOutcome(false, "Rejected: invalid queue index")
        if (index !in city.cityConstructions.constructionQueue.indices) return QueueOutcome(false, "Rejected: queue index $index is invalid")
        val name = city.cityConstructions.constructionQueue[index]
        val newIndex = if (raise) city.cityConstructions.raisePriority(index) else city.cityConstructions.lowerPriority(index)
        return QueueOutcome(true, "Moved $name in ${city.name} queue from $index to $newIndex")
    }

    private fun legalActions(civ: Civilization): List<QueueLegalAction> {
        val actions = ArrayList<QueueLegalAction>()
        for (city in civ.cities.sortedBy { it.name }) {
            if (city.isPuppet || city.isInResistance()) continue
            val cityId = cityToken(city)
            for (construction in legalQueueAdditions(city)) {
                actions += QueueLegalAction(
                    "city:$cityId:queue:add:${construction.name}",
                    "queueAdd",
                    "Add ${construction.name} to ${city.name} production queue"
                )
            }
            for ((index, name) in city.cityConstructions.constructionQueue.withIndex()) {
                actions += QueueLegalAction(
                    "city:$cityId:queue:remove:$index",
                    "queueRemove",
                    "Remove $name from ${city.name} queue index $index"
                )
                if (index > 0) {
                    actions += QueueLegalAction(
                        "city:$cityId:queue:raise:$index",
                        "queueRaise",
                        "Raise $name in ${city.name} queue from index $index"
                    )
                }
                if (index < city.cityConstructions.constructionQueue.lastIndex) {
                    actions += QueueLegalAction(
                        "city:$cityId:queue:lower:$index",
                        "queueLower",
                        "Lower $name in ${city.name} queue from index $index"
                    )
                }
            }
        }
        return actions
    }

    private fun legalQueueAdditions(city: City): List<IConstruction> {
        if (city.cityConstructions.isQueueFull()) return emptyList()
        val buildings = city.getRuleset().buildings.values.asSequence()
            .filterNot { it.getMatchingUniques(UniqueType.CreatesOneImprovement, city.state).any() }
            .map { it as IConstruction }
        val units = city.getRuleset().units.values.asSequence().map { it as IConstruction }
        return (buildings + units)
            .filter { city.cityConstructions.canAddToQueue(it) }
            .sortedBy { it.name }
            .toList()
    }

    private fun findCity(civ: Civilization, token: String): City? =
        civ.cities.firstOrNull { cityToken(it) == token || it.name == token }

    private fun cityToken(city: City): String =
        city.id.takeIf { it.isNotBlank() && it != Constants.NO_ID.toString() } ?: city.name

    private fun writeOutputs(config: QueueConfig, gameInfo: GameInfo, results: List<QueueResult>) {
        val outputDir = File(config.outputDir).apply { mkdirs() }
        val civ = gameInfo.currentPlayerCiv
        val legal = legalActions(civ)
        val queues = civ.cities.sortedBy { it.name }.map { city ->
            "{" + listOf(
                queueJson("city", city.name),
                "\"queue\":${queueStringArrayJson(city.cityConstructions.constructionQueue)}"
            ).joinToString(",") + "}"
        }
        File(outputDir, "queue-legal-actions.json").writeText(queueArrayJson(legal.map { it.toJson() }) + "\n")
        File(outputDir, "queue-result.json").writeText(
            "{" + listOf(
                queueJson("turn", gameInfo.turns),
                queueJson("automationUsed", false),
                "\"queues\":${queueArrayJson(queues)}",
                "\"results\":${queueArrayJson(results.map { it.toJson() })}"
            ).joinToString(",") + "}\n"
        )
    }

    private data class QueueOutcome(val applied: Boolean, val message: String)

    private data class QueueLegalAction(val actionId: String, val category: String, val label: String) {
        fun toJson(): String = "{" + listOf(
            queueJson("actionId", actionId),
            queueJson("category", category),
            queueJson("label", label)
        ).joinToString(",") + "}"
    }

    private data class QueueResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson(): String = "{" + listOf(
            queueJson("actionId", actionId),
            queueJson("applied", applied),
            queueJson("error", error),
            queueJson("message", message)
        ).joinToString(",") + "}"
    }

    private data class QueueConfig(val saveFile: String, val outputDir: String, val commandsFile: String?) {
        companion object {
            fun fromArgs(args: Array<String>): QueueConfig {
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
                return QueueConfig(
                    saveFile = values["save-file"] ?: "$output/chat-player-save.json",
                    outputDir = output,
                    commandsFile = values["commands"]
                )
            }
        }
    }
}

private fun queueArrayJson(values: Iterable<String>): String = values.joinToString(prefix = "[", postfix = "]")
private fun queueStringArrayJson(values: Iterable<String>): String = queueArrayJson(values.map { "\"${escapeQueueJson(it)}\"" })
private fun queueJson(name: String, value: String?): String = "\"$name\":${if (value == null) "null" else "\"${escapeQueueJson(value)}\""}"
private fun queueJson(name: String, value: Int): String = "\"$name\":$value"
private fun queueJson(name: String, value: Boolean): String = "\"$name\":$value"
private fun escapeQueueJson(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
private fun unescapeQueueJson(value: String): String = value
    .replace("\\n", "\n")
    .replace("\\\"", "\"")
    .replace("\\\\", "\\")
