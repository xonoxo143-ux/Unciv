package com.unciv.logic.civilization.managers

/**
 * Narrow public bridge for the desktop headless benchmark.
 *
 * The normal picker lives in the core module and can call the module-internal founding transition.
 * Desktop headless tools are a separate Gradle module, so they need a validated public entry point.
 */
fun ReligionManager.foundReligionHeadless(displayName: String, symbolName: String): Boolean {
    if (religionState != ReligionState.FoundingReligion) return false
    if (displayName.isBlank()) return false
    if (symbolName !in civInfo.gameInfo.ruleset.religions) return false
    if (symbolName in civInfo.gameInfo.religions) return false
    foundReligion(displayName, symbolName)
    return true
}
