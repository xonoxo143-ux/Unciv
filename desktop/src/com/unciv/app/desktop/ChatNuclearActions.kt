package com.unciv.app.desktop

import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.Nuke
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

/** Explicit nuclear strikes, restricted to targets whose full blast area is visible. */
internal object ChatNuclearActions {
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
        println("Nuclear actions complete: actions=${results.size} applied=${results.count { it.applied }}")
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
            .findAll(text).map { unescapeNukeJson(it.groupValues[1]) }.toList()
        return ids.ifEmpty { text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList() }
    }

    private fun apply(civ: Civilization, actionId: String): NukeResult {
        legalActions(civ).associateBy { it.actionId }[actionId]
            ?: return NukeResult(actionId, false, false, "Rejected: unknown or currently illegal visible-area nuclear strike")
        val parts = actionId.split(":", limit = 4)
        if (parts.size != 4 || parts[0] != "unit" || parts[2] != "nuke")
            return NukeResult(actionId, false, false, "Rejected: malformed nuclear action")
        val unit = parts[1].toIntOrNull()?.let { id -> civ.units.getCivUnits().firstOrNull { it.id == id } }
            ?: return NukeResult(actionId, false, false, "Rejected: nuclear unit no longer exists")
        val target = parseTile(unit, parts[3]) ?: return NukeResult(actionId, false, false, "Rejected: malformed target")
        if (target !in legalTargets(unit)) return NukeResult(actionId, false, false, "Rejected: target is no longer legal or fully visible")
        val id = unit.id
        val name = unit.name
        val visibleUnitsBefore = target.getTilesInDistance(unit.getNukeBlastRadius()).sumOf { it.getUnits().count() }
        val visibleCitiesBefore = target.getTilesInDistance(unit.getNukeBlastRadius()).count { it.isCityCenter() }
        return runCatching {
            Nuke.NUKE(MapUnitCombatant(unit), target)
            val survivor = civ.units.getCivUnits().firstOrNull { it.id == id }
            NukeResult(
                actionId, true, false,
                "Launched $name #$id at ${target.position}; blastRadius=${unit.getNukeBlastRadius()}; visibleUnitsBefore=$visibleUnitsBefore; visibleCitiesBefore=$visibleCitiesBefore; launchUnitConsumed=${survivor == null}; all exported blast tiles were visible"
            )
        }.getOrElse { NukeResult(actionId, false, true, "Error while executing nuclear strike: ${it.message ?: it::class.simpleName}") }
    }

    private fun legalActions(civ: Civilization): List<LegalNukeAction> = buildList {
        for (unit in civ.units.getCivUnits().filter { it.isNuclearWeapon() && it.canAttack() }.sortedBy { it.id }) {
            for (tile in legalTargets(unit)) add(LegalNukeAction(
                "unit:${unit.id}:nuke:${tile.position.x},${tile.position.y}", "nuclearStrike",
                "Launch ${unit.name} #${unit.id} at fully visible target ${tile.position}", tile.position.x, tile.position.y, unit.getNukeBlastRadius()
            ))
        }
    }

    private fun legalTargets(unit: MapUnit): List<Tile> {
        if (!unit.isNuclearWeapon() || !unit.canAttack()) return emptyList()
        val attacker = MapUnitCombatant(unit)
        val radius = unit.getNukeBlastRadius()
        return unit.currentTile.getTilesInDistance(unit.getRange())
            .filter { target -> target != unit.currentTile }
            .filter { target -> target.getTilesInDistance(radius).all { it.isVisible(unit.civ) } }
            .filter { Nuke.mayUseNuke(attacker, it) }
            .distinctBy { it.position }
            .sortedWith(compareBy<Tile> { it.position.x }.thenBy { it.position.y })
            .toList()
    }

    private fun parseTile(unit: MapUnit, text: String): Tile? {
        val coordinates = text.split(",", limit = 2)
        if (coordinates.size != 2) return null
        val x = coordinates[0].toIntOrNull() ?: return null
        val y = coordinates[1].toIntOrNull() ?: return null
        return unit.currentTile.tileMap.getOrNull(x, y)
    }

    private fun writeOutputs(config: Config, game: GameInfo, results: List<NukeResult>) {
        val out = File(config.outputDir).apply { mkdirs() }
        File(out, "nuclear-legal-actions.json").writeText(nukeArrayJson(legalActions(game.currentPlayerCiv).map { it.toJson() }) + "\n")
        File(out, "nuclear-result.json").writeText("{" + listOf(
            nukeJson("turn", game.turns), nukeJson("fullBlastVisibilityRequired", true), nukeJson("automationUsed", false),
            "\"results\":${nukeArrayJson(results.map { it.toJson() })}"
        ).joinToString(",") + "}\n")
    }

    private data class LegalNukeAction(val actionId: String, val category: String, val label: String, val x: Int, val y: Int, val blastRadius: Int) {
        fun toJson() = "{" + listOf(nukeJson("actionId", actionId), nukeJson("category", category), nukeJson("label", label), nukeJson("targetX", x), nukeJson("targetY", y), nukeJson("blastRadius", blastRadius)).joinToString(",") + "}"
    }
    private data class NukeResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson() = "{" + listOf(nukeJson("actionId", actionId), nukeJson("applied", applied), nukeJson("error", error), nukeJson("message", message)).joinToString(",") + "}"
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

private fun nukeArrayJson(values: Iterable<String>) = values.joinToString(prefix = "[", postfix = "]")
private fun nukeJson(name: String, value: String?) = "\"$name\":${if (value == null) "null" else "\"${escapeNukeJson(value)}\""}"
private fun nukeJson(name: String, value: Int) = "\"$name\":$value"
private fun nukeJson(name: String, value: Boolean) = "\"$name\":$value"
private fun escapeNukeJson(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
private fun unescapeNukeJson(value: String) = value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
