package com.unciv.app.desktop

import com.unciv.Constants
import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Explicit attacks and city bombardment for the headless chat benchmark. */
internal object ChatCombatActions {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = CombatConfig.fromArgs(args)
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
        println("Combat chat actions complete: actions=${results.size} applied=${results.count { it.applied }} rejected=${results.count { !it.applied }}")
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
            .map { unescapeCombatJson(it.groupValues[1]) }
            .toList()
        if (fromJson.isNotEmpty()) return fromJson
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }

    private fun applyAction(civ: Civilization, actionId: String): CombatResult {
        val legal = legalActions(civ).associateBy { it.actionId }
        val action = legal[actionId]
            ?: return CombatResult(actionId, false, false, "Rejected: unknown or currently illegal combat action")
        return runCatching {
            when (action.category) {
                "unitAttack" -> applyUnitAttack(civ, actionId)
                "cityBombard" -> applyCityBombard(civ, actionId)
                else -> CombatOutcome(false, "Rejected: unsupported combat category")
            }
        }.fold(
            onSuccess = { CombatResult(actionId, it.applied, false, it.message, it.attackerDamage, it.defenderDamage) },
            onFailure = { CombatResult(actionId, false, true, "Error while applying combat action: ${it.message ?: it::class.simpleName}") }
        )
    }

    private fun applyUnitAttack(civ: Civilization, actionId: String): CombatOutcome {
        val parts = actionId.split(":", limit = 4)
        if (parts.size != 4 || parts[2] != "attack") return CombatOutcome(false, "Rejected: malformed unit attack")
        val unit = findUnit(civ, parts[1]) ?: return CombatOutcome(false, "Rejected: unit ${parts[1]} not found")
        val target = parseTarget(unit.currentTile, parts[3]) ?: return CombatOutcome(false, "Rejected: malformed target")
        val attackable = legalUnitAttacks(unit).firstOrNull { it.tileToAttack == target }
            ?: return CombatOutcome(false, "Rejected: target ${target.position} is not attackable from the current tile")
        val attacker = MapUnitCombatant(unit)
        val defender = attackable.combatant ?: Battle.getMapCombatantOfTile(target)
            ?: return CombatOutcome(false, "Rejected: target has no combatant")
        val attackerName = attacker.getName()
        val defenderName = defender.getName()
        val attackerHealthBefore = attacker.getHealth()
        val defenderHealthBefore = defender.getHealth()
        if (!Battle.movePreparingAttack(attacker, attackable, tryHealPillage = false))
            return CombatOutcome(false, "Rejected: $attackerName could not prepare the attack without changing target")
        val damage = Battle.attackOrNuke(attacker, attackable)
        val attackerHealthAfter = if (unit.isDestroyed) 0 else unit.health
        val defenderHealthAfter = if (defender.isDefeated()) 0 else defender.getHealth()
        return CombatOutcome(
            true,
            "$attackerName #${unit.id} attacked $defenderName at ${target.position}; attacker health $attackerHealthBefore -> $attackerHealthAfter; defender health $defenderHealthBefore -> $defenderHealthAfter; attackerDefeated=${unit.isDestroyed}; defenderDefeated=${defender.isDefeated()}",
            attackerDamage = damage.defenderDealt,
            defenderDamage = damage.attackerDealt
        )
    }

    private fun applyCityBombard(civ: Civilization, actionId: String): CombatOutcome {
        val parts = actionId.split(":", limit = 4)
        if (parts.size != 4 || parts[2] != "bombard") return CombatOutcome(false, "Rejected: malformed city bombardment")
        val city = findCity(civ, parts[1]) ?: return CombatOutcome(false, "Rejected: city ${parts[1]} not found")
        val target = parseTarget(city.getCenterTile(), parts[3]) ?: return CombatOutcome(false, "Rejected: malformed target")
        if (target !in TargetHelper.getBombardableTiles(city).toList())
            return CombatOutcome(false, "Rejected: target ${target.position} is not bombardable by ${city.name}")
        val attacker = CityCombatant(city)
        val defender = Battle.getMapCombatantOfTile(target)
            ?: return CombatOutcome(false, "Rejected: target has no combatant")
        val defenderName = defender.getName()
        val defenderHealthBefore = defender.getHealth()
        val damage = Battle.attack(attacker, defender)
        val defenderHealthAfter = if (defender.isDefeated()) 0 else defender.getHealth()
        return CombatOutcome(
            true,
            "${city.name} bombarded $defenderName at ${target.position}; defender health $defenderHealthBefore -> $defenderHealthAfter; defeated=${defender.isDefeated()}",
            attackerDamage = damage.defenderDealt,
            defenderDamage = damage.attackerDealt
        )
    }

    private fun legalActions(civ: Civilization): List<CombatLegalAction> {
        val actions = ArrayList<CombatLegalAction>()
        for (unit in civ.units.getCivUnits().sortedWith(compareBy<MapUnit> { it.id }.thenBy { it.name })) {
            for (attack in legalUnitAttacks(unit)) {
                val defender = attack.combatant ?: continue
                actions += CombatLegalAction(
                    "unit:${unit.id}:attack:${attack.tileToAttack.position.x},${attack.tileToAttack.position.y}",
                    "unitAttack",
                    "Attack ${defender.getName()} at ${attack.tileToAttack.position} with ${unit.name} #${unit.id}",
                    attack.tileToAttack.position.x,
                    attack.tileToAttack.position.y
                )
            }
        }
        for (city in civ.cities.sortedBy { it.name }) {
            if (!city.canBombard()) continue
            val cityId = cityToken(city)
            for (tile in TargetHelper.getBombardableTiles(city)
                .sortedWith(compareBy({ it.position.x }, { it.position.y }))) {
                val defender = Battle.getMapCombatantOfTile(tile) ?: continue
                actions += CombatLegalAction(
                    "city:$cityId:bombard:${tile.position.x},${tile.position.y}",
                    "cityBombard",
                    "Bombard ${defender.getName()} at ${tile.position} with ${city.name}",
                    tile.position.x,
                    tile.position.y
                )
            }
        }
        return actions
    }

    private fun legalUnitAttacks(unit: MapUnit): List<AttackableTile> {
        if (!unit.canAttack() || unit.isNuclearWeapon()) return emptyList()
        val distance = unit.movement.getDistanceToTiles()
        return TargetHelper.getAttackableEnemies(unit, distance)
            .filter { it.tileToAttackFrom == unit.currentTile }
            .distinctBy { it.tileToAttack.position }
            .sortedWith(compareBy({ it.tileToAttack.position.x }, { it.tileToAttack.position.y }))
    }

    private fun parseTarget(origin: Tile, coordinatesText: String): Tile? {
        val coordinates = coordinatesText.split(",", limit = 2)
        if (coordinates.size != 2) return null
        val x = coordinates[0].toIntOrNull() ?: return null
        val y = coordinates[1].toIntOrNull() ?: return null
        return origin.tileMap.getOrNull(x, y)
    }

    private fun findUnit(civ: Civilization, idText: String): MapUnit? =
        idText.toIntOrNull()?.let { id -> civ.units.getCivUnits().firstOrNull { it.id == id } }

    private fun findCity(civ: Civilization, token: String): City? =
        civ.cities.firstOrNull { cityToken(it) == token || it.name == token }

    private fun cityToken(city: City): String =
        city.id.takeIf { it.isNotBlank() && it != Constants.NO_ID.toString() } ?: city.name

    private fun writeOutputs(config: CombatConfig, gameInfo: GameInfo, results: List<CombatResult>) {
        val outputDir = File(config.outputDir).apply { mkdirs() }
        val legal = legalActions(gameInfo.currentPlayerCiv)
        File(outputDir, "combat-legal-actions.json").writeText(combatArrayJson(legal.map { it.toJson() }) + "\n")
        File(outputDir, "combat-result.json").writeText(
            "{" + listOf(
                combatJson("turn", gameInfo.turns),
                combatJson("currentPlayer", gameInfo.currentPlayer),
                combatJson("automationUsed", false),
                "\"results\":${combatArrayJson(results.map { it.toJson() })}"
            ).joinToString(",") + "}\n"
        )
    }

    private data class CombatOutcome(
        val applied: Boolean,
        val message: String,
        val attackerDamage: Int = 0,
        val defenderDamage: Int = 0
    )

    private data class CombatLegalAction(
        val actionId: String,
        val category: String,
        val label: String,
        val targetX: Int,
        val targetY: Int
    ) {
        fun toJson(): String = "{" + listOf(
            combatJson("actionId", actionId),
            combatJson("category", category),
            combatJson("label", label),
            combatJson("targetX", targetX),
            combatJson("targetY", targetY)
        ).joinToString(",") + "}"
    }

    private data class CombatResult(
        val actionId: String,
        val applied: Boolean,
        val error: Boolean,
        val message: String,
        val attackerDamage: Int = 0,
        val defenderDamage: Int = 0
    ) {
        fun toJson(): String = "{" + listOf(
            combatJson("actionId", actionId),
            combatJson("applied", applied),
            combatJson("error", error),
            combatJson("message", message),
            combatJson("damageToAttacker", attackerDamage),
            combatJson("damageToDefender", defenderDamage)
        ).joinToString(",") + "}"
    }

    private data class CombatConfig(val saveFile: String, val outputDir: String, val commandsFile: String?) {
        companion object {
            fun fromArgs(args: Array<String>): CombatConfig {
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
                return CombatConfig(
                    saveFile = values["save-file"] ?: "$output/chat-player-save.json",
                    outputDir = output,
                    commandsFile = values["commands"]
                )
            }
        }
    }
}

private fun combatArrayJson(values: Iterable<String>): String = values.joinToString(prefix = "[", postfix = "]")
private fun combatJson(name: String, value: String?): String = "\"$name\":${if (value == null) "null" else "\"${escapeCombatJson(value)}\""}"
private fun combatJson(name: String, value: Int): String = "\"$name\":$value"
private fun combatJson(name: String, value: Boolean): String = "\"$name\":$value"
private fun escapeCombatJson(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
private fun unescapeCombatJson(value: String): String = value
    .replace("\\n", "\n")
    .replace("\\\"", "\"")
    .replace("\\\\", "\\")
