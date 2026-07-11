package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.logic.map.mapunit.MapUnit

/** Public bridge to the confirmation-free, fully validated core pillage action. */
fun MapUnit.getHeadlessPillageTitle(): String? =
    UnitActionsPillage.getPillageAction(this, getTile())?.takeIf { it.action != null }?.title

fun MapUnit.executeHeadlessPillage(): Boolean {
    val action = UnitActionsPillage.getPillageAction(this, getTile())?.action ?: return false
    action.invoke()
    return true
}
