package com.unciv.logic.automation.neural

import com.unciv.logic.civilization.Civilization
import java.io.File
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.tanh

/**
 * Small runtime scorer for the dependency-free value model emitted by the
 * headless learning tools.
 *
 * This is deliberately dormant unless a model file is supplied through either:
 * - JVM property: -Dunciv.neural.valueModel=/path/to/value-model.json
 * - Environment: UNCIV_NEURAL_VALUE_MODEL=/path/to/value-model.json
 * - Default relative file: neural-overlays/value-model.json
 */
object NeuralValueModel {
    private const val defaultModelPath = "neural-overlays/value-model.json"
    private val model: TinyValueModel? by lazy { loadModelOrNull() }

    fun isEnabled(): Boolean = model != null

    fun scoreCiv(civ: Civilization): Double? {
        val loadedModel = model ?: return null
        val features = valueFeaturesFor(civ, NeuralFeatureAdjustments()) ?: return null
        return loadedModel.score(features)
    }

    fun scoreConstructionAdjustment(civ: Civilization, adjustments: NeuralFeatureAdjustments): Double? {
        val loadedModel = model ?: return null
        val baseline = valueFeaturesFor(civ, NeuralFeatureAdjustments()) ?: return null
        val proposal = valueFeaturesFor(civ, adjustments) ?: return null
        return loadedModel.score(proposal) - loadedModel.score(baseline)
    }

    fun constructionStrength(): Double {
        val property = System.getProperty("unciv.neural.constructionStrength")
        val environment = System.getenv("UNCIV_NEURAL_CONSTRUCTION_STRENGTH")
        return (property ?: environment)?.toDoubleOrNull() ?: 1.0
    }

    private fun loadModelOrNull(): TinyValueModel? {
        val configuredPath = System.getProperty("unciv.neural.valueModel")
            ?: System.getenv("UNCIV_NEURAL_VALUE_MODEL")
            ?: defaultModelPath
        val file = File(configuredPath)
        if (!file.exists() || !file.isFile) return null
        return runCatching { TinyValueModel.fromJson(file.readText()) }.getOrNull()
    }

    private fun valueFeaturesFor(civ: Civilization, adjustments: NeuralFeatureAdjustments): LinkedHashMap<String, Double>? {
        val civs = civ.gameInfo.civilizations
            .filter { it.isMajorCiv() && !it.isSpectator() }
        if (civs.isEmpty()) return null

        val snapshots = civs.associateWith { snapshotFor(it) }
        val own = snapshots[civ]?.plus(adjustments) ?: return null
        val rivalMax = aggregateMax(snapshots.entries.filter { it.key != civ }.map { it.value })
        return valueFeatures(own, rivalMax)
    }

    private fun snapshotFor(civ: Civilization): CivValueSnapshot {
        runCatching { civ.updateStatsForNextTurn() }
        val stats = runCatching { civ.stats.statsForNextTurn }.getOrNull()
        return CivValueSnapshot(
            cities = civ.cities.size.toDouble(),
            population = civ.cities.sumOf { it.population.population }.toDouble(),
            units = civ.units.getCivUnitsSize().toDouble(),
            techs = civ.tech.getNumberOfTechsResearched().toDouble(),
            goldReserve = civ.gold.toDouble(),
            production = stats?.production?.toDouble() ?: 0.0,
            food = stats?.food?.toDouble() ?: 0.0,
            goldPerTurn = stats?.gold?.toDouble() ?: 0.0,
            science = stats?.science?.toDouble() ?: 0.0,
            culture = stats?.culture?.toDouble() ?: 0.0,
            happiness = civ.getHappiness().toDouble()
        )
    }

    private fun aggregateMax(snapshots: List<CivValueSnapshot>): CivValueSnapshot {
        if (snapshots.isEmpty()) return CivValueSnapshot()
        return CivValueSnapshot(
            cities = snapshots.maxOf { it.cities },
            population = snapshots.maxOf { it.population },
            units = snapshots.maxOf { it.units },
            techs = snapshots.maxOf { it.techs },
            goldReserve = snapshots.maxOf { it.goldReserve },
            production = snapshots.maxOf { it.production },
            food = snapshots.maxOf { it.food },
            goldPerTurn = snapshots.maxOf { it.goldPerTurn },
            science = snapshots.maxOf { it.science },
            culture = snapshots.maxOf { it.culture },
            happiness = snapshots.maxOf { it.happiness }
        )
    }

    private fun valueFeatures(own: CivValueSnapshot, rivalMax: CivValueSnapshot): LinkedHashMap<String, Double> {
        fun ratio(value: Double, denom: Double): Double = value / max(1.0, denom)
        fun delta(value: Double, other: Double): Double = value - other

        return linkedMapOf(
            "cities" to own.cities,
            "population" to own.population,
            "units" to own.units,
            "techs" to own.techs,
            "gold_reserve" to own.goldReserve,
            "production" to own.production,
            "food" to own.food,
            "gold_per_turn" to own.goldPerTurn,
            "science" to own.science,
            "culture" to own.culture,
            "happiness" to own.happiness,
            "cities_ratio" to ratio(own.cities, rivalMax.cities),
            "population_ratio" to ratio(own.population, rivalMax.population),
            "units_ratio" to ratio(own.units, rivalMax.units),
            "techs_ratio" to ratio(own.techs, rivalMax.techs),
            "science_ratio" to ratio(own.science, rivalMax.science),
            "production_ratio" to ratio(own.production, rivalMax.production),
            "cities_delta" to delta(own.cities, rivalMax.cities),
            "population_delta" to delta(own.population, rivalMax.population),
            "units_delta" to delta(own.units, rivalMax.units),
            "techs_delta" to delta(own.techs, rivalMax.techs),
            "science_delta" to delta(own.science, rivalMax.science),
            "production_delta" to delta(own.production, rivalMax.production)
        )
    }
}

data class NeuralFeatureAdjustments(
    val cities: Double = 0.0,
    val population: Double = 0.0,
    val units: Double = 0.0,
    val techs: Double = 0.0,
    val goldReserve: Double = 0.0,
    val production: Double = 0.0,
    val food: Double = 0.0,
    val goldPerTurn: Double = 0.0,
    val science: Double = 0.0,
    val culture: Double = 0.0,
    val happiness: Double = 0.0
)

private data class CivValueSnapshot(
    val cities: Double = 0.0,
    val population: Double = 0.0,
    val units: Double = 0.0,
    val techs: Double = 0.0,
    val goldReserve: Double = 0.0,
    val production: Double = 0.0,
    val food: Double = 0.0,
    val goldPerTurn: Double = 0.0,
    val science: Double = 0.0,
    val culture: Double = 0.0,
    val happiness: Double = 0.0
) {
    fun plus(adjustments: NeuralFeatureAdjustments): CivValueSnapshot = copy(
        cities = cities + adjustments.cities,
        population = population + adjustments.population,
        units = units + adjustments.units,
        techs = techs + adjustments.techs,
        goldReserve = goldReserve + adjustments.goldReserve,
        production = production + adjustments.production,
        food = food + adjustments.food,
        goldPerTurn = goldPerTurn + adjustments.goldPerTurn,
        science = science + adjustments.science,
        culture = culture + adjustments.culture,
        happiness = happiness + adjustments.happiness
    )
}

private data class TinyValueModel(
    val features: List<String>,
    val means: List<Double>,
    val stds: List<Double>,
    val w1: List<List<Double>>,
    val b1: List<Double>,
    val w2: List<Double>,
    val b2: Double
) {
    fun score(featureValues: Map<String, Double>): Double {
        val x = features.mapIndexed { index, feature ->
            val std = stds.getOrElse(index) { 1.0 }.let { if (it == 0.0) 1.0 else it }
            (featureValues[feature] ?: 0.0 - means.getOrElse(index) { 0.0 }) / std
        }
        val hidden = w1.mapIndexed { index, row ->
            tanh(b1.getOrElse(index) { 0.0 } + row.zip(x).sumOf { it.first * it.second })
        }
        val logit = b2 + w2.zip(hidden).sumOf { it.first * it.second }
        return sigmoid(logit)
    }

    companion object {
        fun fromJson(text: String): TinyValueModel {
            val features = parseStringArray(extractJsonValue(text, "features") ?: "[]")
            val normalization = extractJsonValue(text, "normalization") ?: "{}"
            val weights = extractJsonValue(text, "weights") ?: "{}"
            return TinyValueModel(
                features = features,
                means = parseDoubleArray(extractJsonValue(normalization, "means") ?: "[]"),
                stds = parseDoubleArray(extractJsonValue(normalization, "stds") ?: "[]"),
                w1 = parseDoubleMatrix(extractJsonValue(weights, "w1") ?: "[]"),
                b1 = parseDoubleArray(extractJsonValue(weights, "b1") ?: "[]"),
                w2 = parseDoubleArray(extractJsonValue(weights, "w2") ?: "[]"),
                b2 = (extractJsonValue(weights, "b2") ?: "0").toDoubleOrNull() ?: 0.0
            )
        }
    }
}

private fun sigmoid(value: Double): Double {
    if (value >= 0) {
        val z = exp(-value)
        return 1.0 / (1.0 + z)
    }
    val z = exp(value)
    return z / (1.0 + z)
}

private fun extractJsonValue(text: String, key: String): String? {
    val keyIndex = text.indexOf("\"$key\"")
    if (keyIndex < 0) return null
    val colonIndex = text.indexOf(':', keyIndex)
    if (colonIndex < 0) return null
    var index = colonIndex + 1
    while (index < text.length && text[index].isWhitespace()) index++
    if (index >= text.length) return null

    val start = text[index]
    if (start == '[' || start == '{') {
        val open = start
        val close = if (start == '[') ']' else '}'
        var depth = 0
        var inString = false
        var escaped = false
        for (cursor in index until text.length) {
            val char = text[cursor]
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                !inString && char == open -> depth++
                !inString && char == close -> {
                    depth--
                    if (depth == 0) return text.substring(index, cursor + 1)
                }
            }
        }
        return null
    }

    val end = text.indexOfAny(charArrayOf(',', '}', ']'), startIndex = index).let { if (it < 0) text.length else it }
    return text.substring(index, end).trim().trim('"')
}

private fun parseStringArray(raw: String): List<String> = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
    .findAll(raw)
    .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
    .toList()

private fun parseDoubleArray(raw: String): List<Double> = Regex("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")
    .findAll(raw)
    .mapNotNull { it.value.toDoubleOrNull() }
    .toList()

private fun parseDoubleMatrix(raw: String): List<List<Double>> {
    val rows = ArrayList<List<Double>>()
    var depth = 0
    var rowStart = -1
    for (index in raw.indices) {
        when (raw[index]) {
            '[' -> {
                depth++
                if (depth == 2) rowStart = index
            }
            ']' -> {
                if (depth == 2 && rowStart >= 0) {
                    rows += parseDoubleArray(raw.substring(rowStart, index + 1))
                    rowStart = -1
                }
                depth--
            }
        }
    }
    return rows
}
