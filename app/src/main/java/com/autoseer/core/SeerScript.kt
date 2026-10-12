package com.autoseer.core

import com.autoseer.scripts.SeerLayout
import java.util.UUID

/**
 * One saved automation script: a per-stage skill plan plus its run options and
 * optional player-facing skill-slot names. Multiple of these are managed by
 * [ScriptStore] so the user can keep several and switch between them.
 *
 * [skillNames] is display-only (executed casts still use the numeric slot 1..5);
 * this is how the UI shows "玩家看得懂的語言" while the runtime keeps codes.
 */
data class SeerScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val note: String = "",
    val planText: String = "",
    val maxBattles: Int = 30,
    val healBeforeBattle: Boolean = true,
    val advanceMap: Boolean = true,
    val defaultSlot: Int = 2,
    val startStage: Int = 1,
    val maxRetries: Int = 5,
    /** Full loops of the stage list to run (精靈因子=3). 1 = single pass. */
    val loops: Int = 1,
    /** Mission category this script belongs to (selects the run engine). */
    val category: String = CATEGORY_SEER_FACTOR,
    /** Optional display names for skill slots 1..5; blank falls back to the number. */
    val skillNames: List<String> = List(SeerLayout.SKILL_COUNT) { "" },
) {
    val displayName: String get() = if (name.isBlank()) "--" else name

    /** Player-facing label for a plan code, using custom skill names when set. */
    fun labelForCode(code: Int): String {
        if (code in 1..SeerLayout.SKILL_COUNT) {
            skillNames.getOrNull(code - 1)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return SeerLayout.labelFor(code)
    }

    companion object {
        const val CATEGORY_SEER_FACTOR = "seer_factor"
        const val CATEGORY_DAILY = "daily"

        fun new(name: String = "", category: String = CATEGORY_SEER_FACTOR) =
            SeerScript(name = name, category = category)
    }
}
