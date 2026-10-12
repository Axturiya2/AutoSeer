package com.autoseer.scripts

/**
 * Per-segment pacing delays (milliseconds) inserted after key actions, so the
 * automation doesn't tap faster than the game (and its stage/menu transitions)
 * can keep up. All global (shared across scripts). 0 = no extra wait.
 *
 * These are pacing on top of the state-based `waitUntil` guards in [BattleScript];
 * raising them makes the flow more forgiving on laggy devices, lowering them
 * makes it snappier.
 */
data class BattleDelays(
    /** After tapping 「精靈恢復」. */
    val afterHeal: Long = 500,
    /** After a battle has started (進入戰鬥 成功) and before the first action. */
    val afterEnter: Long = 500,
    /** After casting a skill / action. */
    val afterSkill: Long = 500,
    /** After deploying a switched-in pet. */
    val afterSwitch: Long = 500,
    /** After tapping to dismiss a 勝利／失敗 result screen. */
    val afterResultTap: Long = 500,
    /** Between each single tap in the retreat sequence. */
    val afterRetreatTap: Long = 500,
) {
    companion object {
        fun default() = BattleDelays()
    }
}
