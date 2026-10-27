package com.autoseer.core

import android.content.Context
import com.autoseer.scripts.RunMode

/**
 * Global execution options (shared by all scripts) per the finalized UI:
 * 起始關卡、預設技能格、戰前恢復、每關重試上限. Delays live in [DelayPrefs].
 *
 * 清關場數上限與循環輪數已移除——精靈因子改由畫面「達到每天操作上限」字樣自然結束。
 */
object RunPrefs {
    private const val FILE = "autoseer_run"
    private const val KEY_START_STAGE = "start_stage"
    private const val KEY_DEFAULT_SLOT = "default_slot"
    private const val KEY_HEAL = "heal_before_battle"
    private const val KEY_LOBBY_ON_EXHAUST = "lobby_on_retry_exhausted"
    private const val KEY_MAX_RETRIES = "max_retries_per_stage"
    private const val KEY_MODE = "run_mode"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 懸浮窗「功能」選單：按開始要跑哪種流程（預設精靈因子＝舊行為）。 */
    fun mode(ctx: Context): RunMode = RunMode.fromKey(prefs(ctx).getString(KEY_MODE, null))
    fun setMode(ctx: Context, mode: RunMode) = prefs(ctx).edit().putString(KEY_MODE, mode.key).apply()

    /** 1-based stage to start from (中途續跑用；第 1 關填 1). */
    fun startStage(ctx: Context): Int = prefs(ctx).getInt(KEY_START_STAGE, 1)
    fun defaultSlot(ctx: Context): Int = prefs(ctx).getInt(KEY_DEFAULT_SLOT, 2)
    fun healBeforeBattle(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HEAL, true)

    /** 失敗達每關重試上限後是否回大廳。預設 false＝待在原本的地方（原地停）。 */
    fun backToLobbyOnRetryExhausted(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_LOBBY_ON_EXHAUST, false)

    /**
     * 同一關連續失敗/撤退後最多重打幾次（0＝不限）。所有腳本共用，取代腳本自帶的 maxRetries
     * （舊版有欄位，改版後介面拿掉，等於固定 5）。
     */
    fun maxRetriesPerStage(ctx: Context): Int = prefs(ctx).getInt(KEY_MAX_RETRIES, 5)

    fun save(
        ctx: Context,
        startStage: Int,
        defaultSlot: Int,
        healBeforeBattle: Boolean,
        backToLobbyOnRetryExhausted: Boolean,
        maxRetriesPerStage: Int,
    ) {
        prefs(ctx).edit()
            .putInt(KEY_START_STAGE, startStage.coerceAtLeast(1))
            .putInt(KEY_DEFAULT_SLOT, defaultSlot.coerceIn(1, 5))
            .putBoolean(KEY_HEAL, healBeforeBattle)
            .putBoolean(KEY_LOBBY_ON_EXHAUST, backToLobbyOnRetryExhausted)
            .putInt(KEY_MAX_RETRIES, maxRetriesPerStage.coerceAtLeast(0))
            .apply()
    }
}
