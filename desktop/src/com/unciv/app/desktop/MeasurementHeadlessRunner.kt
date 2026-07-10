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
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.time.ExperimentalTime

/**
 * Minimal no-UI measurement runner for chat/GitHub headless experiments.
 *
 * Parent mode runs each seed in a fresh child JVM. That avoids accumulated Unciv
 * global/transient state between simulated games and lets the parent enforce a
 * hard per-seed timeout from outside the game process.
 */
internal object MeasurementHeadlessRunner {

    @ExperimentalTime
    @JvmStatic
    fun main(args: Array<String>) {
        Log.backend = DesktopLogBackend()

        val config = MeasurementConfig.fromArgs(args)
        val writer = MeasurementResultWriter(config)
        writer.reset()

        val results = if (config.childMode) {
            initializeUnciv()
            runSingleGame(config, writer)
        } else {
            runGamesInChildJvms(config, writer)
        }

        writer.writeReport(results)
        println("Measurement complete: ${results.size} games -> ${config.outputDir}")

        val hasTimeout = results.any { it.timedOut }
        val hasCrash = results.any { it.crashed }
        exitProcess(
            when {
                hasTimeout -> 124
                hasCrash -> 1
                else -> 0
            }
        )
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

    private fun runSingleGame(config: MeasurementConfig, writer: MeasurementResultWriter): List<MeasuredGame> {
        val seed = config.seedStart
        val startedAt = System.currentTimeMillis()
        val result = runOneGame(seed, config).copy(
            index = 1,
            durationMs = System.currentTimeMillis() - startedAt
        )
        writer.writeIncremental(result)
        writer.writeReport(listOf(result))
        println("1/1 seed=$seed status=${result.status()} winner=${result.winner ?: "DRAW"} victory=${result.victoryType ?: "none"} turns=${result.turns} durationMs=${result.durationMs}")
        return listOf(result)
    }

    private fun runGamesInChildJvms(config: MeasurementConfig, writer: MeasurementResultWriter): List<MeasuredGame> {
        val measuredGames = ArrayList<MeasuredGame>()
        val outputDir = File(config.outputDir)
        outputDir.mkdirs()

        val jarFile = currentJarFile()
        repeat(config.games) { index ->
            val seed = config.seedStart + index
            val measured = runSeedInChildJvm(config, jarFile, index + 1, seed)
            measuredGames += measured
            writer.writeIncremental(measured)
            writer.writeReport(measuredGames.toList())
            println("${measured.index}/${config.games} seed=$seed status=${measured.status()} winner=${measured.winner ?: "DRAW"} victory=${measured.victoryType ?: "none"} turns=${measured.turns} durationMs=${measured.durationMs}")
        }

        return measuredGames
    }

    private fun runSeedInChildJvm(config: MeasurementConfig, jarFile: File, index: Int, seed: Int): MeasuredGame {
        val startedAt = System.currentTimeMillis()
        val seedOutputDir = File(config.outputDir, "seeds/$seed")
        if (seedOutputDir.exists()) seedOutputDir.deleteRecursively()
        seedOutputDir.mkdirs()

        val command = childCommand(config, jarFile, seed, seedOutputDir)
        File(seedOutputDir, "command.txt").writeText(command.joinToString(" "))
        val consoleLog = File(seedOutputDir, "console.log")

        val process = ProcessBuilder(command)
            .directory(File(".").absoluteFile)
            .redirectErrorStream(true)
            .redirectOutput(consoleLog)
            .start()

        val timeoutSeconds = config.perGameTimeoutSeconds
        val finished = if (timeoutSeconds > 0) {
            process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } else {
            process.waitFor()
            true
        }

        val durationMs = System.currentTimeMillis() - startedAt
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
            return MeasuredGame(
                index = index,
                seed = seed,
                turns = -1,
                winner = null,
                victoryType = null,
                currentPlayer = null,
                durationMs = durationMs,
                crashed = false,
                timedOut = true,
                crashMessage = "Child JVM timed out after $timeoutSeconds seconds. See seeds/$seed/console.log"
            )
        }

        val exitCode = process.exitValue()
        val parsed = readChildResult(seedOutputDir, index, seed, durationMs)
        if (parsed != null) {
            return if (exitCode != 0 && !parsed.crashed && !parsed.timedOut) {
                parsed.copy(
                    crashed = true,
                    crashMessage = "Child JVM exited with code $exitCode after writing a non-error row.\n" + tail(consoleLog)
                )
            } else parsed
        }

        return MeasuredGame(
            index = index,
            seed = seed,
            turns = -1,
            winner = null,
            victoryType = null,
            currentPlayer = null,
            durationMs = durationMs,
            crashed = true,
            timedOut = false,
            crashMessage = "Child JVM exited with code $exitCode without writing games.jsonl.\n" + tail(consoleLog)
        )
    }

    private fun childCommand(config: MeasurementConfig, jarFile: File, seed: Int, seedOutputDir: File): List<String> {
        return listOf(
            javaBinary(),
            "-Xmx${config.childMaxHeap}",
            "-jar",
            jarFile.absolutePath,
            "--child",
            "--seed",
            seed.toString(),
            "--max-turns",
            config.maxTurns.toString(),
            "--stat-turns",
            config.statTurns.joinToString(","),
            "--per-game-timeout-seconds",
            "0",
            "--output",
            seedOutputDir.path
        )
    }

    private fun currentJarFile(): File {
        val location = File(MeasurementHeadlessRunner::class.java.protectionDomain.codeSource.location.toURI())
        if (location.isFile) return location
        throw IllegalStateException(
            "Parent/child batch mode requires running from a jar. Current code source is ${location.absolutePath}. " +
                "Use --child --seed <seed> for direct single-process development runs."
        )
    }

    private fun javaBinary(): String {
        val javaHome = System.getProperty("java.home")
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val executable = if (isWindows) "java.exe" else "java"
        val candidate = File(File(javaHome, "bin"), executable)
        return if (candidate.exists()) candidate.absolutePath else "java"
    }

    private fun readChildResult(seedOutputDir: File, index: Int, seed: Int, durationMs: Long): MeasuredGame? {
        val gamesFile = File(seedOutputDir, "games.jsonl")
        if (!gamesFile.exists()) return null
        val line = gamesFile.readLines().firstOrNull { it.isNotBlank() } ?: return null
        return MeasuredGame(
            index = index,
            seed = jsonInt(line, "seed") ?: seed,
            turns = jsonInt(line, "turns") ?: -1,
            winner = jsonString(line, "winner"),
            victoryType = jsonString(line, "victoryType"),
            currentPlayer = null,
            durationMs = durationMs,
            crashed = jsonBool(line, "crashed") ?: false,
            timedOut = jsonBool(line, "timedOut") ?: false,
            crashMessage = readCrashMessage(seedOutputDir)
        )
    }

    private fun readCrashMessage(seedOutputDir: File): String? {
        val crashesFile = File(seedOutputDir, "crashes.jsonl")
        if (!crashesFile.exists()) return null
        val line = crashesFile.readLines().firstOrNull { it.isNotBlank() } ?: return null
        return jsonString(line, "crashMessage")
    }

    private fun jsonInt(text: String, name: String): Int? =
        Regex("\\\"$name\\\":(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun jsonBool(text: String, name: String): Boolean? =
        Regex("\\\"$name\\\":(true|false)").find(text)?.groupValues?.get(1)?.toBooleanStrictOrNull()

    private fun jsonString(text: String, name: String): String? {
        if (Regex("\\\"$name\\\":null").containsMatchIn(text)) return null
        val raw = Regex("\\\"$name\\\":\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"").find(text)?.groupValues?.get(1) ?: return null
        return raw
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun tail(file: File, maxChars: Int = 4000): String {
        if (!file.exists()) return "No console log written."
        val text = file.readText()
        return if (text.length <= maxChars) text else text.takeLast(maxChars)
    }

    private fun runOneGame(seed: Int, config: MeasurementConfig): MeasuredGame {
        return try {
            val setupInfo = createGameSetup(seed)
            val gameInfo = GameStarter.startNewGame(setupInfo)
            gameInfo.gameParameters.victoryTypes = ArrayList(gameInfo.ruleset.victories.keys)
            UncivGame.Current.gameInfo = gameInfo

            val valueSamples = ArrayList<HeadlessValueSample>()
            HeadlessTrainingLogger.captureValueSamples(gameInfo, seed, "start", valueSamples)

            val step = SimulationStep(gameInfo, config.statTurns)
            gameInfo.simulateUntilWin = true

            for (turn in config.statTurns.sorted()) {
                if (turn >= config.maxTurns) continue
                gameInfo.simulateMaxTurns = turn
                gameInfo.nextTurn()
                step.update(gameInfo)
                HeadlessTrainingLogger.captureValueSamples(gameInfo, seed, "turn-$turn", valueSamples)
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

            HeadlessTrainingLogger.captureValueSamples(gameInfo, seed, "final", valueSamples)
            HeadlessTrainingLogger.writeValueSamples(
                File(config.outputDir, "value-training.jsonl"),
                valueSamples,
                step.winner,
                step.victoryType,
                step.turns
            )

            MeasuredGame(
                seed = seed,
                turns = step.turns,
                winner = step.winner,
                victoryType = step.victoryType,
                currentPlayer = step.currentPlayer,
                crashed = false,
                timedOut = false,
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
                timedOut = false,
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
    val statTurns: List<Int> = listOf(50, 100, 150, 200),
    val perGameTimeoutSeconds: Int = 120,
    val childMaxHeap: String = "2G",
    val childMode: Boolean = false
) {
    companion object {
        fun fromArgs(args: Array<String>): MeasurementConfig {
            val (argMap, flags) = parseArgs(args)
            val fileConfig = argMap["config"]?.let { fromFile(File(it)) } ?: MeasurementConfig()
            val explicitSeed = argMap["seed"]?.toIntOrNull()
            return fileConfig.copy(
                games = if (explicitSeed != null) 1 else argMap["games"]?.toIntOrNull() ?: fileConfig.games,
                maxTurns = argMap["max-turns"]?.toIntOrNull() ?: fileConfig.maxTurns,
                seedStart = explicitSeed ?: argMap["seed-start"]?.toIntOrNull() ?: fileConfig.seedStart,
                outputDir = argMap["output"] ?: fileConfig.outputDir,
                statTurns = argMap["stat-turns"]?.let { parseIntList(it) } ?: fileConfig.statTurns,
                perGameTimeoutSeconds = argMap["per-game-timeout-seconds"]?.toIntOrNull() ?: fileConfig.perGameTimeoutSeconds,
                childMaxHeap = argMap["child-max-heap"] ?: fileConfig.childMaxHeap,
                childMode = "child" in flags
            )
        }

        private fun parseArgs(args: Array<String>): Pair<Map<String, String>, Set<String>> {
            val values = LinkedHashMap<String, String>()
            val flags = LinkedHashSet<String>()
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
                    flags += key
                    index++
                }
            }
            return values to flags
        }

        private fun fromFile(file: File): MeasurementConfig {
            if (!file.exists()) return MeasurementConfig()
            val text = file.readText()
            return MeasurementConfig(
                games = intField(text, "games") ?: 5,
                maxTurns = intField(text, "maxTurns") ?: 500,
                seedStart = intField(text, "seedStart") ?: 42017,
                outputDir = stringField(text, "outputDir") ?: "measurement-results",
                statTurns = intListField(text, "statTurns") ?: listOf(50, 100, 150, 200),
                perGameTimeoutSeconds = intField(text, "perGameTimeoutSeconds") ?: 120,
                childMaxHeap = stringField(text, "childMaxHeap") ?: "2G"
            )
        }

        private fun intField(text: String, name: String): Int? =
            Regex("\\\"$name\\\"\\s*:\\s*(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()

        private fun stringField(text: String, name: String): String? =
            Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(text)?.groupValues?.get(1)

        private fun intListField(text: String, name: String): List<Int>? {
            val raw = Regex("\\\"$name\\\"\\s*:\\s*\\[([^]]*)]").find(text)?.groupValues?.get(1) ?: return null
            return parseIntList(raw)
        }

        private fun parseIntList(raw: String): List<Int> =
            raw.split(',').mapNotNull { it.trim().toIntOrNull() }
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
    val timedOut: Boolean,
    val crashMessage: String?
) {
    fun status(): String = when {
        timedOut -> "timeout"
        crashed -> "crash"
        winner == null -> "draw"
        else -> "completed"
    }
}

private class MeasurementResultWriter(private val config: MeasurementConfig) {
    private val outputDir = File(config.outputDir)
    private val summaryFile = File(outputDir, "summary.csv")
    private val gamesFile = File(outputDir, "games.jsonl")
    private val crashesFile = File(outputDir, "crashes.jsonl")
    private val reportFile = File(outputDir, "report.md")

    @Synchronized
    fun reset() {
        outputDir.mkdirs()
        summaryFile.writeText("index,seed,status,winner,victory_type,turns,duration_ms,crashed,timed_out\n")
        gamesFile.writeText("")
        crashesFile.writeText("")
        reportFile.writeText("# Unciv Headless Measurement Report\n\nRun started. Results are written after each seed.\n")
    }

    @Synchronized
    fun writeIncremental(result: MeasuredGame) {
        summaryFile.appendText(summaryRow(result) + "\n")
        gamesFile.appendText(gameJson(result) + "\n")
        if (result.crashed || result.timedOut) crashesFile.appendText(crashJson(result) + "\n")
    }

    @Synchronized
    fun writeReport(results: List<MeasuredGame>) {
        val completed = results.count { !it.crashed && !it.timedOut }
        val crashes = results.count { it.crashed }
        val timeouts = results.count { it.timedOut }
        val draws = results.count { !it.crashed && !it.timedOut && it.winner == null }
        val winners = results.filter { !it.crashed && !it.timedOut && it.winner != null }.groupingBy { it.winner!! }.eachCount()
        val averageTurns = results.filter { !it.crashed && !it.timedOut && it.turns >= 0 }.map { it.turns }.average()
        val averageDuration = results.filter { !it.crashed && !it.timedOut }.map { it.durationMs }.average()

        val text = buildString {
            appendLine("# Unciv Headless Measurement Report")
            appendLine()
            appendLine("## Config")
            appendLine()
            appendLine("- Games requested: ${config.games}")
            appendLine("- Max turns: ${config.maxTurns}")
            appendLine("- Seed start: ${config.seedStart}")
            appendLine("- Stat turns: ${config.statTurns.joinToString()}")
            appendLine("- Per-game timeout seconds: ${config.perGameTimeoutSeconds}")
            appendLine("- Child max heap: ${config.childMaxHeap}")
            appendLine("- Child mode: ${config.childMode}")
            appendLine()
            appendLine("## Results")
            appendLine()
            appendLine("- Rows written: ${results.size}")
            appendLine("- Completed/draw rows: $completed")
            appendLine("- Crashes: $crashes")
            appendLine("- Timeouts: $timeouts")
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
            appendLine("- seeds/<seed>/console.log")
            appendLine("- seeds/<seed>/command.txt")
            appendLine("- seeds/<seed>/value-training.jsonl")
        }

        reportFile.writeText(text)
    }

    private fun summaryRow(result: MeasuredGame): String = listOf(
        result.index.toString(),
        result.seed.toString(),
        csv(result.status()),
        csv(result.winner ?: "DRAW"),
        csv(result.victoryType ?: ""),
        result.turns.toString(),
        result.durationMs.toString(),
        result.crashed.toString(),
        result.timedOut.toString()
    ).joinToString(",")

    private fun gameJson(result: MeasuredGame): String = "{" + listOf(
        json("index", result.index),
        json("seed", result.seed),
        json("status", result.status()),
        json("winner", result.winner),
        json("victoryType", result.victoryType),
        json("turns", result.turns),
        json("durationMs", result.durationMs),
        json("crashed", result.crashed),
        json("timedOut", result.timedOut)
    ).joinToString(",") + "}"

    private fun crashJson(result: MeasuredGame): String = "{" + listOf(
        json("index", result.index),
        json("seed", result.seed),
        json("status", result.status()),
        json("crashMessage", result.crashMessage)
    ).joinToString(",") + "}"

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun json(name: String, value: String?): String =
        "\"$name\":${if (value == null) "null" else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""}"

    private fun json(name: String, value: Int): String = "\"$name\":$value"
    private fun json(name: String, value: Long): String = "\"$name\":$value"
    private fun json(name: String, value: Boolean): String = "\"$name\":$value"
}
