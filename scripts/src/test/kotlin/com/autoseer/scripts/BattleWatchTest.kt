package com.autoseer.scripts

import com.autoseer.scripts.BattleWatch.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [BattleWatch] (一般戰鬥待命決策，FR-11) and [RunMode]. */
class BattleWatchTest {

    @Test
    fun battleBeatsResultAndRecover() {
        val w = BattleWatch()
        assertEquals(Action.FIGHT, w.decide(inBattle = true, resultShown = true, recoverShown = true))
    }

    @Test
    fun resultResidualBeatsRecover() {
        val w = BattleWatch()
        assertEquals(Action.DISMISS_RESULT, w.decide(inBattle = false, resultShown = true, recoverShown = true))
    }

    @Test
    fun nothingRecognized_idles() {
        assertEquals(Action.IDLE, BattleWatch().decide(inBattle = false, resultShown = false, recoverShown = false))
    }

    @Test
    fun healOnlyOncePerBattle() {
        val w = BattleWatch()
        assertEquals(Action.HEAL, w.decide(false, false, recoverShown = true))
        w.onHealTried()
        // 按完按鈕還在（或沒反應）→ 不再狂點
        assertEquals(Action.IDLE, w.decide(false, false, recoverShown = true))
        w.onFightEnded()
        assertEquals(Action.HEAL, w.decide(false, false, recoverShown = true))
    }

    @Test
    fun startedInBattle_onlyFirstFightIsMidBattle() {
        val w = BattleWatch()
        w.decide(inBattle = true, resultShown = false, recoverShown = false)
        assertTrue(w.midBattle)
        w.onFightEnded()
        w.decide(inBattle = true, resultShown = false, recoverShown = false)
        assertFalse(w.midBattle)
    }

    @Test
    fun startedOutsideBattle_firstFightIsFresh() {
        val w = BattleWatch()
        w.decide(inBattle = false, resultShown = false, recoverShown = false)
        w.decide(inBattle = true, resultShown = false, recoverShown = false)
        assertFalse(w.midBattle)
    }

    @Test
    fun countsBattles() {
        val w = BattleWatch()
        w.onFightEnded(); w.onFightEnded()
        assertEquals(2, w.battlesDone)
    }

    @Test
    fun runModeKeys_unknownOrNotReadyFallBackToFactor() {
        assertEquals(RunMode.BATTLE, RunMode.fromKey("battle"))
        assertEquals(RunMode.FACTOR, RunMode.fromKey("seer_factor"))
        assertEquals(RunMode.FACTOR, RunMode.fromKey(null))
        assertEquals(RunMode.FACTOR, RunMode.fromKey("gone"))
        // 每日還沒開發：就算存過也不會被選成要跑的模式
        assertEquals(RunMode.FACTOR, RunMode.fromKey("daily"))
    }
}
