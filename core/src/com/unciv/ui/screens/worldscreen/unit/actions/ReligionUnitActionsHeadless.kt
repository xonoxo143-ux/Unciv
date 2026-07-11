package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitAction

/** Public, narrow bridge exposing only executable religion-specific unit actions to desktop headless tools. */
data class HeadlessReligionUnitAction(val type: String, val title: String)

private fun MapUnit.executableReligionActions(): List<UnitAction> {
    val tile = getTile()
    return sequenceOf(
        UnitActionsReligion.getFoundReligionActions(this, tile),
        UnitActionsReligion.getEnhanceReligionActions(this, tile),
        UnitActionsReligion.getSpreadReligionActions(this, tile),
        UnitActionsReligion.getRemoveHeresyActions(this, tile)
    ).flatten().filter { it.action != null }.toList()
}

fun MapUnit.getHeadlessReligionUnitActions(): List<HeadlessReligionUnitAction> =
    executableReligionActions().map { HeadlessReligionUnitAction(it.type.name, it.title) }

fun MapUnit.executeHeadlessReligionUnitAction(type: String): Boolean {
    val action = executableReligionActions().firstOrNull { it.type.name == type } ?: return false
    action.action?.invoke() ?: return false
    return true
}
