package com.unciv.app.desktop

import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Explicit post-conquest city decisions for the headless chat benchmark. */
internal object ChatConquestActions {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = ConquestConfig.fromArgs(args)
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
        val results = actionIds.map { applyAction(gameInfo, civ, it) }
        if (results.none { it.error })
            saveFile.writeText(UncivFiles.gameInfoToString(gameInfo, forceZip = false, updateChecksum = false))
        writeOutputs(config, gameInfo, results)
        println("Conquest chat actions complete: actions=${results.size} applied=${results.count { it.applied }} rejected=${results.count { !it.applied }}")
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
            .map { unescapeConquestJson(it.groupValues[1]) }
            .toList()
        if (fromJson.isNotEmpty()) return fromJson
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }

    private fun applyAction(gameInfo: GameInfo, civ: Civilization, actionId: String): ConquestResult {
        val legal = legalActions(gameInfo, civ).associateBy { it.actionId }
        val action = legal[actionId]
            ?: return ConquestResult(actionId, false, false, "Rejected: unknown or currently illegal conquest decision")
        val parts = actionId.split(":", limit = 3)
        if (parts.size != 3 || parts[0] != "conquest")
            return ConquestResult(actionId, false, false, "Rejected: malformed conquest action")
        val alert = pendingConquests(civ).firstOrNull { it.value == parts[1] }
            ?: return ConquestResult(actionId, false, false, "Rejected: no pending conquest alert for city ${parts[1]}")
        val city = gameInfo.getCities().firstOrNull { it.id == alert.value }
            ?: return ConquestResult(actionId, false, false, "Rejected: conquered city no longer exists")

        return runCatching {
            val outcome = when (action.category) {
                "liberate" -> {
                    city.liberateCity(civ)
                    "Liberated ${city.name} to ${city.foundingCivObject?.civName ?: "its founder"}"
                }
                "puppet" -> {
                    city.puppetCity(civ)
                    "Puppeted ${city.name}"
                }
                "annex" -> {
                    city.puppetCity(civ)
                    city.annexCity()
                    "Annexed ${city.name}"
                }
                "raze" -> {
                    val mayAnnex = !civ.hasUnique(UniqueType.MayNotAnnexCities)
                    city.puppetCity(civ)
                    if (mayAnnex) city.annexCity()
                    city.isBeingRazed = true
                    "Began razing ${city.name}"
                }
                "destroy" -> {
                    city.puppetCity(civ)
                    city.destroyCity()
                    "Destroyed ${city.name}"
                }
                else -> return@runCatching ConquestResult(actionId, false, false, "Rejected: unsupported conquest category")
            }
            civ.popupAlerts.remove(alert)
            ConquestResult(actionId, true, false, outcome)
        }.getOrElse {
            ConquestResult(actionId, false, true, "Error while resolving conquest: ${it.message ?: it::class.simpleName}")
        }
    }

    private fun legalActions(gameInfo: GameInfo, civ: Civilization): List<ConquestLegalAction> {
        val actions = ArrayList<ConquestLegalAction>()
        for (alert in pendingConquests(civ)) {
            val city = gameInfo.getCities().firstOrNull { it.id == alert.value } ?: continue
            val cityId = city.id
            val founder = city.foundingCivObject
            if (founder != null && city.civ != founder && civ != founder) {
                actions += ConquestLegalAction(
                    "conquest:$cityId:liberate",
                    "liberate",
                    "Liberate ${city.name} to ${founder.civName}"
                )
            }
            if (civ.isOneCityChallenger()) {
                actions += ConquestLegalAction(
                    "conquest:$cityId:destroy",
                    "destroy",
                    "Destroy ${city.name} immediately"
                )
                continue
            }
            val mayAnnex = !civ.hasUnique(UniqueType.MayNotAnnexCities)
            actions += ConquestLegalAction(
                "conquest:$cityId:puppet",
                "puppet",
                "Puppet ${city.name}"
            )
            if (mayAnnex) {
                actions += ConquestLegalAction(
                    "conquest:$cityId:annex",
                    "annex",
                    "Annex ${city.name}"
                )
            }
            if (city.canBeDestroyed(justCaptured = true)) {
                actions += ConquestLegalAction(
                    "conquest:$cityId:raze",
                    "raze",
                    "Raze ${city.name}"
                )
            }
        }
        return actions
    }

    private fun pendingConquests(civ: Civilization): List<PopupAlert> =
        civ.popupAlerts.filter { it.type == AlertType.CityConquered }

    private fun writeOutputs(config: ConquestConfig, gameInfo: GameInfo, results: List<ConquestResult>) {
        val civ = gameInfo.currentPlayerCiv
        val outputDir = File(config.outputDir).apply { mkdirs() }
        val legal = legalActions(gameInfo, civ)
        File(outputDir, "conquest-legal-actions.json").writeText(conquestArrayJson(legal.map { it.toJson() }) + "\n")
        File(outputDir, "conquest-result.json").writeText(
            "{" + listOf(
                conquestJson("turn", gameInfo.turns),
                conquestJson("pendingConquests", pendingConquests(civ).size),
                conquestJson("automationUsed", false),
                "\"results\":${conquestArrayJson(results.map { it.toJson() })}"
            ).joinToString(",") + "}\n"
        )
    }

    private data class ConquestLegalAction(val actionId: String, val category: String, val label: String) {
        fun toJson(): String = "{" + listOf(
            conquestJson("actionId", actionId),
            conquestJson("category", category),
            conquestJson("label", label)
        ).joinToString(",") + "}"
    }

    private data class ConquestResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson(): String = "{" + listOf(
            conquestJson("actionId", actionId),
            conquestJson("applied", applied),
            conquestJson("error", error),
            conquestJson("message", message)
        ).joinToString(",") + "}"
    }

    private data class ConquestConfig(val saveFile: String, val outputDir: String, val commandsFile: String?) {
        companion object {
            fun fromArgs(args: Array<String>): ConquestConfig {
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
                return ConquestConfig(
                    saveFile = values["save-file"] ?: "$output/chat-player-save.json",
                    outputDir = output,
                    commandsFile = values["commands"]
                )
            }
        }
    }
}

private fun conquestArrayJson(values: Iterable<String>): String = values.joinToString(prefix = "[", postfix = "]")
private fun conquestJson(name: String, value: String?): String = "\"$name\":${if (value == null) "null" else "\"${escapeConquestJson(value)}\""}"
private fun conquestJson(name: String, value: Int): String = "\"$name\":$value"
private fun conquestJson(name: String, value: Boolean): String = "\"$name\":$value"
private fun escapeConquestJson(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
private fun unescapeConquestJson(value: String): String = value
    .replace("\\n", "\n")
    .replace("\\\"", "\"")
    .replace("\\\\", "\\")
