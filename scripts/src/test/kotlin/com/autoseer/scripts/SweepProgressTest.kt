package com.autoseer.scripts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the sweep bookkeeping/decision logic (the part that actually
 * had the win/lose bugs), driven purely — no screen/device needed.
 */
class SweepProgressTest {

    private fun plan(
        stages: Int = 5,
        maxRetries: Int = 5,
        startStage: Int = 1,
    ) = BattlePlan(
        stages = List(stages) { StagePlan() },
        maxRetriesPerStage = maxRetries,
        startStage = startStage,
    )

    @Test
    fun winAdvancesAndWrapsWithinFixedStages() {
        val p = SweepProgress(plan(stages = 5))
        assertEquals(0, p.currentStageIndex())
        p.onWin(); assertEquals(1, p.currentStageIndex())
        p.onWin(); p.onWin(); p.onWin()       // cleared 4
        assertEquals(4, p.currentStageIndex())
        p.onWin()                              // cleared 5 → wrap to stage 0
        assertEquals(0, p.currentStageIndex())
        assertEquals(5, p.battlesDone)
    }

    @Test
    fun syncStageIndexAlignsToReadStageThenAdvancesAndWraps() {
        // 影片27 情境：遊戲把因子續戰停在關4(index 3)，AutoSeer 讀畫面校正相位。
        val p = SweepProgress(plan(stages = 5))
        p.syncStageIndex(3)
        assertEquals(3, p.currentStageIndex())        // 關4（非勝場推算的關1）
        p.onWin(); assertEquals(4, p.currentStageIndex())  // 關5
        p.onWin(); assertEquals(0, p.currentStageIndex())  // 新輪關1（wrap）
        p.onWin(); assertEquals(1, p.currentStageIndex())  // 關2
        p.syncStageIndex(1); assertEquals(1, p.currentStageIndex())  // 再讀對齊＝no-op
        assertEquals(3, p.battlesDone)                // 勝場總數照計不受影響
    }

    @Test
    fun syncStageIndexHonorsStartStageAndWraps() {
        val p = SweepProgress(plan(stages = 5, startStage = 3))
        p.syncStageIndex(2); assertEquals(2, p.currentStageIndex())  // 讀到關3
        p.onWin(); assertEquals(3, p.currentStageIndex())            // 關4
        p.onWin(); assertEquals(4, p.currentStageIndex())            // 關5
        p.onWin(); assertEquals(0, p.currentStageIndex())            // wrap → 關1
    }

    @Test
    fun loseKeepsSameStageAndCountsRetry() {
        val p = SweepProgress(plan(maxRetries = 5))
        assertEquals(0, p.currentStageIndex())
        assertEquals(SweepProgress.LoseAction.RETRY, p.onLose())
        assertEquals(0, p.currentStageIndex())   // same stage
        assertEquals(1, p.retriesThisStage)
        assertEquals(0, p.battlesDone)
    }

    @Test
    fun winResetsPerStageRetries() {
        val p = SweepProgress(plan(maxRetries = 5))
        p.onLose(); p.onLose()
        assertEquals(2, p.retriesThisStage)
        p.onWin()
        assertEquals(0, p.retriesThisStage)
    }

    @Test
    fun retryLimitStopsInPlaceByDefault() {
        val p = SweepProgress(plan(maxRetries = 2), backToLobbyOnExhaust = false)
        assertEquals(SweepProgress.LoseAction.RETRY, p.onLose())        // 1
        assertEquals(SweepProgress.LoseAction.RETRY, p.onLose())        // 2
        assertEquals(SweepProgress.LoseAction.STOP_HERE, p.onLose())    // 3 > 2
    }

    @Test
    fun retryLimitStopsToLobbyWhenEnabled() {
        val p = SweepProgress(plan(maxRetries = 2), backToLobbyOnExhaust = true)
        p.onLose(); p.onLose()
        assertEquals(SweepProgress.LoseAction.STOP_TO_LOBBY, p.onLose())
    }

    @Test
    fun zeroMaxRetriesNeverStops() {
        val p = SweepProgress(plan(maxRetries = 0))
        repeat(20) { assertEquals(SweepProgress.LoseAction.RETRY, p.onLose()) }
    }

    @Test
    fun startStageMidLoopIndexes() {
        val p = SweepProgress(plan(stages = 5, startStage = 3))
        assertEquals(2, p.currentStageIndex())   // 起始關 3 → index 2
        p.onWin(); assertEquals(3, p.currentStageIndex())
        p.onWin(); assertEquals(4, p.currentStageIndex())
        p.onWin(); assertEquals(0, p.currentStageIndex())  // wrap
    }

    @Test
    fun factorSwitchRestartsStageCursorButKeepsTotal() {
        val p = SweepProgress(plan(stages = 5))
        p.onWin(); p.onWin(); p.onWin()          // cleared 3 → stage index 3
        assertEquals(3, p.currentStageIndex())
        assertEquals(3, p.battlesDone)
        p.onFactorSwitch()                        // 新因子：關卡游標歸零
        assertEquals(0, p.currentStageIndex())
        assertEquals(3, p.battlesDone)            // 總數保留
        p.onWin(); assertEquals(1, p.currentStageIndex())  // 新因子內照常前進
        assertEquals(4, p.battlesDone)
    }

    @Test
    fun factorSwitchResetsPerStageRetries() {
        val p = SweepProgress(plan(maxRetries = 5))
        p.onLose(); p.onLose()
        assertEquals(2, p.retriesThisStage)
        p.onFactorSwitch()
        assertEquals(0, p.retriesThisStage)
    }
}
