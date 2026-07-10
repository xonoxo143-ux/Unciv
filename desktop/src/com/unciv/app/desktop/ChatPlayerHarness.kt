package com.unciv.app.desktop

import com.unciv.Constants
import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MirroringType
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.Speed
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/**
 * Experimental headless player command surface for ChatGPT-style turn play.
 *
 * The rule is strict: commands should use action IDs emitted by legalActions().
 * Unsupported categories are listed immediately but reject without mutating the
 * game state. This gives us a broad API shape without risking silent save poison.
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
        println("Chat player harness complete: actions=${results.size} rejected=$rejected output=${config.outputDir}")
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
        val ruleset = RulesetCache[GameParameters().baseRuleset] ?: RulesetCache.getVanillaRuleset()

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
                add(Player(opponentNation, PlayerType.AI))
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
                actionId.startsWith("construction:") -> applyConstruction(gameInfo.currentPlayerCiv, actionId)
                actionId == "automate:economy" -> {
                    NextTurnAutomation.automateCities(gameInfo.currentPlayerCiv)
                    "Automated city/economy choices for ${gameInfo.currentPlayer}"
                }
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

    private fun applyConstruction(civ: Civilization, actionId: String): String {
        val parts = actionId.split(":", limit = 3)
        if (parts.size != 3) return "Rejected: malformed construction action ID"
        val cityId = parts[1]
        val constructionName = parts[2]
        val city = civ.cities.firstOrNull { cityToken(it) == cityId }
            ?: return "Rejected: city $cityId not found"
        val construction = constructionByName(city, constructionName)
            ?: return "Rejected: construction $constructionName not found"
        if (!construction.isBuildable(city.cityConstructions)) return "Rejected: $constructionName is not buildable in ${city.name}"
        city.cityConstructions.setCurrentConstruction(constructionName)
        return "${city.name} construction set to $constructionName"
    }

    private fun legalActions(gameInfo: GameInfo): List<ChatLegalAction> {
        val civ = gameInfo.currentPlayerCiv
        val actions = ArrayList<ChatLegalAction>()

        for (tech in gameInfo.ruleset.technologies.values.sortedBy { it.name }) {
            if (civ.tech.canBeResearched(tech.name)) {
                actions += ChatLegalAction(
                    actionId = "research:${tech.name}",
                    category = "research",
                    label = "Research ${tech.name}",
                    supported = true
                )
            }
        }

        for (city in civ.cities.sortedBy { it.name }) {
            val cityId = cityToken(city)
            for (construction in buildableConstructions(city).sortedBy { it.name }) {
                actions += ChatLegalAction(
                    actionId = "construction:$cityId:${construction.name}",
                    category = "cityConstruction",
                    label = "${city.name}: build ${construction.name}",
                    supported = true
                )
            }
        }

        if (civ.cities.isNotEmpty()) {
            actions += ChatLegalAction("automate:economy", "automation", "Let built-in AI handle city/economy choices once", true)
        }

        for (unit in civ.units.getCivUnits().sortedWith(compareBy<MapUnit> { it.id }.thenBy { it.name })) {
            val unitLabel = "${unit.name} #${unit.id} at ${unit.currentTile.position}"
            actions += ChatLegalAction("unit:${unit.id}:list", "unit", "$unitLabel — unit-specific commands not enabled yet", false)
        }

        actions += ChatLegalAction("policy:list", "policy", "Policy choice category present but executor not enabled yet", false)
        actions += ChatLegalAction("diplomacy:list", "diplomacy", "Diplomacy category present but executor not enabled yet", false)
        actions += ChatLegalAction("religion:list", "religion", "Religion category present but executor not enabled yet", false)
        actions += ChatLegalAction("greatPerson:list", "greatPerson", "Great-person category present but executor not enabled yet", false)
        actions += ChatLegalAction("gold:list", "gold", "Gold purchase/spending category present but executor not enabled yet", false)
        actions += ChatLegalAction("endTurn", "turn", "End the current turn", true)

        return actions
    }

    private fun buildableConstructions(city: com.unciv.logic.city.City): List<IConstruction> {
        val constructions = ArrayList<IConstruction>()
        for (building in city.getRuleset().buildings.values) {
            if (building.isBuildable(city.cityConstructions)) constructions += building
        }
        for (unit in city.getRuleset().units.values) {
            if (unit.isBuildable(city.cityConstructions)) constructions += unit
        }
        return constructions
    }

    private fun constructionByName(city: com.unciv.logic.city.City, name: String): IConstruction? =
        city.getRuleset().buildings[name] ?: city.getRuleset().units[name]

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
        File(outputDir, "state.json").writeText(stateJson(gameInfo, beforeTurn, beforePlayer))
        File(outputDir, "legal-actions.json").writeText(listJson(actions.map { it.toJson() }))
        File(outputDir, "result.json").writeText(resultJson(results, config))
        File(outputDir, "report.md").writeText(reportMarkdown(gameInfo, actions, results, config))
    }

    private fun stateJson(gameInfo: GameInfo, beforeTurn: Int, beforePlayer: String): String {
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
            json("turnBeforeActions", beforeTurn),
            json("playerBeforeActions", beforePlayer),
            json("turn", gameInfo.turns),
            json("currentPlayer", gameInfo.currentPlayer),
            json("currentTechnology", civ.tech.currentTechnologyName()),
            json("gold", civ.gold),
            json("sciencePerTurn", civ.stats.statsForNextTurn.science.toDouble()),
            json("culturePerTurn", civ.stats.statsForNextTurn.culture.toDouble()),
            json("happiness", civ.getHappiness()),
            "\"cities\":${listJson(cities)}",
            "\"units\":${listJson(units)}"
        ).joinToString(",") + "}\n"
    }

    private fun resultJson(results: List<ChatActionResult>, config: ChatPlayerConfig): String =
        "{" + listOf(
            json("saveFile", config.saveFile),
            "\"results\":${listJson(results.map { it.toJson() })}"
        ).joinToString(",") + "}\n"

    private fun reportMarkdown(
        gameInfo: GameInfo,
        actions: List<ChatLegalAction>,
        results: List<ChatActionResult>,
        config: ChatPlayerConfig
    ): String = buildString {
        appendLine("# Chat Player Harness")
        appendLine()
        appendLine("- Save file: `${config.saveFile}`")
        appendLine("- Turn: ${gameInfo.turns}")
        appendLine("- Current player: ${gameInfo.currentPlayer}")
        appendLine("- Actions applied: ${results.count { it.applied }}")
        appendLine("- Actions rejected: ${results.count { !it.applied }}")
        appendLine("- Legal supported actions: ${actions.count { it.supported }}")
        appendLine("- Legal unsupported placeholders: ${actions.count { !it.supported }}")
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

private data class ChatLegalAction(
    val actionId: String,
    val category: String,
    val label: String,
    val supported: Boolean
) {
    fun toJson(): String = "{" + listOf(
        json("actionId", actionId),
        json("category", category),
        json("label", label),
        json("supported", supported)
    ).joinToString(",") + "}"
}

private data class ChatActionResult(
    val actionId: String,
    val applied: Boolean,
    val error: Boolean,
    val message: String
) {
    fun toJson(): String = "{" + listOf(
        json("actionId", actionId),
        json("applied", applied),
        json("error", error),
        json("message", message)
    ).joinToString(",") + "}"
}

private data class ChatPlayerConfig(
    val seed: Int = 42017,
    val outputDir: String = "chat-player-output",
    val saveFile: String = "chat-player-output/chat-player-save.json",
    val commandsFile: String? = null,
    val newGame: Boolean = false
) {
    companion object {
        fun fromArgs(args: Array<String>): ChatPlayerConfig {
            val values = parseArgs(args.filter { it != "--chat-player" }.toTypedArray())
            return ChatPlayerConfig(
                seed = values["seed"]?.toIntOrNull() ?: 42017,
                outputDir = values["output"] ?: "chat-player-output",
                saveFile = values["save-file"] ?: "chat-player-output/chat-player-save.json",
                commandsFile = values["commands"],
                newGame = values["new-game"] == "true" || values["reset"] == "true"
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

private fun json(name: String, value: String): String = "\"$name\":\"${escapeJsonShared(value)}\""
private fun json(name: String, value: Boolean): String = "\"$name\":$value"
private fun escapeJsonShared(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
