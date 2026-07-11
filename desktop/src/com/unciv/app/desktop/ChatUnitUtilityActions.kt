package com.unciv.app.desktop

import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Explicit swap, heal-rest, and unit-gift controls. */
internal object ChatUnitUtilityActions {
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
        println("Unit utility actions complete: actions=${results.size} applied=${results.count { it.applied }}")
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
            .findAll(text).map { unescapeUtilityJson(it.groupValues[1]) }.toList()
        return ids.ifEmpty { text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList() }
    }

    private fun apply(civ: Civilization, actionId: String): UtilityResult {
        val legal = legalActions(civ).associateBy { it.actionId }[actionId]
            ?: return UtilityResult(actionId, false, false, "Rejected: unknown or currently illegal unit utility action")
        val parts = actionId.split(":", limit = 5)
        val unit = parts.getOrNull(1)?.toIntOrNull()?.let { id -> civ.units.getCivUnits().firstOrNull { it.id == id } }
            ?: return UtilityResult(actionId, false, false, "Rejected: unit no longer exists")
        return runCatching {
            when (legal.category) {
                "swap" -> {
                    val tile = parseTile(unit, parts.getOrNull(4)) ?: return@runCatching Outcome(false, "Rejected: invalid swap target")
                    if (!unit.movement.canUnitSwapTo(tile)) return@runCatching Outcome(false, "Rejected: swap is no longer legal")
                    val origin = unit.currentTile.position
                    val other = if (unit.isCivilian()) tile.civilianUnit else tile.militaryUnit
                    val otherId = other?.id
                    unit.movement.swapMoveToTile(tile)
                    Outcome(true, "Swapped ${unit.name} #${unit.id} from $origin with unit #$otherId at ${tile.position}")
                }
                "fortifyUntilHealed" -> {
                    if (!unit.canFortify() || !unit.hasMovement() || unit.health >= 100 || !unit.canHealInCurrentTile())
                        return@runCatching Outcome(false, "Rejected: fortify-until-healed is no longer legal")
                    unit.fortifyUntilHealed(); unit.due = false
                    Outcome(true, "${unit.name} #${unit.id} will fortify until healed from ${unit.health} health")
                }
                "sleepUntilHealed" -> {
                    if (unit.canFortify() || !unit.hasMovement() || unit.health >= 100 || !unit.canHealInCurrentTile())
                        return@runCatching Outcome(false, "Rejected: sleep-until-healed is no longer legal")
                    unit.action = UnitActionType.SleepUntilHealed.value; unit.due = false
                    Outcome(true, "${unit.name} #${unit.id} will sleep until healed from ${unit.health} health")
                }
                "gift" -> applyGift(unit)
                else -> Outcome(false, "Rejected: unsupported unit utility category")
            }
        }.fold(
            onSuccess = { UtilityResult(actionId, it.applied, false, it.message) },
            onFailure = { UtilityResult(actionId, false, true, "Error while applying unit utility action: ${it.message ?: it::class.simpleName}") }
        )
    }

    private fun applyGift(unit: MapUnit): Outcome {
        val recipient = giftRecipient(unit) ?: return Outcome(false, "Rejected: unit can no longer be gifted here")
        val giver = unit.civ
        val id = unit.id
        val name = unit.name
        if (recipient.isCityState) {
            for (unique in unit.getMatchingUniques(UniqueType.GainInfluenceWithUnitGiftToCityState, checkCivInfoUniques = true)) {
                if (unit.matchesFilter(unique.params[1])) {
                    recipient.getDiplomacyManager(giver)?.addInfluence(unique.params[0].toFloat() - 5f)
                    break
                }
            }
            recipient.getDiplomacyManager(giver)?.addInfluence(5f)
        } else recipient.getDiplomacyManager(giver)?.addModifier(DiplomaticModifiers.GaveUsUnits, 5f)
        if (recipient.isCityState && unit.isGreatPerson()) unit.destroy() else unit.gift(recipient)
        return Outcome(true, "Gifted $name #$id to ${recipient.civName}; greatPersonDestroyedInstead=${recipient.isCityState && unit.isGreatPerson()}")
    }

    private fun legalActions(civ: Civilization): List<LegalUtilityAction> = buildList {
        for (unit in civ.units.getCivUnits().sortedBy { it.id }) {
            for (tile in unit.movement.getUnitSwappableTiles().sortedWith(compareBy<Tile> { it.position.x }.thenBy { it.position.y })) add(
                LegalUtilityAction("unit:${unit.id}:utility:swap:${tile.position.x},${tile.position.y}", "swap", "Swap ${unit.name} #${unit.id} with unit at ${tile.position}")
            )
            if (unit.health < 100 && unit.hasMovement() && unit.canHealInCurrentTile()) {
                if (unit.canFortify() && !unit.isFortifyingUntilHealed()) add(
                    LegalUtilityAction("unit:${unit.id}:utility:fortifyUntilHealed", "fortifyUntilHealed", "Fortify ${unit.name} #${unit.id} until healed")
                )
                if (!unit.canFortify() && !unit.isSleepingUntilHealed()) add(
                    LegalUtilityAction("unit:${unit.id}:utility:sleepUntilHealed", "sleepUntilHealed", "Sleep ${unit.name} #${unit.id} until healed")
                )
            }
            val recipient = giftRecipient(unit)
            if (recipient != null) add(LegalUtilityAction("unit:${unit.id}:utility:gift", "gift", "Gift ${unit.name} #${unit.id} to ${recipient.civName}"))
        }
    }

    private fun giftRecipient(unit: MapUnit): Civilization? {
        if (!unit.hasMovement() || unit.isTransported) return null
        val tile = unit.currentTile
        val recipient = tile.getOwner() ?: return null
        if (recipient == unit.civ) return null
        if (recipient.isCityState) {
            if (recipient.isAtWarWith(unit.civ)) return null
            if (!unit.isMilitary() && unit.getMatchingUniques(UniqueType.GainInfluenceWithUnitGiftToCityState, checkCivInfoUniques = true)
                    .none { unit.matchesFilter(it.params[1]) }) return null
        } else if (!tile.isFriendlyTerritory(unit.civ)) return null
        return recipient
    }

    private fun parseTile(unit: MapUnit, text: String?): Tile? {
        val coordinates = text?.split(",", limit = 2) ?: return null
        if (coordinates.size != 2) return null
        val x = coordinates[0].toIntOrNull() ?: return null
        val y = coordinates[1].toIntOrNull() ?: return null
        return unit.currentTile.tileMap.getOrNull(x, y)
    }

    private fun writeOutputs(config: Config, game: GameInfo, results: List<UtilityResult>) {
        val out = File(config.outputDir).apply { mkdirs() }
        File(out, "unit-utility-legal-actions.json").writeText(utilityArrayJson(legalActions(game.currentPlayerCiv).map { it.toJson() }) + "\n")
        File(out, "unit-utility-result.json").writeText("{" + listOf(
            utilityJson("turn", game.turns), utilityJson("automationUsed", false),
            "\"results\":${utilityArrayJson(results.map { it.toJson() })}"
        ).joinToString(",") + "}\n")
    }

    private data class Outcome(val applied: Boolean, val message: String)
    private data class LegalUtilityAction(val actionId: String, val category: String, val label: String) {
        fun toJson() = "{" + listOf(utilityJson("actionId", actionId), utilityJson("category", category), utilityJson("label", label)).joinToString(",") + "}"
    }
    private data class UtilityResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson() = "{" + listOf(utilityJson("actionId", actionId), utilityJson("applied", applied), utilityJson("error", error), utilityJson("message", message)).joinToString(",") + "}"
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

private fun utilityArrayJson(values: Iterable<String>) = values.joinToString(prefix = "[", postfix = "]")
private fun utilityJson(name: String, value: String?) = "\"$name\":${if (value == null) "null" else "\"${escapeUtilityJson(value)}\""}"
private fun utilityJson(name: String, value: Int) = "\"$name\":$value"
private fun utilityJson(name: String, value: Boolean) = "\"$name\":$value"
private fun escapeUtilityJson(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
private fun unescapeUtilityJson(value: String) = value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
