package com.autoseer.scripts

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanStageCountTest {

    @Test
    fun countsStages() {
        val plan = BattlePlanParser.parse("1 ; 3,1 ; Switch 4,5").plan
        assertEquals(2, plan.stages.size)
    }
}
