package com.unciv.app.desktop

import com.unciv.Constants
import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MirroringType
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.Speed
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/**
 * Experimental headless player command surface for ChatGPT-style benchmark play.
 *
 * Commands must use action IDs emitted by legalActions(). Unsupported categories
 * are listed immediately but reject without mutating the game state.
 */
internal object ChatPlayerHarness {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = ChatPlayerConfig.fromArgs(args)
        initializeUnciv()

        val gameInfo = loadOrCreateGame(config)
        UncivGame.Current.gameInfo = gameInfo

        val beforeTurn = gameInfo.turns
        val beforePlayer = gameInfo.currentPlayer
        val actionIds = config.commandsFile?.let { readActionIds(File(it)) } ?: emptyList()
        val results = actionIds.map { applyAction(gameInfo, it) }

        writeOutputs(config, gameInfo, results, beforeTurn, beforePlayer)
        val rejected = results.count { !it.applied }
        println("Chat player harness complete: match=${config.matchId} profile=${config.benchmarkProfile} actions=${results.size} rejected=$rejected output=${config.outputDir}")
        exitProcess(if (results.any { it.error }) 1 else 0)
    }

    private fun initializeUnciv() {
        val game = UncivGame(true)
        UncivGame.Current = game
        UncivGame.Current.settings = GameSettings().apply {
            showTutorials = false
            turnsBetweenAutosaves = 10000
        }
        RulesetCache.loadRulesets(true)
        TileSetCache.loadTileSetConfigs(true)
        SkinCache.loadSkinConfigs(true)
    }

    private fun loadOrCreateGame(config: ChatPlayerConfig): GameInfo {
        val saveFile = File(config.saveFile)
        if (!config.newGame && saveFile.exists() && saveFile.isFile) {
            return UncivFiles.gameInfoFromString(saveFile.readText())
        }
        val setupInfo = createGameSetup(config.seed)
        val gameInfo = GameStarter.startNewGame(setupInfo)
        gameInfo.gameParameters.victoryTypes = ArrayList(gameInfo.ruleset.victories.keys)
        return gameInfo
    }

    private fun createGameSetup(seed: Int): GameSetupInfo {
        val ruleset = RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!

        val chatNation = Nation().apply { name = simulationCiv1 }
        ruleset.nations[simulationCiv1] = chatNation
        val opponentNation = Nation().apply { name = simulationCiv2 }
        ruleset.nations[simulationCiv2] = opponentNation

        val gameParameters = GameParameters().apply {
            difficulty = "King"
            numberOfCityStates = 0
            speed = Speed.DEFAULT
            noBarbarians = true
            players = ArrayList<Player>().apply {
                add(Player(chatNation, PlayerType.Human, "chatgpt"))
                add(Player(opponentNation, PlayerType.AI, "benchmark-opponent"))
            }
        }

        val mapParameters = MapParameters().apply {
            mapSize = MapSize.Tiny
            noRuins = true
            noNaturalWonders = true
            legendaryStart = true
            strategicBalance = true
            mirroring = MirroringType.aroundCenterTile
            waterThreshold -= 0.1f
            this.seed = seed.toLong()
        }

        return GameSetupInfo(gameParameters, mapParameters)
    }

    private fun readActionIds(file: File): List<String> {
        if (!file.exists()) return emptyList()
        val text = file.readText()
        val fromJson = Regex("\"actionId\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .findAll(text)
            .map { unescapeJson(it.groupValues[1]) }
            .toList()
        if (fromJson.isNotEmpty()) return fromJson
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }

    private fun applyAction(gameInfo: GameInfo, actionId: String): ChatActionResult {
        val legal = legalActions(gameInfo).associateBy { it.actionId }
        val legalAction = legal[actionId]
            ?: return ChatActionResult(actionId, false, false, "Rejected: unknown or currently illegal action ID")

        return runCatching {
            when {
                actionId.startsWith("research:") -> applyResearch(gameInfo.currentPlayerCiv, actionId.removePrefix("research:"))
                actionId == "endTurn" -> {
                    gameInfo.nextTurn()
                    "Ended turn and advanced to ${gameInfo.currentPlayer} on turn ${gameInfo.turns}"
                }
                else -> return ChatActionResult(actionId, false, false, "Unsupported action category: ${legalAction.category}")
            }
        }.fold(
            onSuccess = { ChatActionResult(actionId, true, false, it) },
            onFailure = { ChatActionResult(actionId, false, true, "Error while applying action: ${it.message ?: it::class.simpleName}") }
        )
    }

    private fun applyResearch(civ: Civilization, techName: String): String {
        if (!civ.tech.canBeResearched(techName)) return "Rejected: $techName is not researchable"
        civ.tech.techsToResearch.clear()
        civ.tech.techsToResearch.add(techName)
        return "Research set to $techName"
    }

    private fun legalActions(gameInfo: GameInfo): List<ChatLegalAction> {
        val civ = gameInfo.currentPlayerCiv
        val actions = ArrayList<ChatLegalAction>()

        for (tech in gameInfo.ruleset.technologies.values.sortedBy { it.name }) {
            if (civ.tech.canBeResearched(tech.name)) {
                actions += ChatLegalAction("research:${tech.name}", "research", "Research ${tech.name}", true)
            }
        }

        for (city in civ.cities.sortedBy { it.name }) {
            actions += ChatLegalAction(
                "construction:${cityToken(city)}:list",
                "cityConstruction",
                "${city.name}: construction category present but executor not enabled yet",
                false
            )
        }

        for (unit in civ.units.getCivUnits().sortedWith(compareBy<MapUnit> { it.id }.thenBy { it.name })) {
            val unitLabel = "${unit.name} #${unit.id} at ${unit.currentTile.position}"
            actions += ChatLegalAction("unit:${unit.id}:list", "unit", "$unitLabel — unit-specific commands not enabled yet", false)
        }

        actions += ChatLegalAction("automate:economy", "automation", "Economy automation category present but executor not enabled yet", false)
        actions += ChatLegalAction("policy:list", "policy", "Policy choice category present but executor not enabled yet", false)
        actions += ChatLegalAction("diplomacy:list", "diplomacy", "Diplomacy category present but executor not enabled yet", false)
        actions += ChatLegalAction("religion:list", "religion", "Religion category present but executor not enabled yet", false)
        actions += ChatLegalAction("greatPerson:list", "greatPerson", "Great-person category present but executor not enabled yet", false)
        actions += ChatLegalAction("gold:list", "gold", "Gold purchase/spending category present but executor not enabled yet", false)
        actions += ChatLegalAction("endTurn", "turn", "End the current turn", true)

        return actions
    }

    private fun writeOutputs(
        config: ChatPlayerConfig,
        gameInfo: GameInfo,
        results: List<ChatActionResult>,
        beforeTurn: Int,
        beforePlayer: String
    ) {
        val outputDir = File(config.outputDir)
        outputDir.mkdirs()
        File(config.saveFile).parentFile?.mkdirs()
        File(config.saveFile).writeText(UncivFiles.gameInfoToString(gameInfo, forceZip = false, updateChecksum = false))

        val actions = legalActions(gameInfo)
        val scoreboard = scoreboardRow(config, gameInfo, results)
        File(outputDir, "state.json").writeText(stateJson(config, gameInfo, beforeTurn, beforePlayer))
        File(outputDir, "legal-actions.json").writeText(listJson(actions.map { it.toJson() }))
        File(outputDir, "result.json").writeText(resultJson(results, config, scoreboard))
        File(outputDir, "benchmark.json").writeText(benchmarkJson(config))
        File(outputDir, "report.md").writeText(reportMarkdown(gameInfo, actions, results, config, scoreboard))
        appendLine(File(outputDir, "match-log.jsonl"), matchLogJson(config, gameInfo, results, scoreboard))
        appendScoreboard(File(outputDir, "scoreboard.csv"), scoreboard)
    }

    private fun stateJson(config: ChatPlayerConfig, gameInfo: GameInfo, beforeTurn: Int, beforePlayer: String): String {
        val civ = gameInfo.currentPlayerCiv
        val cities = civ.cities.sortedBy { it.name }.map { city ->
            "{" + listOf(
                json("id", cityToken(city)),
                json("name", city.name),
                json("population", city.population.population),
                json("currentConstruction", city.cityConstructions.currentConstructionName()),
                json("health", city.health),
                json("x", city.location.x),
                json("y", city.location.y)
            ).joinToString(",") + "}"
        }
        val units = civ.units.getCivUnits().sortedWith(compareBy<MapUnit> { it.id }.thenBy { it.name }).map { unit ->
            "{" + listOf(
                json("id", unit.id),
                json("name", unit.name),
                json("health", unit.health),
                json("movement", unit.currentMovement.toDouble()),
                json("x", unit.currentTile.position.x),
                json("y", unit.currentTile.position.y),
                json("supportedCommands", false)
            ).joinToString(",") + "}"
        }
        return "{" + listOf(
            json("matchId", config.matchId),
            json("benchmarkProfile", config.benchmarkProfile),
            json("benchmarkVersion", config.benchmarkVersion),
            json("visibleInformationOnly", true),
            json("turnBeforeActions", beforeTurn),
            json("playerBeforeActions", beforePlayer),
            json("turn", gameInfo.turns),
            json("currentPlayer", gameInfo.currentPlayer),
            json("currentTechnology", civ.tech.currentTechnologyName()),
            json("gold", civ.gold),
            json("sciencePerTurn", civ.stats.statsForNextTurn.science.toDouble()),
            json("culturePerTurn", civ.stats.statsForNextTurn.culture.toDouble()),
            json("happiness", civ.getHappiness()),
            "\"knownOpponents\":${knownOpponentsJson(civ)}",
            "\"cities\":${listJson(cities)}",
            "\"units\":${listJson(units)}"
        ).joinToString(",") + "}\n"
    }

    private fun knownOpponentsJson(civ: Civilization): String {
        val known = civ.getKnownCivs()
            .filter { it.isMajorCiv() && it != civ && !it.isSpectator() }
            .sortedBy { it.civName }
            .map { other ->
                val exploredCities = other.cities.count { city -> city.getCenterTile().isExplored(civ) }
                "{" + listOf(
                    json("civ", other.civName),
                    json("atWar", civ.isAtWarWith(other)),
                    json("knownCities", exploredCities),
                    json("isDefeated", other.isDefeated())
                ).joinToString(",") + "}"
            }
        return listJson(known)
    }

    private fun resultJson(results: List<ChatActionResult>, config: ChatPlayerConfig, scoreboard: ChatScoreboardRow): String =
        "{" + listOf(
            json("matchId", config.matchId),
            json("benchmarkProfile", config.benchmarkProfile),
            json("saveFile", config.saveFile),
            "\"scoreboard\":${scoreboard.toJson()}",
            "\"results\":${listJson(results.map { it.toJson() })}"
        ).joinToString(",") + "}\n"

    private fun benchmarkJson(config: ChatPlayerConfig): String =
        "{" + listOf(
            json("matchId", config.matchId),
            json("benchmarkProfile", config.benchmarkProfile),
            json("benchmarkVersion", config.benchmarkVersion),
            json("opponentLabel", config.opponentLabel),
            json("seed", config.seed),
            json("visibleInformationOnly", true),
            json("legalActionIdsRequired", true),
            json("unsupportedActionsMustRejectWithoutMutation", true),
            json("notes", "Stable ChatGPT benchmark player lane for comparing built-in and neural-assisted Unciv AI opponents.")
        ).joinToString(",") + "}\n"

    private fun matchLogJson(config: ChatPlayerConfig, gameInfo: GameInfo, results: List<ChatActionResult>, scoreboard: ChatScoreboardRow): String =
        "{" + listOf(
            json("matchId", config.matchId),
            json("benchmarkProfile", config.benchmarkProfile),
            json("benchmarkVersion", config.benchmarkVersion),
            json("opponentLabel", config.opponentLabel),
            json("turn", gameInfo.turns),
            json("currentPlayer", gameInfo.currentPlayer),
            "\"scoreboard\":${scoreboard.toJson()}",
            "\"results\":${listJson(results.map { it.toJson() })}"
        ).joinToString(",") + "}"

    private fun scoreboardRow(config: ChatPlayerConfig, gameInfo: GameInfo, results: List<ChatActionResult>): ChatScoreboardRow {
        val civ = gameInfo.currentPlayerCiv
        val victoryData = gameInfo.victoryData
        return ChatScoreboardRow(
            matchId = config.matchId,
            benchmarkProfile = config.benchmarkProfile,
            benchmarkVersion = config.benchmarkVersion,
            opponentLabel = config.opponentLabel,
            seed = config.seed,
            turn = gameInfo.turns,
            currentPlayer = gameInfo.currentPlayer,
            winner = victoryData?.winningCiv,
            victoryType = victoryData?.victoryType,
            actionCount = results.size,
            rejectedCount = results.count { !it.applied },
            errorCount = results.count { it.error },
            cities = civ.cities.size,
            population = civ.cities.sumOf { it.population.population },
            units = civ.units.getCivUnitsSize(),
            techs = civ.tech.getNumberOfTechsResearched(),
            gold = civ.gold,
            science = civ.stats.statsForNextTurn.science.toDouble(),
            culture = civ.stats.statsForNextTurn.culture.toDouble(),
            happiness = civ.getHappiness()
        )
    }

    private fun reportMarkdown(gameInfo: GameInfo, actions: List<ChatLegalAction>, results: List<ChatActionResult>, config: ChatPlayerConfig, scoreboard: ChatScoreboardRow): String = buildString {
        appendLine("# Chat Player Harness")
        appendLine()
        appendLine("- Match ID: `${config.matchId}`")
        appendLine("- Benchmark profile: `${config.benchmarkProfile}`")
        appendLine("- Benchmark version: `${config.benchmarkVersion}`")
        appendLine("- Opponent: `${config.opponentLabel}`")
        appendLine("- Save file: `${config.saveFile}`")
        appendLine("- Turn: ${gameInfo.turns}")
        appendLine("- Current player: ${gameInfo.currentPlayer}")
        appendLine("- Winner: ${scoreboard.winner ?: "none"}")
        appendLine("- Victory type: ${scoreboard.victoryType ?: "none"}")
        appendLine("- Actions applied: ${results.count { it.applied }}")
        appendLine("- Actions rejected: ${results.count { !it.applied }}")
        appendLine("- Legal supported actions: ${actions.count { it.supported }}")
        appendLine("- Legal unsupported placeholders: ${actions.count { !it.supported }}")
        appendLine()
        appendLine("## Visible-info rule")
        appendLine()
        appendLine("The state export is intended to show the controlled civ plus known opponents only. Hidden map, enemy queues, and model internals are not exported in this benchmark lane.")
        appendLine()
        appendLine("## Scoreboard")
        appendLine()
        appendLine("- Cities: ${scoreboard.cities}")
        appendLine("- Population: ${scoreboard.population}")
        appendLine("- Units: ${scoreboard.units}")
        appendLine("- Techs: ${scoreboard.techs}")
        appendLine("- Gold: ${scoreboard.gold}")
        appendLine("- Science/turn: ${scoreboard.science}")
        appendLine("- Culture/turn: ${scoreboard.culture}")
        appendLine("- Happiness: ${scoreboard.happiness}")
        appendLine()
        appendLine("## Applied/rejected commands")
        appendLine()
        if (results.isEmpty()) appendLine("No commands supplied. Use `legal-actions.json` to choose exact action IDs.")
        for (result in results) appendLine("- `${result.actionId}`: ${result.message}")
        appendLine()
        appendLine("## First legal actions")
        appendLine()
        for (action in actions.take(30)) appendLine("- `${action.actionId}` — ${action.label}${if (action.supported) "" else " _(unsupported)_"}")
    }

    private fun appendLine(file: File, line: String) {
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
    }

    private fun appendScoreboard(file: File, row: ChatScoreboardRow) {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.writeText(ChatScoreboardRow.csvHeader() + "\n")
        file.appendText(row.toCsv() + "\n")
    }

    private fun cityToken(city: com.unciv.logic.city.City): String =
        city.id.takeIf { it.isNotBlank() && it != Constants.NO_ID.toString() } ?: city.name

    private fun listJson(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]")

    private fun json(name: String, value: String?): String =
        "\"$name\":${if (value == null) "null" else "\"${escapeJson(value)}\""}"
    private fun json(name: String, value: Int): String = "\"$name\":$value"
    private fun json(name: String, value: Double): String = "\"$name\":$value"
    private fun json(name: String, value: Boolean): String = "\"$name\":$value"

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

    private fun unescapeJson(value: String): String = value
        .replace("\\n", "\n")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}

private data class ChatLegalAction(val actionId: String, val category: String, val label: String, val supported: Boolean) {
    fun toJson(): String = "{" + listOf(
        json("actionId", actionId),
        json("category", category),
        json("label", label),
        json("supported", supported)
    ).joinToString(",") + "}"
}

private data class ChatActionResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
    fun toJson(): String = "{" + listOf(
        json("actionId", actionId),
        json("applied", applied),
        json("error", error),
        json("message", message)
    ).joinToString(",") + "}"
}

private data class ChatScoreboardRow(
    val matchId: String,
    val benchmarkProfile: String,
    val benchmarkVersion: String,
    val opponentLabel: String,
    val seed: Int,
    val turn: Int,
    val currentPlayer: String,
    val winner: String?,
    val victoryType: String?,
    val actionCount: Int,
    val rejectedCount: Int,
    val errorCount: Int,
    val cities: Int,
    val population: Int,
    val units: Int,
    val techs: Int,
    val gold: Int,
    val science: Double,
    val culture: Double,
    val happiness: Int
) {
    fun toJson(): String = "{" + listOf(
        json("matchId", matchId),
        json("benchmarkProfile", benchmarkProfile),
        json("benchmarkVersion", benchmarkVersion),
        json("opponentLabel", opponentLabel),
        json("seed", seed),
        json("turn", turn),
        json("currentPlayer", currentPlayer),
        json("winner", winner),
        json("victoryType", victoryType),
        json("actionCount", actionCount),
        json("rejectedCount", rejectedCount),
        json("errorCount", errorCount),
        json("cities", cities),
        json("population", population),
        json("units", units),
        json("techs", techs),
        json("gold", gold),
        json("science", science),
        json("culture", culture),
        json("happiness", happiness)
    ).joinToString(",") + "}"

    fun toCsv(): String = listOf(
        matchId, benchmarkProfile, benchmarkVersion, opponentLabel, seed, turn, currentPlayer,
        winner ?: "", victoryType ?: "", actionCount, rejectedCount, errorCount, cities, population, units,
        techs, gold, science, culture, happiness
    ).joinToString(",") { csv(it.toString()) }

    companion object {
        fun csvHeader(): String = listOf(
            "matchId", "benchmarkProfile", "benchmarkVersion", "opponentLabel", "seed", "turn", "currentPlayer",
            "winner", "victoryType", "actionCount", "rejectedCount", "errorCount", "cities", "population",
            "units", "techs", "gold", "science", "culture", "happiness"
        ).joinToString(",")
    }
}

private data class ChatPlayerConfig(
    val seed: Int = 42017,
    val outputDir: String = "chat-player-output",
    val saveFile: String = "chat-player-output/chat-player-save.json",
    val commandsFile: String? = null,
    val newGame: Boolean = false,
    val matchId: String = "chat-benchmark-42017",
    val benchmarkProfile: String = "ChatGPT-Benchmark-Player",
    val benchmarkVersion: String = "v1",
    val opponentLabel: String = "BuiltInAI-or-NeuralAI"
) {
    companion object {
        fun fromArgs(args: Array<String>): ChatPlayerConfig {
            val values = parseArgs(args.filter { it != "--chat-player" }.toTypedArray())
            val seed = values["seed"]?.toIntOrNull() ?: 42017
            return ChatPlayerConfig(
                seed = seed,
                outputDir = values["output"] ?: "chat-player-output",
                saveFile = values["save-file"] ?: "chat-player-output/chat-player-save.json",
                commandsFile = values["commands"],
                newGame = values["new-game"] == "true" || values["reset"] == "true",
                matchId = values["match-id"] ?: "chat-benchmark-$seed",
                benchmarkProfile = values["benchmark-profile"] ?: "ChatGPT-Benchmark-Player",
                benchmarkVersion = values["benchmark-version"] ?: "v1",
                opponentLabel = values["opponent-label"] ?: "BuiltInAI-or-NeuralAI"
            )
        }

        private fun parseArgs(args: Array<String>): Map<String, String> {
            val values = LinkedHashMap<String, String>()
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                if (!arg.startsWith("--")) {
                    index++
                    continue
                }
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
            return values
        }
    }
}

private fun json(name: String, value: String?): String =
    "\"$name\":${if (value == null) "null" else "\"${escapeJsonShared(value)}\""}"
private fun json(name: String, value: Int): String = "\"$name\":$value"
private fun json(name: String, value: Double): String = "\"$name\":$value"
private fun json(name: String, value: Boolean): String = "\"$name\":$value"
private fun escapeJsonShared(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
