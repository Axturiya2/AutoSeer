package com.autoseer.scripts

import com.autoseer.libautomata.AutomataApi
import com.autoseer.libautomata.Script
import com.autoseer.libautomata.Templates

/**
 * 一般戰鬥（FR-11）：玩家自己進戰鬥，AS 只負責按技能。按「開始」後持續待命直到按停止：
 *
 *   出現「你的回合」／「已戰敗」→ 依 [steps] 打（[BattleTurnRunner]，排序用完就一直放
 *     [defaultCode]）→ 勝利／失敗 → 點掉結算畫面
 *   看到「精靈恢復」鈕 → 按一次（每場結束後只試一次），玩家接著自己按開始挑戰
 *   其他畫面 → 待命
 *
 * 不撤退：不知道是什麼戰鬥。技能全放不出或換不了精靈時交還手動、結束腳本。
 * 決策在 [BattleWatch]（可單測），這裡只負責看畫面和點擊。
 */
class BattleWatchScript(
    api: AutomataApi,
    private val templates: Templates,
    private val steps: List<Step>,
    private val defaultCode: Int,
    private val delays: BattleDelays = BattleDelays.default(),
    /** 每打完一場回報累計場數，供懸浮視窗顯示。 */
    private val onBattleDone: (battles: Int) -> Unit = {},
) : Script(api) {

    override val name = "一般戰鬥 (BattleWatchScript)"

    private val m = SeerModules(api, templates)
    private val runner = BattleTurnRunner(api, templates, delays)
    private val watch = BattleWatch()

    override fun run() {
        api.logger.i("一般戰鬥開始：技能排序 ${steps.size} 步，用完改放${SeerLayout.labelFor(defaultCode)}")
        var idleLogged = false
        while (true) {
            api.refreshScreen()
            val action = watch.decide(
                inBattle = m.exists(SeerTemplates.BATTLE_ACTION) || m.exists(SeerTemplates.PET_DEFEATED),
                resultShown = RESULT_MARKS.any { m.exists(it) },
                recoverShown = m.exists(SeerTemplates.PET_RECOVER),
            )
            if (action != BattleWatch.Action.IDLE) idleLogged = false
            when (action) {
                BattleWatch.Action.FIGHT -> if (!fight()) break
                BattleWatch.Action.DISMISS_RESULT -> {
                    api.click(SeerLayout.VICTORY_CONTINUE); api.sleep(delays.afterResultTap)
                }
                BattleWatch.Action.HEAL -> heal()
                BattleWatch.Action.IDLE -> {
                    if (!idleLogged) {
                        api.logger.i("待命中：等「你的回合」…（已打 ${watch.battlesDone} 場）")
                        idleLogged = true
                    }
                    api.sleep(IDLE_POLL_MS)
                }
            }
        }
    }

    /** Fight the battle on screen. False = hand back to the player (stop the script). */
    private fun fight(): Boolean {
        val label = "（一般戰鬥第 ${watch.battlesDone + 1} 場${if (watch.midBattle) "，中途接手" else ""}）"
        when (runner.fight(steps, defaultCode, label, midBattle = watch.midBattle, keepFighting = true)) {
            BattleTurnRunner.Result.WIN -> endBattle("勝利")
            BattleTurnRunner.Result.LOSE -> endBattle("失敗")
            BattleTurnRunner.Result.PLAN_EXHAUSTED -> {
                api.logger.w("技能都放不出或換不了精靈 → 交還手動，一般戰鬥結束（已打 ${watch.battlesDone} 場）")
                return false
            }
            BattleTurnRunner.Result.STUCK -> api.logger.w("戰鬥中畫面認不出來，回待命")
        }
        return true
    }

    private fun endBattle(outcome: String) {
        watch.onFightEnded()
        onBattleDone(watch.battlesDone)
        api.logger.i("$outcome！已打 ${watch.battlesDone} 場 → 點擊繼續")
        if (!m.waitAppear(SeerTemplates.TAP_CONTINUE, WAIT_UI_MS)) {
            api.logger.w("未見『點擊任意位置繼續』字樣，仍嘗試點擊繼續")
        }
        m.tapPointUntilGone(SeerTemplates.TAP_CONTINUE, SeerLayout.VICTORY_CONTINUE, DISMISS_TRIES, delays.afterResultTap)
    }

    /**
     * Tap 「精靈恢復」 where it was found (一般關卡版面不一定跟因子關卡一樣，所以不用
     * 固定座標) until a 恢復 字樣 shows; confirm the 每日首次 tip if it pops up.
     * Tried once per battle either way, so a button that doesn't respond isn't spammed.
     */
    private fun heal() {
        watch.onHealTried()
        api.logger.i("偵測到「精靈恢復」→ 自動恢復")
        val done = listOf(SeerTemplates.RECOVER_FULL, SeerTemplates.RECOVER_CANNOT)
        val first = m.tapUntilAppearsAny(listOf(SeerTemplates.FIRST_RECOVER_TIP) + done, RECOVER_TRIES) {
            m.tapIfPresent(SeerTemplates.PET_RECOVER)
        }
        val ok = when (first) {
            SeerTemplates.FIRST_RECOVER_TIP -> {
                api.click(SeerLayout.FIRST_RECOVER_CONFIRM)
                m.waitAppearAny(done, WAIT_UI_MS) != null
            }
            null -> false
            else -> true
        }
        if (ok) {
            api.logger.i("精靈已恢復，請按開始挑戰")
            api.sleep(delays.afterHeal)
        } else {
            api.logger.w("按了精靈恢復但沒看到恢復完成字樣，這場先不再按")
        }
    }

    companion object {
        private val RESULT_MARKS = listOf(SeerTemplates.TAP_CONTINUE, SeerTemplates.RESULT_WIN, SeerTemplates.RESULT_LOSE)
        private const val IDLE_POLL_MS = 1_000L
        private const val WAIT_UI_MS = 12_000L
        private const val DISMISS_TRIES = 5
        private const val RECOVER_TRIES = 3
    }
}
