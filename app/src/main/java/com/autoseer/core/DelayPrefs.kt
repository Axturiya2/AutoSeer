package com.autoseer.core

import android.content.Context
import com.autoseer.scripts.BattleDelays

/**
 * Global pacing delays (milliseconds), shared across all scripts, persisted in
 * their own SharedPreferences file. Edited from the delay-settings screen and
 * fed into [BattleDelays] when a run starts.
 */
object DelayPrefs {
    private const val FILE = "autoseer_delays"
    private const val KEY_HEAL = "after_heal"
    private const val KEY_ENTER = "after_enter"
    private const val KEY_SKILL = "after_skill"
    private const val KEY_SWITCH = "after_switch"
    private const val KEY_RESULT = "after_result"
    private const val KEY_RETREAT = "after_retreat"

    const val DEFAULT_MS = 500

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun afterHeal(ctx: Context): Int = prefs(ctx).getInt(KEY_HEAL, DEFAULT_MS)
    fun afterEnter(ctx: Context): Int = prefs(ctx).getInt(KEY_ENTER, DEFAULT_MS)
    fun afterSkill(ctx: Context): Int = prefs(ctx).getInt(KEY_SKILL, DEFAULT_MS)
    fun afterSwitch(ctx: Context): Int = prefs(ctx).getInt(KEY_SWITCH, DEFAULT_MS)
    fun afterResult(ctx: Context): Int = prefs(ctx).getInt(KEY_RESULT, DEFAULT_MS)
    fun afterRetreat(ctx: Context): Int = prefs(ctx).getInt(KEY_RETREAT, DEFAULT_MS)

    fun save(
        ctx: Context,
        afterHeal: Int,
        afterEnter: Int,
        afterSkill: Int,
        afterSwitch: Int,
        afterResult: Int,
        afterRetreat: Int,
    ) {
        prefs(ctx).edit()
            .putInt(KEY_HEAL, afterHeal.coerceAtLeast(0))
            .putInt(KEY_ENTER, afterEnter.coerceAtLeast(0))
            .putInt(KEY_SKILL, afterSkill.coerceAtLeast(0))
            .putInt(KEY_SWITCH, afterSwitch.coerceAtLeast(0))
            .putInt(KEY_RESULT, afterResult.coerceAtLeast(0))
            .putInt(KEY_RETREAT, afterRetreat.coerceAtLeast(0))
            .apply()
    }

    /** Build the script-layer delay model from the saved global values. */
    fun toBattleDelays(ctx: Context): BattleDelays = BattleDelays(
        afterHeal = afterHeal(ctx).toLong(),
        afterEnter = afterEnter(ctx).toLong(),
        afterSkill = afterSkill(ctx).toLong(),
        afterSwitch = afterSwitch(ctx).toLong(),
        afterResultTap = afterResult(ctx).toLong(),
        afterRetreatTap = afterRetreat(ctx).toLong(),
    )
}
