package com.autoseer.scripts

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanStepCountTest {

    @Test
    fun countsStepsInStage() {
        val plan = BattlePlanParser.parse("3,1*N,Switch 2").plan
        assertEquals(4, plan.stages[0].steps.size)
    }
}
