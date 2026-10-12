package com.autoseer.scripts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [DeployDecision] — the "which pet to send in when one dies"
 * choice, driven purely (no screen/device). Uses the user's 精靈因子 script shape:
 * Switch6, Switch2, cast2, Switch3, cast2, Switch4, cast2.
 */
class DeployDecisionTest {

    private fun sw(n: Int) = Step(SeerLayout.PET_CODE_BASE + n)
    private fun cast(code: Int, untilDefeat: Boolean = false) = Step(code, untilDefeat)

    private val script = listOf(sw(6), sw(2), cast(2), sw(3), cast(2), sw(4), cast(2))

    @Test
    fun deathWhenNextStepIsSwitch_bringsInScriptedPet() {
        // 蒂朵 (idx2 cast 放完 → stepIndex 3) 陣亡；下一步 Switch3 → 換精靈3、index→4
        val d = DeployDecision.decide(script, 3)
        assertEquals(DeployDecision.Action.Scripted(3), d.action)
        assertEquals(4, d.nextStepIndex)
    }

    @Test
    fun deathWhenNextStepIsCast_fallsBackWithoutAdvancing() {
        // stepIndex 指向 cast（意外被秒的錯位時機）→ 決策 b 補位、index 不動
        val d = DeployDecision.decide(script, 2)
        assertEquals(DeployDecision.Action.Fallback, d.action)
        assertEquals(2, d.nextStepIndex)
    }

    @Test
    fun planExhausted_retreats() {
        val d = DeployDecision.decide(script, script.size)
        assertEquals(DeployDecision.Action.PlanExhausted, d.action)
        assertEquals(script.size, d.nextStepIndex)
    }

    @Test
    fun untilDefeatStep_isConsumedThenNextSwitchUsed() {
        // Switch1, cast2*N, Switch2 —— *N 打到陣亡，消耗它再看下一步 Switch2
        val s = listOf(sw(1), cast(2, untilDefeat = true), sw(2))
        val d = DeployDecision.decide(s, 1)
        assertEquals(DeployDecision.Action.Scripted(2), d.action)
        assertEquals(3, d.nextStepIndex)
    }

    @Test
    fun untilDefeatAsLastStep_thenExhausted() {
        val s = listOf(sw(1), cast(2, untilDefeat = true))
        val d = DeployDecision.decide(s, 1)
        assertEquals(DeployDecision.Action.PlanExhausted, d.action)
        assertEquals(2, d.nextStepIndex)
    }

    @Test
    fun emptyPlan_fallsBack() {
        // 沒有各關計畫（spam 預設技能）→ 陣亡補位走 fallback、不推進
        val d = DeployDecision.decide(emptyList(), 0)
        assertEquals(DeployDecision.Action.Fallback, d.action)
        assertEquals(0, d.nextStepIndex)
    }
}
