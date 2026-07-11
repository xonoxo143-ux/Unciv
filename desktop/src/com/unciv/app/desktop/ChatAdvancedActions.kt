package com.unciv.app.desktop

import com.unciv.Constants
import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/**
 * Additional explicit player controls kept separate from the stable ChatPlayerHarness command lane.
 * This executor never invokes built-in unit or economy automation.
 */
internal object ChatAdvancedActions {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = AdvancedConfig.fromArgs(args)
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
        if (results.any { it.error }) {
            writeOutputs(config, gameInfo, results)
            exitProcess(1)
        }

        saveFile.parentFile?.mkdirs()
        saveFile.writeText(UncivFiles.gameInfoToString(gameInfo, forceZip = false, updateChecksum = false))
        writeOutputs(config, gameInfo, results)
        println("Advanced chat actions complete: actions=${results.size} applied=${results.count { it.applied }} rejected=${results.count { !it.applied }}")
        exitProcess(0)
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
            .map { unescapeAdvancedJson(it.groupValues[1]) }
            .toList()
        if (fromJson.isNotEmpty()) return fromJson
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }

    private fun applyAction(civ: Civilization, actionId: String): AdvancedResult {
        val legal = legalActions(civ).associateBy { it.actionId }
        val action = legal[actionId]
            ?: return AdvancedResult(actionId, false, false, "Rejected: unknown or currently illegal advanced action")

        return runCatching {
            when (action.category) {
                "promotion" -> applyPromotion(civ, actionId)
                "upgrade" -> applyUpgrade(civ, actionId)
                "cityFocus" -> applyCityFocus(civ, actionId)
                "avoidGrowth" -> applyAvoidGrowth(civ, actionId)
                else -> ActionOutcome(false, "Rejected: unsupported advanced category ${action.category}")
            }
        }.fold(
            onSuccess = { AdvancedResult(actionId, it.applied, false, it.message) },
            onFailure = { AdvancedResult(actionId, false, true, "Error while applying action: ${it.message ?: it::class.simpleName}") }
        )
    }

    private fun applyPromotion(civ: Civilization, actionId: String): ActionOutcome {
        val parts = actionId.split(":", limit = 4)
        if (parts.size != 4) return ActionOutcome(false, "Rejected: malformed promotion action")
        val unit = findUnit(civ, parts[1]) ?: return ActionOutcome(false, "Rejected: unit ${parts[1]} not found")
        val promotionName = parts[3]
        val promotion = unit.promotions.getAvailablePromotions().firstOrNull { it.name == promotionName }
            ?: return ActionOutcome(false, "Rejected: $promotionName is not available for unit ${unit.id}")
        val isFree = promotion.hasUnique(UniqueType.FreePromotion)
        if (!isFree && unit.promotions.XP < unit.promotions.xpForNextPromotion())
            return ActionOutcome(false, "Rejected: unit ${unit.id} lacks XP for $promotionName")
        val xpBefore = unit.promotions.XP
        unit.promotions.addPromotion(promotionName)
        return ActionOutcome(true, "Promoted unit ${unit.id} (${unit.name}) with $promotionName; XP $xpBefore -> ${unit.promotions.XP}")
    }

    private fun applyUpgrade(civ: Civilization, actionId: String): ActionOutcome {
        val parts = actionId.split(":", limit = 4)
        if (parts.size != 4) return ActionOutcome(false, "Rejected: malformed upgrade action")
        val unit = findUnit(civ, parts[1]) ?: return ActionOutcome(false, "Rejected: unit ${parts[1]} not found")
        val targetName = parts[3]
        val target = legalUpgradeTargets(unit).firstOrNull { it.name == targetName }
            ?: return ActionOutcome(false, "Rejected: $targetName is not a legal upgrade for unit ${unit.id}")
        val cost = unit.upgrade.getCostOfUpgrade(target)
        if (civ.gold < cost) return ActionOutcome(false, "Rejected: upgrade costs $cost gold but only ${civ.gold} is available")
        val oldName = unit.name
        val id = unit.id
        val goldBefore = civ.gold
        unit.upgrade.performUpgrade(target, isFree = false, goldCostOfUpgrade = cost)
        val replacement = findUnit(civ, id.toString())
        return if (replacement != null && replacement.name == target.name)
            ActionOutcome(true, "Upgraded unit $id from $oldName to ${replacement.name}; gold $goldBefore -> ${civ.gold}")
        else ActionOutcome(false, "Rejected: upgrade placement failed and the original unit was restored")
    }

    private fun applyCityFocus(civ: Civilization, actionId: String): ActionOutcome {
        val parts = actionId.split(":", limit = 4)
        if (parts.size != 4) return ActionOutcome(false, "Rejected: malformed city focus action")
        val city = findCity(civ, parts[1]) ?: return ActionOutcome(false, "Rejected: city ${parts[1]} not found")
        val focus = CityFocus.entries.firstOrNull { it.name == parts[3] && it.tableEnabled }
            ?: return ActionOutcome(false, "Rejected: city focus ${parts[3]} is unavailable")
        city.setCityFocus(focus)
        city.reassignPopulation(resetLocked = false)
        city.cityStats.update(updateCivStats = true)
        return ActionOutcome(true, "Set ${city.name} focus to ${focus.name} and reassigned population")
    }

    private fun applyAvoidGrowth(civ: Civilization, actionId: String): ActionOutcome {
        val parts = actionId.split(":", limit = 4)
        if (parts.size != 4) return ActionOutcome(false, "Rejected: malformed avoid-growth action")
        val city = findCity(civ, parts[1]) ?: return ActionOutcome(false, "Rejected: city ${parts[1]} not found")
        val enabled = parts[3].toBooleanStrictOrNull()
            ?: return ActionOutcome(false, "Rejected: avoid-growth value must be true or false")
        city.avoidGrowth = enabled
        city.cityStats.update(updateCivStats = true)
        return ActionOutcome(true, "Set ${city.name} avoidGrowth=$enabled")
    }

    private fun legalActions(civ: Civilization): List<AdvancedLegalAction> {
        val actions = ArrayList<AdvancedLegalAction>()
        for (unit in civ.units.getCivUnits().sortedWith(compareBy<MapUnit> { it.id }.thenBy { it.name })) {
            if (unit.promotions.canBePromoted()) {
                val hasPaidPromotionXp = unit.promotions.XP >= unit.promotions.xpForNextPromotion()
                for (promotion in unit.promotions.getAvailablePromotions().sortedBy { it.name }) {
                    if (!hasPaidPromotionXp && !promotion.hasUnique(UniqueType.FreePromotion)) continue
                    actions += AdvancedLegalAction(
                        "unit:${unit.id}:promote:${promotion.name}",
                        "promotion",
                        "Promote ${unit.name} #${unit.id} with ${promotion.name}"
                    )
                }
            }
            for (target in legalUpgradeTargets(unit)) {
                val cost = unit.upgrade.getCostOfUpgrade(target)
                if (civ.gold < cost) continue
                actions += AdvancedLegalAction(
                    "unit:${unit.id}:upgrade:${target.name}",
                    "upgrade",
                    "Upgrade ${unit.name} #${unit.id} to ${target.name} for $cost gold"
                )
            }
        }

        for (city in civ.cities.sortedBy { it.name }) {
            val cityId = cityToken(city)
            for (focus in CityFocus.entries.filter { it.tableEnabled }) {
                actions += AdvancedLegalAction(
                    "city:$cityId:focus:${focus.name}",
                    "cityFocus",
                    "Set ${city.name} focus to ${focus.name}"
                )
            }
            actions += AdvancedLegalAction(
                "city:$cityId:avoidGrowth:${!city.avoidGrowth}",
                "avoidGrowth",
                "Set ${city.name} avoid growth to ${!city.avoidGrowth}"
            )
        }
        return actions
    }

    private fun legalUpgradeTargets(unit: MapUnit): List<BaseUnit> {
        if (!unit.hasMovement() || unit.isEmbarked() || unit.currentTile.getOwner() != unit.civ) return emptyList()
        return unit.baseUnit.getUpgradeUnits(unit.cache.state)
            .map { unit.civ.getEquivalentUnit(it) }
            .filter { unit.upgrade.canUpgrade(it) }
            .distinctBy { it.name }
            .sortedBy { it.name }
            .toList()
    }

    private fun findUnit(civ: Civilization, idText: String): MapUnit? =
        idText.toIntOrNull()?.let { id -> civ.units.getCivUnits().firstOrNull { it.id == id } }

    private fun findCity(civ: Civilization, token: String): City? =
        civ.cities.firstOrNull { cityToken(it) == token || it.name == token }

    private fun cityToken(city: City): String =
        city.id.takeIf { it.isNotBlank() && it != Constants.NO_ID.toString() } ?: city.name

    private fun writeOutputs(config: AdvancedConfig, gameInfo: GameInfo, results: List<AdvancedResult>) {
        val outputDir = File(config.outputDir).apply { mkdirs() }
        val legal = legalActions(gameInfo.currentPlayerCiv)
        File(outputDir, "advanced-legal-actions.json").writeText(
            advancedArrayJson(legal.map { it.toJson() }) + "\n"
        )
        File(outputDir, "advanced-result.json").writeText(
            "{" + listOf(
                advancedJson("turn", gameInfo.turns),
                advancedJson("currentPlayer", gameInfo.currentPlayer),
                advancedJson("automationUsed", false),
                "\"results\":${advancedArrayJson(results.map { it.toJson() })}"
            ).joinToString(",") + "}\n"
        )
    }

    private data class ActionOutcome(val applied: Boolean, val message: String)

    private data class AdvancedLegalAction(val actionId: String, val category: String, val label: String) {
        fun toJson(): String = "{" + listOf(
            advancedJson("actionId", actionId),
            advancedJson("category", category),
            advancedJson("label", label)
        ).joinToString(",") + "}"
    }

    private data class AdvancedResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson(): String = "{" + listOf(
            advancedJson("actionId", actionId),
            advancedJson("applied", applied),
            advancedJson("error", error),
            advancedJson("message", message)
        ).joinToString(",") + "}"
    }

    private data class AdvancedConfig(val saveFile: String, val outputDir: String, val commandsFile: String?) {
        companion object {
            fun fromArgs(args: Array<String>): AdvancedConfig {
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
                return AdvancedConfig(
                    saveFile = values["save-file"] ?: "$output/chat-player-save.json",
                    outputDir = output,
                    commandsFile = values["commands"]
                )
            }
        }
    }
}

private fun advancedArrayJson(values: Iterable<String>): String = values.joinToString(prefix = "[", postfix = "]")
private fun advancedJson(name: String, value: String?): String = "\"$name\":${if (value == null) "null" else "\"${escapeAdvancedJson(value)}\""}"
private fun advancedJson(name: String, value: Int): String = "\"$name\":$value"
private fun advancedJson(name: String, value: Boolean): String = "\"$name\":$value"
private fun escapeAdvancedJson(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
private fun unescapeAdvancedJson(value: String): String = value
    .replace("\\n", "\n")
    .replace("\\\"", "\"")
    .replace("\\\\", "\\")
