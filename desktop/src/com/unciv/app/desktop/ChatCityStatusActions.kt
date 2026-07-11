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
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Explicit annex/raze status controls for owned cities. */
internal object ChatCityStatusActions {
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
        println("City status actions complete: actions=${results.size} applied=${results.count { it.applied }}")
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
            .findAll(text).map { unescapeCityStatusJson(it.groupValues[1]) }.toList()
        return ids.ifEmpty { text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList() }
    }

    private fun apply(civ: Civilization, actionId: String): CityStatusResult {
        val legal = legalActions(civ).associateBy { it.actionId }[actionId]
            ?: return CityStatusResult(actionId, false, false, "Rejected: unknown or currently illegal city status action")
        val parts = actionId.split(":", limit = 3)
        val city = parts.getOrNull(1)?.let { token -> civ.cities.firstOrNull { cityToken(it) == token || it.name == token } }
            ?: return CityStatusResult(actionId, false, false, "Rejected: city no longer exists")
        return runCatching {
            when (legal.category) {
                "annex" -> { city.annexCity(); CityStatusResult(actionId, true, false, "Annexed ${city.name}") }
                "raze" -> { city.isBeingRazed = true; CityStatusResult(actionId, true, false, "Began razing ${city.name}") }
                "stopRazing" -> { city.isBeingRazed = false; CityStatusResult(actionId, true, false, "Stopped razing ${city.name}") }
                else -> CityStatusResult(actionId, false, false, "Rejected: unsupported city status category")
            }
        }.getOrElse { CityStatusResult(actionId, false, true, "Error while changing city status: ${it.message ?: it::class.simpleName}") }
    }

    private fun legalActions(civ: Civilization): List<LegalCityStatusAction> = buildList {
        val mayAnnex = !civ.hasUnique(UniqueType.MayNotAnnexCities)
        for (city in civ.cities.sortedBy { it.name }) {
            val id = cityToken(city)
            if (city.isPuppet && mayAnnex) add(LegalCityStatusAction("city:$id:annex", "annex", "Annex ${city.name}"))
            if (city.isBeingRazed) add(LegalCityStatusAction("city:$id:stopRazing", "stopRazing", "Stop razing ${city.name}"))
            else if (!city.isPuppet && mayAnnex && city.canBeDestroyed()) add(LegalCityStatusAction("city:$id:raze", "raze", "Begin razing ${city.name}"))
        }
    }

    private fun cityToken(city: City) = city.id.takeIf { it.isNotBlank() && it != Constants.NO_ID.toString() } ?: city.name

    private fun writeOutputs(config: Config, game: GameInfo, results: List<CityStatusResult>) {
        val out = File(config.outputDir).apply { mkdirs() }
        File(out, "city-status-legal-actions.json").writeText(cityStatusArrayJson(legalActions(game.currentPlayerCiv).map { it.toJson() }) + "\n")
        File(out, "city-status-result.json").writeText("{" + listOf(
            cityStatusJson("turn", game.turns), cityStatusJson("automationUsed", false),
            "\"results\":${cityStatusArrayJson(results.map { it.toJson() })}"
        ).joinToString(",") + "}\n")
    }

    private data class LegalCityStatusAction(val actionId: String, val category: String, val label: String) {
        fun toJson() = "{" + listOf(cityStatusJson("actionId", actionId), cityStatusJson("category", category), cityStatusJson("label", label)).joinToString(",") + "}"
    }
    private data class CityStatusResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson() = "{" + listOf(cityStatusJson("actionId", actionId), cityStatusJson("applied", applied), cityStatusJson("error", error), cityStatusJson("message", message)).joinToString(",") + "}"
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

private fun cityStatusArrayJson(values: Iterable<String>) = values.joinToString(prefix = "[", postfix = "]")
private fun cityStatusJson(name: String, value: String?) = "\"$name\":${if (value == null) "null" else "\"${escapeCityStatusJson(value)}\""}"
private fun cityStatusJson(name: String, value: Int) = "\"$name\":$value"
private fun cityStatusJson(name: String, value: Boolean) = "\"$name\":$value"
private fun escapeCityStatusJson(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
private fun unescapeCityStatusJson(value: String) = value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
