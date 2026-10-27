package com.autoseer.scripts

import com.autoseer.libautomata.AutomataApi
import com.autoseer.libautomata.Location
import com.autoseer.libautomata.Script
import com.autoseer.libautomata.Templates

/**
 * Core farm loop for 《賽爾號：巔峰之戰》 driven by on-screen state and a
 * user-authored [BattlePlan] of per-stage [Step]s.
 *
 * Per stage it walks the steps: cast a skill/action, or 換精靈. A `*N` cast
 * (untilDefeat) repeats every turn until the active pet is 已戰敗 (then it moves
 * to the next step — usually a Switch). If the stage's steps run out before
 * 「勝利」, that's a failure: it retreats and retries the same stage.
 *
 *   勝利                  -> stage cleared
 *   已戰敗                -> active pet died: advance the *N step / do a pending Switch
 *   你的回合 + 計畫用盡    -> failure: 撤退 → 確認 → 失敗UI → 繼續挑戰 → 精靈恢復 → 進入戰鬥
 *   進入戰鬥              -> heal, pick next node, enter
 */
class BattleScript(
    api: AutomataApi,
    private val templates: Templates,
    private val plan: BattlePlan = BattlePlan.default(),
    private val delays: BattleDelays = BattleDelays.default(),
) : Script(api) {

    override val name = "自動戰鬥/刷取 (BattleScript)"

    var battlesDone = 0
        private set

    private var stepIndex = 0
    private var retries = 0
    // Plan index for the stage we're on: offset by the start stage and wrapped
    // within the loop, so a fixed-count map cycles instead of running off the end.
    private val stageIndex: Int get() = plan.stageIndexFor(battlesDone)

    override fun run() {
        api.logger.i("BattleScript 開始：maxBattles=${plan.maxBattles}, 循環=${plan.loops}, 每輪關數=${plan.stagesPerLoop}, 戰前恢復=${plan.healBeforeBattle}, 推進地圖=${plan.advanceMap}, 重試上限=${plan.maxRetriesPerStage}")
        var unknown = 0
        while (true) {
            if (reachedLimit()) { api.logger.i("已達停止條件（已清 $battlesDone 關），停止。"); break }
            api.refreshScreen()

            when {
                exists(SeerTemplates.OUT_OF_STAMINA) -> { api.logger.i("挑戰次數不足，停止。"); break }

                exists(SeerTemplates.RESULT_WIN) -> {
                    onVictory(); unknown = 0
                }

                exists(SeerTemplates.PET_DEFEATED) -> {
                    onPetDefeated(); unknown = 0
                }

                exists(SeerTemplates.BATTLE_ACTION) -> {
                    if (!onYourTurn()) break   // false = gave up (retry limit)
                    unknown = 0
                }

                exists(SeerTemplates.ENTER_BATTLE) -> {
                    enterNextStage(); stepIndex = 0; unknown = 0
                }

                else -> {
                    if (++unknown >= STUCK_LIMIT) { api.logger.w("連續 $STUCK_LIMIT 次無法辨識畫面，停止。"); break }
                    // Live diagnostic while waiting (e.g. long post-enter lag before
                    // 你的回合 appears): show how close 「你的回合」 is to matching, so a
                    // template that never reaches the threshold is obvious on the overlay.
                    if (unknown % 5 == 0 && templates.has(SeerTemplates.BATTLE_ACTION)) {
                        val s = api.find(templates.get(SeerTemplates.BATTLE_ACTION), threshold = 0.0)?.score ?: -1.0
                        api.logger.i("等待可辨識畫面…（你的回合樣板分數 ${fmt(s)}／門檻 ${AutomataApi.DEFAULT_THRESHOLD}）")
                    }
                    api.sleep(700)
                }
            }
        }
        api.logger.i("BattleScript 結束，共清 $battlesDone 關。")
    }

    private fun currentSteps(): List<Step> = plan.forStage(stageIndex).steps

    /** It's our turn and no pet is defeated. Returns false if we hit the retry limit. */
    private fun onYourTurn(): Boolean {
        val steps = currentSteps()
        when {
            steps.isEmpty() -> {
                cast(plan.defaultCode)             // no explicit plan: spam default until win
            }
            stepIndex >= steps.size -> {
                // Plan finished but 勝利 didn't appear -> failure.
                api.logger.i("第 ${stageIndex + 1} 關：計畫跑完仍未勝利 → 失敗")
                if (retriesExhausted()) {
                    api.logger.w("第 ${stageIndex + 1} 關重試已達上限 ${plan.maxRetriesPerStage}，停止。")
                    return false
                }
                retries++
                retreatAndRetry()
                stepIndex = 0
                return true
            }
            else -> {
                val step = steps[stepIndex]
                if (step.isSwitch) {
                    switchPet(step.petIndex, alreadyOpen = false)
                    stepIndex++
                } else {
                    cast(step.code)
                    if (!step.untilDefeat) stepIndex++   // *N stays; advances on 已戰敗
                }
            }
        }
        // Wait for this turn's prompt to settle (or a pet to fall) before re-checking.
        api.waitUntil(TURN_SETTLE_MS, pollMs = 400) {
            !exists(SeerTemplates.BATTLE_ACTION) || exists(SeerTemplates.PET_DEFEATED)
        }
        return true
    }

    /** A pet is 已戰敗 (switch screen open). Advance a *N step, or do a pending Switch. */
    private fun onPetDefeated() {
        val steps = currentSteps()
        // If we were spamming until defeat, that pet has now fallen -> next step.
        steps.getOrNull(stepIndex)?.let { if (it.untilDefeat) stepIndex++ }

        val next = steps.getOrNull(stepIndex)
        when {
            next == null && steps.isNotEmpty() -> {
                // Ran out of plan with a dead pet -> failure/retreat.
                api.logger.i("第 ${stageIndex + 1} 關：精靈陣亡且計畫用盡 → 失敗")
                if (!retriesExhausted()) { retries++; retreatAndRetry(); stepIndex = 0 }
                else api.logger.w("重試已達上限，等待其他狀態。")
            }
            next != null && next.isSwitch -> {
                switchPet(next.petIndex, alreadyOpen = true)  // screen already open
                stepIndex++
            }
            else -> {
                // No planned switch; deploy any pet so the battle can continue.
                deployAnyPet()
            }
        }
    }

    private fun onVictory() {
        battlesDone++
        stepIndex = 0
        retries = 0
        api.logger.i("勝利！已清 $battlesDone 關。")
        // The result screen has an intro animation that swallows early taps, so
        // keep tapping the valid blank spot until 「勝利」 is gone (also prevents
        // the same victory from being counted twice).
        var tries = 0
        while (exists(SeerTemplates.RESULT_WIN) && tries < 10) {
            api.click(SeerLayout.VICTORY_CONTINUE)
            api.sleep(delays.afterResultTap)
            api.refreshScreen()
            tries++
        }
        if (reachedLimit()) return
        val ok = api.waitUntil(NEXT_STAGE_TIMEOUT_MS, pollMs = 600) {
            exists(SeerTemplates.ENTER_BATTLE) || exists(SeerTemplates.BATTLE_ACTION)
        }
        if (!ok) api.logger.w("等待下一關畫面逾時。")
    }

    private fun cast(code: Int) {
        api.logger.i("第 ${stageIndex + 1} 關 步驟 ${stepIndex + 1}：${SeerLayout.labelFor(code)}")
        val where = SeerLayout.pointFor(code) ?: SeerLayout.SKILL_SLOTS[0]
        api.click(where)
        api.sleep(delays.afterSkill)
    }

    /** Switch to pet [n]. If [alreadyOpen], the 換精靈 screen is showing (pet died). */
    private fun switchPet(n: Int, alreadyOpen: Boolean) {
        val slot = SeerLayout.PET_SLOTS.getOrNull(n - 1) ?: return
        api.logger.i("換精靈 $n（${if (alreadyOpen) "已開啟" else "先開選單"}）")
        if (!alreadyOpen) { api.click(SeerLayout.PET.center); api.sleep(700); api.refreshScreen() }
        api.click(slot)
        api.sleep(300)
        api.swipe(slot, Location(slot.x, slot.y - SeerLayout.PET_DEPLOY_UP_PX), 450)
        api.sleep(delays.afterSwitch)
    }

    /** Deploy some alive pet (used to unstick / to enable 撤退 when a pet is dead). */
    private fun deployAnyPet() {
        var tries = 0
        while (exists(SeerTemplates.PET_DEFEATED) && tries < SeerLayout.PET_COUNT) {
            switchPet(tries + 1, alreadyOpen = true)
            api.refreshScreen()
            tries++
        }
    }

    /**
     * On the stage map: heal, then enter. The panel is usually already on the
     * right node, so try 進入戰鬥 directly first (tapping an already-open node
     * would toggle its panel and break entry). Only if that doesn't start the
     * battle do we pick the next 「等待挑戰」 node and enter that.
     */
    private fun enterNextStage() {
        healIfEnabled()
        if (tapEnterAndWait()) return   // battle started

        if (plan.advanceMap && templates.has(SeerTemplates.WAIT_CHALLENGE)) {
            api.find(templates.get(SeerTemplates.WAIT_CHALLENGE))?.let { node ->
                val icon = Location(node.region.center.x, node.region.center.y - SeerLayout.NODE_ABOVE_LABEL_PX)
                api.logger.i("選擇下一個可挑戰節點 @ $icon")
                api.click(icon); api.sleep(700); api.refreshScreen()
            }
        }
        tapEnterAndWait()
    }

    /**
     * Tap 「進入戰鬥」 and wait until the battle starts. The H5 button occasionally
     * swallows a single tap, so re-tap a few times until 進入戰鬥 is gone.
     * Returns true once the battle started.
     */
    private fun tapEnterAndWait(): Boolean {
        repeat(4) { attempt ->
            val enter = api.find(templates.get(SeerTemplates.ENTER_BATTLE)) ?: return attempt > 0
            api.logger.i("進入戰鬥（第 ${attempt + 1} 次）")
            api.click(enter.region.center, durationMs = 120)  // longer tap: H5 button catches it more reliably
            if (api.waitUntil(3500, pollMs = 400) { !exists(SeerTemplates.ENTER_BATTLE) }) {
                api.sleep(delays.afterEnter)   // let the battle intro settle before acting
                return true
            }
            api.refreshScreen()
        }
        return false
    }

    /** Failure recovery: retreat, skip the 失敗 UI, 繼續挑戰, heal, re-enter the same stage. */
    private fun retreatAndRetry() {
        api.logger.i("撤退重試（第 $retries 次）")
        // A dead pet on field greys out 撤退 — deploy someone first.
        deployAnyPet()

        // Precise single taps paced by [BattleDelays.afterRetreatTap] (per #9), one
        // per dialog, instead of a rapid multi-tap loop. If 「進入戰鬥」 hasn't shown
        // (variable dialog timing), repeat the whole sequence a few times as a
        // robust fallback — re-tapping a coordinate on an already-open dialog is a
        // harmless no-op.
        var attempt = 0
        api.refreshScreen()
        while (!exists(SeerTemplates.ENTER_BATTLE) && attempt < RETREAT_ATTEMPTS) {
            retreatTap(SeerLayout.RETREAT.center)          // 撤退
            retreatTap(SeerLayout.RETREAT_CONFIRM)         // 你確定要撤退嗎 → 確認
            retreatTap(SeerLayout.RETREAT_SUCCESS_CONFIRM) // 恭喜你成功撤退 → 確認
            retreatTap(SeerLayout.VICTORY_CONTINUE)        // 失敗UI：點空白略過
            retreatTap(SeerLayout.CONTINUE_CHALLENGE)      // 繼續挑戰 → 前置準備
            api.refreshScreen()
            attempt++
        }

        healIfEnabled()
        api.find(templates.get(SeerTemplates.ENTER_BATTLE))?.let {
            api.logger.i("重新進入戰鬥"); api.click(it)
        }
        api.waitUntil(ENTER_TIMEOUT_MS, pollMs = 500) { !exists(SeerTemplates.ENTER_BATTLE) }
    }

    /** One precise tap in the retreat flow, then wait the configured pacing delay. */
    private fun retreatTap(where: Location) {
        api.click(where)
        api.sleep(delays.afterRetreatTap)
    }

    private fun healIfEnabled() {
        if (plan.healBeforeBattle) {
            api.logger.i("精靈恢復")
            api.click(SeerLayout.HEAL)
            api.sleep(delays.afterHeal)
            api.refreshScreen()
        }
    }

    private fun reachedLimit(): Boolean {
        if (plan.maxBattles > 0 && battlesDone >= plan.maxBattles) return true
        val target = plan.clearsTarget()
        return target > 0 && battlesDone >= target
    }

    /** True once the per-stage retreat retries run out. 0 = unlimited (never exhausts). */
    private fun retriesExhausted(): Boolean =
        plan.maxRetriesPerStage > 0 && retries >= plan.maxRetriesPerStage

    private fun exists(id: String): Boolean = templates.has(id) && api.exists(templates.get(id))

    /** Round a match score to 3 dp for logs (locale-independent). */
    private fun fmt(v: Double): String = ((v * 1000).toInt() / 1000.0).toString()

    companion object {
        // Be patient: bosses with 先制+N make the enemy act several turns before our
        // 你的回合 appears (long intro with no recognizable state). Only give up after
        // ~90s of nothing, which still catches a genuine game freeze.
        private const val STUCK_LIMIT = 80
        private const val NEXT_STAGE_TIMEOUT_MS = 20_000L
        private const val TURN_SETTLE_MS = 12_000L
        private const val ENTER_TIMEOUT_MS = 12_000L
        // Robust fallback: repeat the paced single-tap retreat sequence at most this many times.
        private const val RETREAT_ATTEMPTS = 6
    }
}
