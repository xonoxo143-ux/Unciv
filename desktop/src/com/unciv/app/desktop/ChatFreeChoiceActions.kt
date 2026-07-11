package com.unciv.app.desktop

import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import java.io.File
import kotlin.system.exitProcess

/** Explicit free technology and free great-person choices. */
internal object ChatFreeChoiceActions {
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
        println("Free choices complete: actions=${results.size} applied=${results.count { it.applied }} rejected=${results.count { !it.applied }}")
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
            .findAll(text).map { unescapeFreeJson(it.groupValues[1]) }.toList()
        return ids.ifEmpty { text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList() }
    }

    private fun apply(civ: Civilization, actionId: String): ChoiceResult {
        val legal = legalActions(civ).associateBy { it.actionId }[actionId]
            ?: return ChoiceResult(actionId, false, false, "Rejected: unknown or currently illegal free choice")
        return runCatching {
            when (legal.category) {
                "freeTech" -> {
                    val name = actionId.removePrefix("choice:freeTech:")
                    if (civ.tech.freeTechs <= 0 || !civ.tech.canBeResearched(name))
                        ChoiceOutcome(false, "Rejected: $name is no longer a valid free technology")
                    else {
                        val before = civ.tech.freeTechs
                        civ.tech.getFreeTechnology(name)
                        ChoiceOutcome(true, "Selected free technology $name; freeTechs $before -> ${civ.tech.freeTechs}")
                    }
                }
                "freeGreatPerson" -> {
                    val name = actionId.removePrefix("choice:freeGreatPerson:")
                    val unit = legalGreatPeople(civ).firstOrNull { it.name == name }
                        ?: return@runCatching ChoiceOutcome(false, "Rejected: $name is no longer an available free great person")
                    val capital = civ.getCapital() ?: return@runCatching ChoiceOutcome(false, "Rejected: no capital is available for placement")
                    val before = civ.greatPeople.freeGreatPeople
                    val placed = civ.units.addUnit(unit, capital)
                    if (placed == null) ChoiceOutcome(false, "Rejected: $name could not be placed near ${capital.name}")
                    else {
                        civ.greatPeople.freeGreatPeople--
                        if (civ.greatPeople.mayaLimitedFreeGP > 0) {
                            civ.greatPeople.mayaLimitedFreeGP--
                            civ.greatPeople.longCountGPPool.remove(unit.name)
                        }
                        ChoiceOutcome(true, "Selected free great person ${unit.name} #${placed.id}; freeGreatPeople $before -> ${civ.greatPeople.freeGreatPeople}")
                    }
                }
                else -> ChoiceOutcome(false, "Rejected: unsupported free-choice category")
            }
        }.fold(
            onSuccess = { ChoiceResult(actionId, it.applied, false, it.message) },
            onFailure = { ChoiceResult(actionId, false, true, "Error while applying free choice: ${it.message ?: it::class.simpleName}") }
        )
    }

    private fun legalActions(civ: Civilization): List<LegalChoice> = buildList {
        if (civ.tech.freeTechs > 0) {
            for (tech in civ.gameInfo.ruleset.technologies.values.filter { civ.tech.canBeResearched(it.name) }.sortedBy { it.name }) {
                add(LegalChoice("choice:freeTech:${tech.name}", "freeTech", "Choose ${tech.name} as a free technology"))
            }
        }
        if (civ.greatPeople.freeGreatPeople > 0 && civ.getCapital() != null) {
            for (unit in legalGreatPeople(civ)) {
                add(LegalChoice("choice:freeGreatPerson:${unit.name}", "freeGreatPerson", "Choose ${unit.name} as a free great person"))
            }
        }
    }

    private fun legalGreatPeople(civ: Civilization): List<BaseUnit> {
        if (civ.greatPeople.freeGreatPeople <= 0) return emptyList()
        val mayaRestricted = civ.greatPeople.mayaLimitedFreeGP > 0
        return civ.greatPeople.getGreatPeople()
            .filter { !mayaRestricted || it.name in civ.greatPeople.longCountGPPool }
            .sortedBy { it.name }
    }

    private fun writeOutputs(config: Config, game: GameInfo, results: List<ChoiceResult>) {
        val civ = game.currentPlayerCiv
        val out = File(config.outputDir).apply { mkdirs() }
        File(out, "free-choice-legal-actions.json").writeText(freeArrayJson(legalActions(civ).map { it.toJson() }) + "\n")
        File(out, "free-choice-result.json").writeText("{" + listOf(
            freeJson("turn", game.turns), freeJson("freeTechs", civ.tech.freeTechs),
            freeJson("freeGreatPeople", civ.greatPeople.freeGreatPeople), freeJson("mayaLimitedFreeGP", civ.greatPeople.mayaLimitedFreeGP),
            freeJson("automationUsed", false), "\"results\":${freeArrayJson(results.map { it.toJson() })}"
        ).joinToString(",") + "}\n")
    }

    private data class ChoiceOutcome(val applied: Boolean, val message: String)
    private data class LegalChoice(val actionId: String, val category: String, val label: String) {
        fun toJson() = "{" + listOf(freeJson("actionId", actionId), freeJson("category", category), freeJson("label", label)).joinToString(",") + "}"
    }
    private data class ChoiceResult(val actionId: String, val applied: Boolean, val error: Boolean, val message: String) {
        fun toJson() = "{" + listOf(freeJson("actionId", actionId), freeJson("applied", applied), freeJson("error", error), freeJson("message", message)).joinToString(",") + "}"
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

private fun freeArrayJson(values: Iterable<String>) = values.joinToString(prefix = "[", postfix = "]")
private fun freeJson(name: String, value: String?) = "\"$name\":${if (value == null) "null" else "\"${escapeFreeJson(value)}\""}"
private fun freeJson(name: String, value: Int) = "\"$name\":$value"
private fun freeJson(name: String, value: Boolean) = "\"$name\":$value"
private fun escapeFreeJson(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
private fun unescapeFreeJson(value: String) = value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
