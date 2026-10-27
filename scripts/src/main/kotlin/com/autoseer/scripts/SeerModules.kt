package com.autoseer.scripts

import com.autoseer.libautomata.AutomataApi
import com.autoseer.libautomata.Location
import com.autoseer.libautomata.Templates

/**
 * Reusable vision building-blocks for mission scripts (精靈因子、每日任務…).
 * Everything works full-screen: `matchTemplate` locates the target wherever it
 * is and taps the found position, so buttons need no fixed coordinates. These
 * are the "通用判斷" modules the user asked to factor out for reuse across stages.
 */
class SeerModules(
    private val api: AutomataApi,
    private val templates: Templates,
) {
    /** Template present on screen right now (screen must be refreshed by caller). */
    fun exists(id: String): Boolean = templates.has(id) && api.exists(templates.get(id))

    /** Best match for [id] on the current screen, or null. */
    private fun find(id: String) = if (templates.has(id)) api.find(templates.get(id)) else null

    /** Tap [id] where it is found right now. Returns false if not present. */
    fun tapIfPresent(id: String): Boolean {
        val m = find(id) ?: return false
        api.click(m)
        return true
    }

    /** Refresh + poll until [id] appears (or timeout). */
    fun waitAppear(id: String, timeoutMs: Long, pollMs: Long = 400): Boolean =
        templates.has(id) && api.waitUntil(timeoutMs, pollMs) { api.exists(templates.get(id)) }

    /** Wait until any of [ids] appears; returns the one that hit, or null on timeout. */
    fun waitAppearAny(ids: List<String>, timeoutMs: Long, pollMs: Long = 400): String? {
        val known = ids.filter { templates.has(it) }
        if (known.isEmpty()) return null
        var hit: String? = null
        api.waitUntil(timeoutMs, pollMs) {
            hit = known.firstOrNull { api.exists(templates.get(it)) }
            hit != null
        }
        return hit
    }

    /** Wait for [id] to appear then tap it where found. Returns true if tapped. */
    fun waitAndTap(id: String, timeoutMs: Long): Boolean {
        if (!waitAppear(id, timeoutMs)) return false
        api.refreshScreen()
        return tapIfPresent(id)
    }

    /** If [id] is showing now, tap the given fixed [point] (e.g. a 確認 button). */
    fun tapFixedIfPresent(id: String, point: Location): Boolean {
        if (!exists(id)) return false
        api.click(point)
        return true
    }

    /**
     * Run [tap], then wait for [expectId] to appear; repeat up to [tries] times.
     * Returns true once it appears. Use this when a single tap sometimes doesn't
     * "take" (the game ignores taps until a dialog fully settles) — verify the
     * next state actually arrived instead of blindly moving on.
     */
    fun tapUntilAppears(expectId: String, tries: Int, pollMs: Long = 200L, tap: () -> Unit): Boolean {
        repeat(tries) {
            tap()
            if (waitAppear(expectId, STEP_WAIT_MS, pollMs)) return true
        }
        return exists(expectId)
    }

    /**
     * Like [tapUntilAppears] but waits for ANY of [expectIds]; returns the id that
     * appeared, or null after [tries]. Use when a single tap may need repeating and
     * extra taps are harmless (e.g. 精靈恢復: the visual 字樣 is the real gate, so
     * tapping the button a few more times before it shows costs nothing).
     */
    fun tapUntilAppearsAny(
        expectIds: List<String>, tries: Int, pollMs: Long = 200L, tap: () -> Unit,
    ): String? {
        repeat(tries) {
            tap()
            waitAppearAny(expectIds, STEP_WAIT_MS, pollMs)?.let { return it }
        }
        return null
    }

    /** Tap [point] until [goneId] is no longer on screen (result-screen dismiss). */
    fun tapPointUntilGone(goneId: String, point: Location, tries: Int, betweenMs: Long): Boolean {
        repeat(tries) {
            api.click(point)
            api.sleep(betweenMs)
            api.refreshScreen()
            if (!exists(goneId)) return true
        }
        return !exists(goneId)
    }

    /**
     * 「撤退」通用判斷 (PPT s14–18): 撤退 → 等「你確定要撤退嗎」→ 確認 → 等「恭喜你，
     * 成功撤退」→ 確認. Template-gated (fixed 確認 coords from [SeerLayout]) instead
     * of the old blind paced-tap loop, so it stops the moment each dialog is seen.
     * If 撤退 is greyed out because the on-field pet is 已戰敗, deploys one first.
     * Returns true once the retreat dialogs are cleared.
     */
    fun retreat(delays: BattleDelays): Boolean {
        api.logger.i("嘗試撤退…")
        api.refreshScreen()
        if (exists(SeerTemplates.PET_DEFEATED)) deployAnyPet(delays)

        // s14 → s15：點「撤退」直到「你確定要撤退嗎」出現（反灰時上一步已補精靈）。
        if (!tapUntilAppears(SeerTemplates.RETREAT_TIP, RETREAT_ATTEMPTS) {
                api.click(SeerLayout.RETREAT.center); api.sleep(delays.afterRetreatTap)
            }
        ) { api.logger.w("撤退鈕無效：撤退視窗未出現"); return false }

        // s16 → s17：點「確認」直到「恭喜你，成功撤退」出現（確認偶爾無效時重點）。
        if (!tapUntilAppears(SeerTemplates.RETREAT_SUCCESS, RETREAT_ATTEMPTS) {
                api.click(SeerLayout.RETREAT_CONFIRM); api.sleep(delays.afterRetreatTap)
            }
        ) { api.logger.w("撤退確認無效：成功撤退視窗未出現"); return false }

        // s18 → 結算：點「確認」直到「點擊任意位置繼續」出現（沒到結算就重點）。
        if (!tapUntilAppears(SeerTemplates.TAP_CONTINUE, RETREAT_ATTEMPTS) {
                api.click(SeerLayout.RETREAT_SUCCESS_CONFIRM); api.sleep(delays.afterRetreatTap)
            }
        ) { api.logger.w("撤退成功確認後未見結算畫面（交由結算流程續處理）") }
        return true
    }

    /** Deploy an alive pet to un-grey 撤退 / unstick, when the field pet is 已戰敗. */
    private fun deployAnyPet(delays: BattleDelays) {
        var tries = 0
        while (exists(SeerTemplates.PET_DEFEATED) && tries < SeerLayout.PET_COUNT) {
            val slot = SeerLayout.PET_SLOTS.getOrNull(tries) ?: break
            api.click(SeerLayout.PET.center); api.sleep(500); api.refreshScreen()
            api.click(slot); api.sleep(300)
            api.swipe(slot, Location(slot.x, slot.y - SeerLayout.PET_DEPLOY_UP_PX), 450)
            api.sleep(delays.afterSwitch); api.refreshScreen()
            tries++
        }
    }

    companion object {
        private const val RETREAT_ATTEMPTS = 4    // 每步「點擊→驗證」重試次數
        private const val STEP_WAIT_MS = 2_500L   // 每次點擊後等下一狀態出現的時間
    }
}
