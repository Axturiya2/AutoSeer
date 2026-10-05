package com.autoseer.scripts

/**
 * Pure sweep bookkeeping for [SeerFactorScript]: which stage to fight next, and
 * what to do after a win / loss. Kept free of any screen/Android dependency so
 * the decision logic that actually had bugs (win→advance, lose→retry-same-stage,
 * retry-limit→stop) is unit-testable without a device.
 */
class SweepProgress(
    private var plan: BattlePlan,
    private val backToLobbyOnExhaust: Boolean = false,
) {
    /** What the caller should do after a loss/retreat. */
    enum class LoseAction { RETRY, STOP_TO_LOBBY, STOP_HERE }

    /** Stages cleared so far this run. */
    var battlesDone = 0
        private set

    /** Consecutive failures on the current stage (reset on a win). */
    var retriesThisStage = 0
        private set

    /**
     * [battlesDone] value at which the current factor started, so the per-stage
     * plan cursor is relative to *this* factor (multi-factor 銜接): switching to a
     * new factor restarts its stage cycle at 關1 while [battlesDone] keeps counting
     * the run total. 0 in single-factor mode (never switched) — behaviour unchanged.
     */
    private var factorStageBaseline = 0

    /** 0-based plan index of the stage to fight now (wraps within a loop, FR-1). */
    fun currentStageIndex(): Int = plan.stageIndexFor(battlesDone - factorStageBaseline)

    /**
     * 用畫面讀到的「真實關卡 index」(0-based) 校正關卡相位：調整 [factorStageBaseline] 使
     * [currentStageIndex] 立刻等於 [stageIdx]。用於因子續戰停在半途（遊戲從關4接、而非關1）
     * 時，把純勝場推算的相位拉回地面真相。之後每贏一關 [battlesDone]++ 仍會自然往下一關推進，
     * 下一關再讀一次即再校正，任何來源的偏移都不累積。
     */
    fun syncStageIndex(stageIdx: Int) {
        factorStageBaseline = battlesDone + (plan.startStage - 1) - stageIdx
    }

    /**
     * Moving on to a new factor: restart the per-stage plan cursor from this
     * factor's first stage, and clear per-stage retries. [battlesDone] (the run
     * total shown in the UI) is intentionally kept.
     *
     * [newPlan] swaps the active battle plan for the new factor (per-factor scripts,
     * 情況2); null keeps the current plan (single-plan sweep, 情況1 — unchanged).
     */
    fun onFactorSwitch(newPlan: BattlePlan? = null) {
        if (newPlan != null) plan = newPlan
        factorStageBaseline = battlesDone
        retriesThisStage = 0
    }

    /** Won the current stage: advance to the next, reset per-stage retries. */
    fun onWin() {
        battlesDone++
        retriesThisStage = 0
    }

    /**
     * Lost or retreated: count a retry and decide. RETRY = re-fight the same
     * stage (battlesDone unchanged); STOP_* = give up after exceeding the
     * per-stage retry limit ([BattlePlan.maxRetriesPerStage]; <=0 = unlimited),
     * to lobby or in place depending on [backToLobbyOnExhaust].
     */
    fun onLose(): LoseAction {
        retriesThisStage++
        val limit = plan.maxRetriesPerStage
        if (limit > 0 && retriesThisStage > limit) {
            return if (backToLobbyOnExhaust) LoseAction.STOP_TO_LOBBY else LoseAction.STOP_HERE
        }
        return LoseAction.RETRY
    }
}
