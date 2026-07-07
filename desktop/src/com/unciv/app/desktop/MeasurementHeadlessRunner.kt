package com.unciv.app.desktop

import com.unciv.Constants
import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MirroringType
import com.unciv.logic.simulation.SimulationStep
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
import kotlin.time.ExperimentalTime

/**
 * Minimal no-UI measurement runner for chat/GitHub headless experiments.
 *
 * This intentionally starts small: sequential AI-only simulations, CSV/JSONL output,
 * and a portable jar entry point. Keep Android/APK work out of this path.
 */
internal object MeasurementHeadlessRunner {

    @ExperimentalTime
    @JvmStatic
    fun main(args: Array<String>) {
        Log.backend = DesktopLogBackend()

        val config = MeasurementConfig.fromArgs(args)
        val game = UncivGame(true)
        UncivGame.Current = game
        UncivGame.Current.settings = GameSettings().apply {
            showTutorials = false
            turnsBetweenAutosaves = 10000
        }

        RulesetCache.loadRulesets(true)
        TileSetCache.loadTileSetConfigs(true)
        SkinCache.loadSkinConfigs(true)

        val results = runGames(config)
        MeasurementResultWriter(config).write(results)
        println("Measurement complete: ${results.size} games -> ${config.outputDir}")
    }

    private fun runGames(config: MeasurementConfig): List<MeasuredGame> {
        val measuredGames = ArrayList<MeasuredGame>()
        val outputDir = File(config.outputDir)
        outputDir.mkdirs()

        repeat(config.games) { index ->
            val seed = config.seedStart + index
            val startedAt = System.currentTimeMillis()
            val result = runOneGame(seed, config)
            val durationMs = System.currentTimeMillis() - startedAt
            val measured = result.copy(index = index + 1, durationMs = durationMs)
            measuredGames += measured
            println("${measured.index}/${config.games} seed=$seed winner=${measured.winner ?: "DRAW"} victory=${measured.victoryType ?: "none"} turns=${measured.turns} durationMs=$durationMs")
        }

        return measuredGames
    }

    private fun runOneGame(seed: Int, config: MeasurementConfig): MeasuredGame {
        return try {
            val setupInfo = createGameSetup(seed)
            val gameInfo = GameStarter.startNewGame(setupInfo)
            gameInfo.gameParameters.victoryTypes = ArrayList(gameInfo.ruleset.victories.keys)
            UncivGame.Current.gameInfo = gameInfo

            val step = SimulationStep(gameInfo, config.statTurns)
            gameInfo.simulateUntilWin = true

            for (turn in config.statTurns.sorted()) {
                if (turn >= config.maxTurns) continue
                gameInfo.simulateMaxTurns = turn
                gameInfo.nextTurn()
                step.update(gameInfo)
                if (step.victoryType != null) break
                step.saveTurnStats(gameInfo)
            }

            step.update(gameInfo)
            if (step.victoryType == null) {
                gameInfo.simulateMaxTurns = config.maxTurns
                gameInfo.nextTurn()
                step.update(gameInfo)
            }

            if (step.victoryType != null) {
                step.winner = step.currentPlayer
                step.saveTurnStats(gameInfo)
            }

            MeasuredGame(
                seed = seed,
                turns = step.turns,
                winner = step.winner,
                victoryType = step.victoryType,
                currentPlayer = step.currentPlayer,
                crashed = false,
                crashMessage = null
            )
        } catch (throwable: Throwable) {
            MeasuredGame(
                seed = seed,
                turns = -1,
                winner = null,
                victoryType = null,
                currentPlayer = null,
                crashed = true,
                crashMessage = throwable.stackTraceToString()
            )
        }
    }

    private fun createGameSetup(seed: Int): GameSetupInfo {
        val ruleset = RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!

        val simulationNation1 = Nation().apply { name = simulationCiv1 }
        ruleset.nations[simulationCiv1] = simulationNation1
        val simulationNation2 = Nation().apply { name = simulationCiv2 }
        ruleset.nations[simulationCiv2] = simulationNation2

        val gameParameters = GameParameters().apply {
            difficulty = "King"
            numberOfCityStates = 0
            speed = Speed.DEFAULT
            noBarbarians = true
            players = ArrayList<Player>().apply {
                add(Player(simulationNation1))
                add(Player(simulationNation2))
                add(Player(Constants.spectator, PlayerType.Human))
            }
        }
        gameParameters.players.last().setNationTransient(ruleset)

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
}

private data class MeasurementConfig(
    val games: Int = 5,
    val maxTurns: Int = 500,
    val seedStart: Int = 42017,
    val outputDir: String = "measurement-results",
    val statTurns: List<Int> = listOf(50, 100, 150, 200)
) {
    companion object {
        fun fromArgs(args: Array<String>): MeasurementConfig {
            val argMap = args.toList().windowed(2, 1)
                .filter { it[0].startsWith("--") }
                .associate { it[0].removePrefix("--") to it[1] }

            val fileConfig = argMap["config"]?.let { fromFile(File(it)) } ?: MeasurementConfig()
            return fileConfig.copy(
                games = argMap["games"]?.toIntOrNull() ?: fileConfig.games,
                maxTurns = argMap["max-turns"]?.toIntOrNull() ?: fileConfig.maxTurns,
                seedStart = argMap["seed-start"]?.toIntOrNull() ?: fileConfig.seedStart,
                outputDir = argMap["output"] ?: fileConfig.outputDir
            )
        }

        private fun fromFile(file: File): MeasurementConfig {
            if (!file.exists()) return MeasurementConfig()
            val text = file.readText()
            return MeasurementConfig(
                games = intField(text, "games") ?: 5,
                maxTurns = intField(text, "maxTurns") ?: 500,
                seedStart = intField(text, "seedStart") ?: 42017,
                outputDir = stringField(text, "outputDir") ?: "measurement-results",
                statTurns = intListField(text, "statTurns") ?: listOf(50, 100, 150, 200)
            )
        }

        private fun intField(text: String, name: String): Int? =
            Regex("\\\"$name\\\"\\s*:\\s*(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()

        private fun stringField(text: String, name: String): String? =
            Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(text)?.groupValues?.get(1)

        private fun intListField(text: String, name: String): List<Int>? {
            val raw = Regex("\\\"$name\\\"\\s*:\\s*\\[([^]]*)]").find(text)?.groupValues?.get(1) ?: return null
            return raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        }
    }
}

private data class MeasuredGame(
    val index: Int = 0,
    val seed: Int,
    val turns: Int,
    val winner: String?,
    val victoryType: String?,
    val currentPlayer: String?,
    val durationMs: Long = 0,
    val crashed: Boolean,
    val crashMessage: String?
)

private class MeasurementResultWriter(private val config: MeasurementConfig) {
    private val outputDir = File(config.outputDir)

    fun write(results: List<MeasuredGame>) {
        outputDir.mkdirs()
        writeSummaryCsv(results)
        writeGamesJsonl(results)
        writeCrashLog(results)
        writeReport(results)
    }

    private fun writeSummaryCsv(results: List<MeasuredGame>) {
        val lines = ArrayList<String>()
        lines += "index,seed,winner,victory_type,turns,duration_ms,crashed"
        for (result in results) {
            lines += listOf(
                result.index.toString(),
                result.seed.toString(),
                csv(result.winner ?: "DRAW"),
                csv(result.victoryType ?: ""),
                result.turns.toString(),
                result.durationMs.toString(),
                result.crashed.toString()
            ).joinToString(",")
        }
        File(outputDir, "summary.csv").writeText(lines.joinToString("\n") + "\n")
    }

    private fun writeGamesJsonl(results: List<MeasuredGame>) {
        File(outputDir, "games.jsonl").writeText(
            results.joinToString("\n") { result ->
                "{" + listOf(
                    json("index", result.index),
                    json("seed", result.seed),
                    json("winner", result.winner),
                    json("victoryType", result.victoryType),
                    json("turns", result.turns),
                    json("durationMs", result.durationMs),
                    json("crashed", result.crashed)
                ).joinToString(",") + "}"
            } + "\n"
        )
    }

    private fun writeCrashLog(results: List<MeasuredGame>) {
        File(outputDir, "crashes.jsonl").writeText(
            results.filter { it.crashed }.joinToString("\n") { result ->
                "{" + listOf(
                    json("index", result.index),
                    json("seed", result.seed),
                    json("crashMessage", result.crashMessage)
                ).joinToString(",") + "}"
            } + "\n"
        )
    }

    private fun writeReport(results: List<MeasuredGame>) {
        val completed = results.count { !it.crashed }
        val crashes = results.count { it.crashed }
        val draws = results.count { !it.crashed && it.winner == null }
        val winners = results.filter { !it.crashed && it.winner != null }.groupingBy { it.winner!! }.eachCount()
        val averageTurns = results.filter { !it.crashed && it.turns >= 0 }.map { it.turns }.average()
        val averageDuration = results.filter { !it.crashed }.map { it.durationMs }.average()

        val text = buildString {
            appendLine("# Unciv Headless Measurement Report")
            appendLine()
            appendLine("## Config")
            appendLine()
            appendLine("- Games requested: ${config.games}")
            appendLine("- Max turns: ${config.maxTurns}")
            appendLine("- Seed start: ${config.seedStart}")
            appendLine("- Stat turns: ${config.statTurns.joinToString()}")
            appendLine()
            appendLine("## Results")
            appendLine()
            appendLine("- Completed: $completed")
            appendLine("- Crashes: $crashes")
            appendLine("- Draws: $draws")
            appendLine("- Average turns: ${if (averageTurns.isNaN()) "n/a" else String.format("%.1f", averageTurns)}")
            appendLine("- Average duration ms: ${if (averageDuration.isNaN()) "n/a" else String.format("%.1f", averageDuration)}")
            appendLine()
            appendLine("## Winners")
            appendLine()
            if (winners.isEmpty()) appendLine("No winners recorded.")
            else winners.entries.sortedByDescending { it.value }.forEach { appendLine("- ${it.key}: ${it.value}") }
            appendLine()
            appendLine("## Files")
            appendLine()
            appendLine("- summary.csv")
            appendLine("- games.jsonl")
            appendLine("- crashes.jsonl")
        }

        File(outputDir, "report.md").writeText(text)
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun json(name: String, value: String?): String =
        "\"$name\":${if (value == null) "null" else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""}"

    private fun json(name: String, value: Int): String = "\"$name\":$value"
    private fun json(name: String, value: Long): String = "\"$name\":$value"
    private fun json(name: String, value: Boolean): String = "\"$name\":$value"
}
