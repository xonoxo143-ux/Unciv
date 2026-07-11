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
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Explicit gold purchases for the chat benchmark. No purchase target is selected by automation. */
internal object ChatGoldActions {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = GoldConfig.fromArgs(args)
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
        println("Gold chat actions complete: actions=${results.size} applied=${results.count { it.applied }} rejected=${results.count { !it.applied }}")
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
            .map { unescapeGoldJson(it.groupValues[1]) }
            .toList()
        if (fromJson.isNotEmpty()) return fromJson
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }

    private fun applyAction(civ: Civilization, actionId: String): GoldResult {
        val legal = legalActions(civ).associateBy { it.actionId }
        val action = legal[actionId]
            ?: return GoldResult(actionId, false, false, "Rejected: unknown, unaffordable, or currently illegal gold action")
        return runCatching {
            when (action.category) {
                "purchaseConstruction" -> applyConstructionPurchase(civ, actionId)
                "buyTile" -> applyTilePurchase(civ, actionId)
                else -> GoldOutcome(false, "Rejected: unsupported gold action category")
            }
        }.fold(
            onSuccess = { GoldResult(actionId, it.applied, false, it.message) },
            onFailure = { GoldResult(actionId, false, true, "Error while applying gold action: ${it.message ?: it::class.simpleName}") }
        )
    }

    private fun applyConstructionPurchase(civ: Civilization, actionId: String): GoldOutcome {
        val parts = actionId.split(":", limit = 4)
        if (parts.size != 4) return GoldOutcome(false, "Rejected: malformed construction purchase")
        val city = findCity(civ, parts[1]) ?: return GoldOutcome(false, "Rejected: city ${parts[1]} not found")
        val construction = legalConstructionPurchases(city).firstOrNull { it.first.name == parts[3] }
            ?: return GoldOutcome(false, "Rejected: ${parts[3]} is not a legal gold purchase in ${city.name}")
        val goldBefore = civ.gold
        val success = city.cityConstructions.purchaseConstruction(
            construction = construction.first,
            queuePosition = -1,
            automatic = false,
            stat = Stat.Gold,
            tile = null
        )
        return if (success)
            GoldOutcome(true, "Purchased ${construction.first.name} in ${city.name} for ${construction.second} gold; gold $goldBefore -> ${civ.gold}")
        else GoldOutcome(false, "Rejected: ${construction.first.name} could not be placed or constructed")
    }

    private fun applyTilePurchase(civ: Civilization, actionId: String): GoldOutcome {
        val parts = actionId.split(":", limit = 4)
        if (parts.size != 4) return GoldOutcome(false, "Rejected: malformed tile purchase")
        val city = findCity(civ, parts[1]) ?: return GoldOutcome(false, "Rejected: city ${parts[1]} not found")
        val coordinates = parts[3].split(",", limit = 2)
        if (coordinates.size != 2) return GoldOutcome(false, "Rejected: malformed tile coordinates")
        val x = coordinates[0].toIntOrNull() ?: return GoldOutcome(false, "Rejected: malformed tile x")
        val y = coordinates[1].toIntOrNull() ?: return GoldOutcome(false, "Rejected: malformed tile y")
        val tile = city.tileMap.getOrNull(x, y) ?: return GoldOutcome(false, "Rejected: tile is outside the map")
        if (!city.expansion.canBuyTile(tile)) return GoldOutcome(false, "Rejected: tile ${tile.position} cannot be bought by ${city.name}")
        val cost = city.expansion.getGoldCostOfTile(tile)
        if (civ.gold < cost) return GoldOutcome(false, "Rejected: tile costs $cost gold but only ${civ.gold} is available")
        val goldBefore = civ.gold
        city.expansion.buyTile(tile)
        return GoldOutcome(true, "Purchased tile ${tile.position} for ${city.name} for $cost gold; gold $goldBefore -> ${civ.gold}")
    }

    private fun legalActions(civ: Civilization): List<GoldLegalAction> {
        val actions = ArrayList<GoldLegalAction>()
        for (city in civ.cities.sortedBy { it.name }) {
            val cityId = cityToken(city)
            for ((construction, cost) in legalConstructionPurchases(city)) {
                actions += GoldLegalAction(
                    "city:$cityId:purchase:${construction.name}",
                    "purchaseConstruction",
                    "Purchase ${construction.name} in ${city.name} for $cost gold",
                    cost
                )
            }
            for (tile in city.expansion.getChoosableTiles()
                .filter { city.expansion.canBuyTile(it) }
                .sortedWith(compareBy({ it.position.x }, { it.position.y }))) {
                val cost = city.expansion.getGoldCostOfTile(tile)
                if (cost > civ.gold) continue
                actions += GoldLegalAction(
                    "city:$cityId:buyTile:${tile.position.x},${tile.position.y}",
                    "buyTile",
                    "Buy tile ${tile.position} for ${city.name} for $cost gold",
                    cost
                )
            }
        }
        return actions
    }

    private fun legalConstructionPurchases(city: City): List<Pair<INonPerpetualConstruction, Int>> {
        val constructions = city.getRuleset().buildings.values.asSequence().map { it as INonPerpetualConstruction } +
            city.getRuleset().units.values.asSequence().map { it as INonPerpetualConstruction }
        return constructions
            .filterNot { it.getMatchingUniques(UniqueType.CreatesOneImprovement, city.state).any() }
            .mapNotNull { construction ->
                val cost = construction.getStatBuyCost(city, Stat.Gold) ?: return@mapNotNull null
                if (!city.cityConstructions.isConstructionPurchaseAllowed(construction, Stat.Gold, cost)) return@mapNotNull null
                construction to cost
            }
            .sortedWith(compareBy<Pair<INonPerpetualConstruction, Int>> { it.second }.thenBy { it.first.name })
            .toList()
    }

    private fun findCity(civ: Civilization, token: String): City? =
        civ.cities.firstOrNull { cityToken(it) == token || it.name == token }

    private fun cityToken(city: City): String =
        city.id.takeIf { it.isNotBlank() && it != Constants.NO_ID.toString() } ?: city.name

    private fun writeOutputs(config: GoldConfig, gameInfo: GameInfo, results: List<GoldResult>) {
        val civ = gameInfo.currentPlayerCiv
        val outputDir = File(config.outputDir).apply { mkdirs() }
        val legal = legalActions(civ)
        File(outputDir, "gold-legal-actions.json").writeText(goldArrayJson(legal.map { it.toJson() }) + "\n")
        File(outputDir, "gold-result.json").writeText(
            "{" + listOf(
                goldJson("turn", gameInfo.turns),
                goldJson("currentPlayer", gameInfo.currentPlayer),
                goldJson("gold", civ.gold),
                goldJson("automationUsed", false),
                "\"results\":${goldArrayJson(results.map { it.toJson() })}"
            ).joinToString(",") + "}\n"
        )
    }

    private data class GoldOutcome(val applied: Boolean, val message: String)

    private data class GoldLegalAction(val actionId: String, val category: String, val label: String, val cost: Int) {
        fun toJson(): String = "{" + listOf(
            goldJson("actionId", actionId),
            goldJson("category", category),
            goldJson("label", label),
            goldJson("cost", cost)
        ).joinToString(",") + "}"
    }

    private data class GoldResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson(): String = "{" + listOf(
            goldJson("actionId", actionId),
            goldJson("applied", applied),
            goldJson("error", error),
            goldJson("message", message)
        ).joinToString(",") + "}"
    }

    private data class GoldConfig(val saveFile: String, val outputDir: String, val commandsFile: String?) {
        companion object {
            fun fromArgs(args: Array<String>): GoldConfig {
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
                return GoldConfig(
                    saveFile = values["save-file"] ?: "$output/chat-player-save.json",
                    outputDir = output,
                    commandsFile = values["commands"]
                )
            }
        }
    }
}

private fun goldArrayJson(values: Iterable<String>): String = values.joinToString(prefix = "[", postfix = "]")
private fun goldJson(name: String, value: String?): String = "\"$name\":${if (value == null) "null" else "\"${escapeGoldJson(value)}\""}"
private fun goldJson(name: String, value: Int): String = "\"$name\":$value"
private fun goldJson(name: String, value: Boolean): String = "\"$name\":$value"
private fun escapeGoldJson(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
private fun unescapeGoldJson(value: String): String = value
    .replace("\\n", "\n")
    .replace("\\\"", "\"")
    .replace("\\\\", "\\")
