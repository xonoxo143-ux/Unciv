package com.unciv.app.desktop

import com.unciv.Constants
import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Explicit citizen tile and specialist assignment controls for the chat benchmark. */
internal object ChatCitizenActions {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = CitizenConfig.fromArgs(args)
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
        println("Citizen chat actions complete: actions=${results.size} applied=${results.count { it.applied }} rejected=${results.count { !it.applied }}")
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
            .map { unescapeCitizenJson(it.groupValues[1]) }
            .toList()
        if (fromJson.isNotEmpty()) return fromJson
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }

    private fun applyAction(civ: Civilization, actionId: String): CitizenResult {
        val legal = legalActions(civ).associateBy { it.actionId }
        val action = legal[actionId]
            ?: return CitizenResult(actionId, false, false, "Rejected: unknown or currently illegal citizen action")
        return runCatching {
            when (action.category) {
                "workTile" -> applyWorkTile(civ, actionId)
                "unworkTile" -> applyUnworkTile(civ, actionId)
                "lockTile" -> applyLockTile(civ, actionId, true)
                "unlockTile" -> applyLockTile(civ, actionId, false)
                "addSpecialist" -> applySpecialist(civ, actionId, 1)
                "removeSpecialist" -> applySpecialist(civ, actionId, -1)
                else -> CitizenOutcome(false, "Rejected: unsupported citizen action category")
            }
        }.fold(
            onSuccess = { CitizenResult(actionId, it.applied, false, it.message) },
            onFailure = { CitizenResult(actionId, false, true, "Error while applying citizen action: ${it.message ?: it::class.simpleName}") }
        )
    }

    private fun applyWorkTile(civ: Civilization, actionId: String): CitizenOutcome {
        val (city, tile) = parseCityTile(civ, actionId, "work") ?: return CitizenOutcome(false, "Rejected: malformed or invalid work-tile action")
        if (!canWorkTile(city, tile)) return CitizenOutcome(false, "Rejected: ${city.name} cannot work tile ${tile.position}")
        city.workedTiles.add(tile.position)
        city.cityStats.update(updateCivStats = true)
        return CitizenOutcome(true, "Assigned one citizen from ${city.name} to tile ${tile.position}")
    }

    private fun applyUnworkTile(civ: Civilization, actionId: String): CitizenOutcome {
        val (city, tile) = parseCityTile(civ, actionId, "unwork") ?: return CitizenOutcome(false, "Rejected: malformed or invalid unwork-tile action")
        if (!city.isWorked(tile)) return CitizenOutcome(false, "Rejected: ${city.name} is not working tile ${tile.position}")
        city.population.stopWorkingTile(tile.position)
        city.cityStats.update(updateCivStats = true)
        return CitizenOutcome(true, "Removed citizen from tile ${tile.position} in ${city.name}")
    }

    private fun applyLockTile(civ: Civilization, actionId: String, lock: Boolean): CitizenOutcome {
        val command = if (lock) "lock" else "unlock"
        val (city, tile) = parseCityTile(civ, actionId, command) ?: return CitizenOutcome(false, "Rejected: malformed or invalid $command action")
        if (!city.isWorked(tile)) return CitizenOutcome(false, "Rejected: only worked tiles can be locked or unlocked")
        if (lock) city.lockedTiles.add(tile.position) else city.lockedTiles.remove(tile.position)
        return CitizenOutcome(true, "${if (lock) "Locked" else "Unlocked"} tile ${tile.position} in ${city.name}")
    }

    private fun applySpecialist(civ: Civilization, actionId: String, delta: Int): CitizenOutcome {
        val command = if (delta > 0) "addSpecialist" else "removeSpecialist"
        val parts = actionId.split(":", limit = 4)
        if (parts.size != 4 || parts[2] != command) return CitizenOutcome(false, "Rejected: malformed specialist action")
        val city = findCity(civ, parts[1]) ?: return CitizenOutcome(false, "Rejected: city ${parts[1]} not found")
        val specialist = parts[3]
        val max = city.population.getMaxSpecialists()[specialist]
        val current = city.population.specialistAllocations[specialist]
        if (delta > 0 && (city.population.getFreePopulation() <= 0 || current >= max))
            return CitizenOutcome(false, "Rejected: no free citizen or open $specialist slot")
        if (delta < 0 && current <= 0)
            return CitizenOutcome(false, "Rejected: no $specialist is assigned")
        city.manualSpecialists = true
        city.population.specialistAllocations.add(specialist, delta)
        city.cityStats.update(updateCivStats = true)
        return CitizenOutcome(true, "${if (delta > 0) "Assigned" else "Removed"} $specialist in ${city.name}; count $current -> ${city.population.specialistAllocations[specialist]}")
    }

    private fun legalActions(civ: Civilization): List<CitizenLegalAction> {
        val actions = ArrayList<CitizenLegalAction>()
        for (city in civ.cities.sortedBy { it.name }) {
            if (city.isPuppet || city.isInResistance()) continue
            val cityId = cityToken(city)
            if (city.population.getFreePopulation() > 0) {
                for (tile in city.getWorkableTiles()
                    .filter { canWorkTile(city, it) }
                    .sortedWith(compareBy({ it.position.x }, { it.position.y }))) {
                    actions += CitizenLegalAction(
                        "city:$cityId:work:${tile.position.x},${tile.position.y}",
                        "workTile",
                        "Work tile ${tile.position} in ${city.name}"
                    )
                }
            }
            for (tile in city.getWorkedTiles().sortedWith(compareBy({ it.position.x }, { it.position.y }))) {
                actions += CitizenLegalAction(
                    "city:$cityId:unwork:${tile.position.x},${tile.position.y}",
                    "unworkTile",
                    "Stop working tile ${tile.position} in ${city.name}"
                )
                val locked = tile.position in city.lockedTiles
                actions += CitizenLegalAction(
                    "city:$cityId:${if (locked) "unlock" else "lock"}:${tile.position.x},${tile.position.y}",
                    if (locked) "unlockTile" else "lockTile",
                    "${if (locked) "Unlock" else "Lock"} tile ${tile.position} in ${city.name}"
                )
            }
            val maxSpecialists = city.population.getMaxSpecialists()
            for ((specialist, max) in maxSpecialists.toSortedMap()) {
                val current = city.population.specialistAllocations[specialist]
                if (city.population.getFreePopulation() > 0 && current < max) {
                    actions += CitizenLegalAction(
                        "city:$cityId:addSpecialist:$specialist",
                        "addSpecialist",
                        "Assign $specialist in ${city.name} ($current/$max)"
                    )
                }
                if (current > 0) {
                    actions += CitizenLegalAction(
                        "city:$cityId:removeSpecialist:$specialist",
                        "removeSpecialist",
                        "Remove $specialist in ${city.name} ($current/$max)"
                    )
                }
            }
        }
        return actions
    }

    private fun canWorkTile(city: City, tile: Tile): Boolean =
        !tile.isCityCenter() &&
            tile.getOwner() == city.civ &&
            tile in city.tilesInRange &&
            tile.getWorkingCity() == null &&
            !tile.isBlockaded() &&
            city.population.getFreePopulation() > 0

    private fun parseCityTile(civ: Civilization, actionId: String, command: String): Pair<City, Tile>? {
        val parts = actionId.split(":", limit = 4)
        if (parts.size != 4 || parts[2] != command) return null
        val city = findCity(civ, parts[1]) ?: return null
        val coordinates = parts[3].split(",", limit = 2)
        if (coordinates.size != 2) return null
        val x = coordinates[0].toIntOrNull() ?: return null
        val y = coordinates[1].toIntOrNull() ?: return null
        val tile = city.tileMap.getOrNull(x, y) ?: return null
        return city to tile
    }

    private fun findCity(civ: Civilization, token: String): City? =
        civ.cities.firstOrNull { cityToken(it) == token || it.name == token }

    private fun cityToken(city: City): String =
        city.id.takeIf { it.isNotBlank() && it != Constants.NO_ID.toString() } ?: city.name

    private fun writeOutputs(config: CitizenConfig, gameInfo: GameInfo, results: List<CitizenResult>) {
        val outputDir = File(config.outputDir).apply { mkdirs() }
        val legal = legalActions(gameInfo.currentPlayerCiv)
        File(outputDir, "citizen-legal-actions.json").writeText(citizenArrayJson(legal.map { it.toJson() }) + "\n")
        File(outputDir, "citizen-result.json").writeText(
            "{" + listOf(
                citizenJson("turn", gameInfo.turns),
                citizenJson("currentPlayer", gameInfo.currentPlayer),
                citizenJson("automationUsed", false),
                "\"results\":${citizenArrayJson(results.map { it.toJson() })}"
            ).joinToString(",") + "}\n"
        )
    }

    private data class CitizenOutcome(val applied: Boolean, val message: String)

    private data class CitizenLegalAction(val actionId: String, val category: String, val label: String) {
        fun toJson(): String = "{" + listOf(
            citizenJson("actionId", actionId),
            citizenJson("category", category),
            citizenJson("label", label)
        ).joinToString(",") + "}"
    }

    private data class CitizenResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson(): String = "{" + listOf(
            citizenJson("actionId", actionId),
            citizenJson("applied", applied),
            citizenJson("error", error),
            citizenJson("message", message)
        ).joinToString(",") + "}"
    }

    private data class CitizenConfig(val saveFile: String, val outputDir: String, val commandsFile: String?) {
        companion object {
            fun fromArgs(args: Array<String>): CitizenConfig {
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
                return CitizenConfig(
                    saveFile = values["save-file"] ?: "$output/chat-player-save.json",
                    outputDir = output,
                    commandsFile = values["commands"]
                )
            }
        }
    }
}

private fun citizenArrayJson(values: Iterable<String>): String = values.joinToString(prefix = "[", postfix = "]")
private fun citizenJson(name: String, value: String?): String = "\"$name\":${if (value == null) "null" else "\"${escapeCitizenJson(value)}\""}"
private fun citizenJson(name: String, value: Int): String = "\"$name\":$value"
private fun citizenJson(name: String, value: Boolean): String = "\"$name\":$value"
private fun escapeCitizenJson(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
private fun unescapeCitizenJson(value: String): String = value
    .replace("\\n", "\n")
    .replace("\\\"", "\"")
    .replace("\\\\", "\\")
