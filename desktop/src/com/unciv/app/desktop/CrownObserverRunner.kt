package com.unciv.app.desktop

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameStarter
import com.unciv.logic.automation.world.CrownWorldModel
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MirroringType
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
import com.unciv.utils.Log
import java.io.File

/**
 * Small executable testbed for the Unking "Crown" idea.
 *
 * It runs a normal headless Unciv simulation with several autonomous realms.
 * The Crown only watches. At regular checkpoints it records a world-level
 * equilibrium assessment so we can evaluate the representation before giving
 * the Crown any ability to intervene.
 *
 * Run from the headless jar with:
 *
 * java -cp UncivHeadless.jar com.unciv.app.desktop.CrownObserverRunner \
 *   --seed 42017 --realms 4 --max-turns 250 --interval 10 --output crown-results
 */
internal object CrownObserverRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        Log.backend = DesktopLogBackend()
        val config = CrownObserverConfig.fromArgs(args)
        initializeUnciv()

        val gameInfo = GameStarter.startNewGame(createGameSetup(config))
        gameInfo.gameParameters.victoryTypes = ArrayList(gameInfo.ruleset.victories.keys)
        gameInfo.simulateUntilWin = true
        UncivGame.Current.gameInfo = gameInfo

        val outputDir = File(config.outputDir)
        outputDir.mkdirs()
        val observations = File(outputDir, "crown-observations.jsonl")
        observations.writeText("")

        val crown = CrownWorldModel()
        writeObservation(observations, config.seed, "start", crown.observe(gameInfo))

        var target = config.interval
        while (target <= config.maxTurns) {
            gameInfo.simulateMaxTurns = target
            gameInfo.nextTurn()
            val assessment = crown.observe(gameInfo)
            writeObservation(observations, config.seed, "turn-$target", assessment)

            println(
                "turn=${assessment.turn} imbalance=${format(assessment.globalImbalance)} " +
                    "wars=${assessment.activeWars} dominant=${assessment.dominantCiv ?: "none"} " +
                    "distressed=${assessment.distressedCiv ?: "none"}"
            )

            // simulateMaxTurns is an absolute ceiling. If Unciv stopped before
            // our requested checkpoint, a victory or another terminal state has
            // probably ended the simulation, so do not spin on the same turn.
            if (gameInfo.turns < target) break
            target += config.interval
        }

        File(outputDir, "crown-summary.txt").writeText(buildString {
            appendLine("Unking Crown observer")
            appendLine("seed=${config.seed}")
            appendLine("realms=${config.realms}")
            appendLine("requestedMaxTurns=${config.maxTurns}")
            appendLine("finalTurn=${gameInfo.turns}")
            appendLine("interval=${config.interval}")
            appendLine("observations=${observations.absolutePath}")
        })

        println("Crown observer complete -> ${observations.absolutePath}")
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

    private fun createGameSetup(config: CrownObserverConfig): GameSetupInfo {
        val ruleset = RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!
        val realmNations = (1..config.realms).map { index ->
            Nation().apply { name = "Unking Realm $index" }.also { nation ->
                ruleset.nations[nation.name] = nation
            }
        }

        val gameParameters = GameParameters().apply {
            difficulty = "King"
            numberOfCityStates = 0
            speed = Speed.DEFAULT
            noBarbarians = true
            players = ArrayList<Player>().apply {
                realmNations.forEach { add(Player(it)) }
                add(Player(Constants.spectator, PlayerType.Human))
            }
        }

        val mapParameters = MapParameters().apply {
            mapSize = when {
                config.realms <= 2 -> MapSize.Tiny
                config.realms <= 4 -> MapSize.Small
                else -> MapSize.Medium
            }
            noRuins = true
            noNaturalWonders = true
            legendaryStart = true
            strategicBalance = true
            mirroring = if (config.realms == 2) MirroringType.aroundCenterTile else MirroringType.none
            waterThreshold -= 0.1f
            seed = config.seed.toLong()
        }

        return GameSetupInfo(gameParameters, mapParameters)
    }

    private fun writeObservation(
        file: File,
        seed: Int,
        phase: String,
        assessment: com.unciv.logic.automation.world.CrownWorldAssessment
    ) {
        val assessmentJson = assessment.toJson()
        file.appendText(
            "{\"seed\":$seed,\"phase\":\"${escape(phase)}\",\"assessment\":$assessmentJson}\n"
        )
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

    private fun format(value: Double): String = String.format("%.3f", value)
}

private data class CrownObserverConfig(
    val seed: Int = 42017,
    val realms: Int = 4,
    val maxTurns: Int = 250,
    val interval: Int = 10,
    val outputDir: String = "crown-results"
) {
    companion object {
        fun fromArgs(args: Array<String>): CrownObserverConfig {
            val values = LinkedHashMap<String, String>()
            var index = 0
            while (index < args.size) {
                val key = args[index]
                val value = args.getOrNull(index + 1)
                if (key.startsWith("--") && value != null && !value.startsWith("--")) {
                    values[key.removePrefix("--")] = value
                    index += 2
                } else index++
            }

            return CrownObserverConfig(
                seed = values["seed"]?.toIntOrNull() ?: 42017,
                realms = (values["realms"]?.toIntOrNull() ?: 4).coerceIn(2, 8),
                maxTurns = (values["max-turns"]?.toIntOrNull() ?: 250).coerceAtLeast(1),
                interval = (values["interval"]?.toIntOrNull() ?: 10).coerceAtLeast(1),
                outputDir = values["output"] ?: "crown-results"
            )
        }
    }
}
