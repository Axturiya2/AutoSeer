package com.autoseer.scripts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanParsingTest {

    private val groups = mapOf(
        "尤莉米特" to
            "1 ; 3,1 ; 3,1 ; Switch 4,Switch 1,5,1*N ; " +
            "Switch 2,1*N(避免因為敵方未出招),Switch 3,4*N,Switch 1,1*N,Switch 5,1*N,Switch 6,2*N",
        "萬里春北鳥" to
            "3,1*N ; 3,1*N ; 4*N ; Switch 2,1*N,Switch 3,4*N,Switch 1,1*N ; " +
            "Switch 2,1*N,Switch 3,4*N,Switch 1,1*N,Switch 5,1*N,Switch 6,2*N",
        "靈巢之主索傑爾德" to
            "3,1 ; 3,1 ; 3,1 ; 4,4 ; " +
            "Switch 2,1*N,Switch 3,4*N,Switch 1,1*N,Switch 5,1*N,Switch 6,2*N",
    )

    @Test
    fun parsesAllGroupsWithoutWarnings() {
        for ((name, text) in groups) {
            val r = BattlePlanParser.parse(text)
            println("== $name ==")
            println(BattlePlanParser.describe(r.plan))
            if (r.warnings.isNotEmpty()) println("  警告: ${r.warnings}")
            assertEquals("$name 應為 5 關", 5, r.plan.stages.size)
            assertTrue("$name 不應有解析警告: ${r.warnings}", r.warnings.isEmpty())
        }
    }

    @Test
    fun switchAndUntilDefeatDecodeCorrectly() {
        val r = BattlePlanParser.parse("Switch 2,1*N,Switch 3")
        val steps = r.plan.stages[0].steps
        assertEquals(3, steps.size)
        assertTrue(steps[0].isSwitch); assertEquals(2, steps[0].petIndex)
        assertEquals(1, steps[1].code); assertTrue(steps[1].untilDefeat)
        assertTrue(steps[2].isSwitch); assertEquals(3, steps[2].petIndex)
    }

    @Test
    fun serializeRoundTripsCodes() {
        // editor codes: skill 3, switch pet 2 (code 12), skill 1
        val text = BattlePlanParser.serialize(listOf(listOf(3, 12, 1)))
        assertEquals("3, Switch 2, 1", text)
        val back = BattlePlanParser.toStages(text)
        assertEquals(listOf(listOf(3, 12, 1)), back)
    }

    @Test
    fun loopWrapsWithinFixedStageCount() {
        // 精靈因子：5 關、跑 3 輪。清完第5關要回第1關，而非跑到不存在的「關6」。
        val p = BattlePlanParser.parse("1;1;1;1;1", loops = 3).plan
        assertEquals(5, p.stagesPerLoop)
        assertEquals(0, p.stageIndexFor(0))
        assertEquals(4, p.stageIndexFor(4))
        assertEquals(0, p.stageIndexFor(5))   // 清完第5關 → 回關1
        assertEquals(4, p.stageIndexFor(9))
        assertEquals(15, p.clearsTarget())    // 5*3
    }

    @Test
    fun loopStartStageMidLoopConsumesOneAttempt() {
        val p = BattlePlanParser.parse("1;1;1;1;1", loops = 3, startStage = 3).plan
        assertEquals(2, p.stageIndexFor(0))   // 關3
        assertEquals(4, p.stageIndexFor(2))   // 關5
        assertEquals(0, p.stageIndexFor(3))   // wrap → 關1
        assertEquals(13, p.clearsTarget())    // 5*3 - 2
    }

    @Test
    fun emptyPlanHasNoLoopLimitNorWrap() {
        val p = BattlePlanParser.parse("", loops = 3).plan
        assertEquals(0, p.stagesPerLoop)
        assertEquals(7, p.stageIndexFor(7))   // 無關卡計畫 → 不環繞
        assertEquals(0, p.clearsTarget())     // 只受 maxBattles 限制
    }

    @Test
    fun battleDelaysDefaultToHalfSecond() {
        val d = BattleDelays()
        assertEquals(500L, d.afterHeal)
        assertEquals(500L, d.afterEnter)
        assertEquals(500L, d.afterSkill)
        assertEquals(500L, d.afterSwitch)
        assertEquals(500L, d.afterResultTap)
        assertEquals(500L, d.afterRetreatTap)
    }

    @Test
    fun serializeStepsPreservesUntilDefeat() {
        val stages = listOf(
            listOf(
                Step(SeerLayout.PET_CODE_BASE + 2),  // Switch 2
                Step(1, untilDefeat = true),         // 1*N
                Step(SeerLayout.PET_CODE_BASE + 3),  // Switch 3
                Step(4, untilDefeat = true),         // 4*N
            ),
        )
        val text = BattlePlanParser.serializeSteps(stages)
        assertEquals("Switch 2, 1*N, Switch 3, 4*N", text)
    }

    @Test
    fun editorStepRoundTripKeepsUntilDefeatForEveryPreset() {
        // Regression: the old codes-only editor model dropped *N, silently
        // downgrading X*N to a single cast when a preset was opened and saved.
        for (preset in SeerPresets.ALL) {
            val original = BattlePlanParser.parse(preset.plan).plan
            // Load into the step-aware editor model, then serialize back out.
            val editorModel = BattlePlanParser.toStepStages(preset.plan)
            val reSaved = BattlePlanParser.serializeSteps(editorModel)
            val reparsed = BattlePlanParser.parse(reSaved).plan

            assertEquals("${preset.name} 關數應一致", original.stages.size, reparsed.stages.size)
            original.stages.forEachIndexed { si, stage ->
                assertEquals(
                    "${preset.name} 關${si + 1} 步驟（含 *N）應完整往返",
                    stage.steps,
                    reparsed.stages[si].steps,
                )
            }
        }
    }
}
