package com.autoseer.scripts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [SkillGuard] — substitute choice when a skill won't fire
 * (PP 用盡／被封印). Shape from the user's 精靈因子 scripts: `Switch 1, 1*N`
 * spams 招牌技 (slot 1) with 預設技能格 = 2.
 */
class SkillGuardTest {

    @Test
    fun usableSkillIsCastAsIs() {
        assertEquals(1, SkillGuard(defaultCode = 2).pick(1))
    }

    @Test
    fun blockedSkillFallsBackToDefaultSlot() {
        val g = SkillGuard(defaultCode = 2)
        g.block(1)                       // 招牌技 PP 用盡
        assertEquals(2, g.pick(1))
    }

    @Test
    fun blockedDefaultFallsBackToFirstUsableInOrder() {
        val g = SkillGuard(defaultCode = 2)
        g.block(1); g.block(2)
        assertEquals(3, g.pick(1))
        assertEquals(3, g.pick(2))
    }

    @Test
    fun wantedStillUsableEvenIfOthersBlocked() {
        val g = SkillGuard(defaultCode = 2)
        g.block(2)
        assertEquals(4, g.pick(4))
    }

    @Test
    fun allBlockedReturnsNull() {
        val g = SkillGuard(defaultCode = 2)
        (1..SeerLayout.SKILL_COUNT).forEach(g::block)
        assertNull(g.pick(3))
    }

    @Test
    fun resetOnNewPetMakesEverySlotUsableAgain() {
        val g = SkillGuard(defaultCode = 2)
        g.block(1); g.block(2)
        g.reset()                        // 換精靈上場
        assertEquals(1, g.pick(1))
        assertEquals(emptySet<Int>(), g.blockedCodes)
    }

    @Test
    fun nonSkillCodesPassThroughAndCannotBeBlocked() {
        val g = SkillGuard(defaultCode = 2)
        (1..SeerLayout.SKILL_COUNT).forEach(g::block)
        g.block(7)                       // 道具：不是技能格
        assertEquals(7, g.pick(7))
        assertEquals(setOf(1, 2, 3, 4, 5), g.blockedCodes)
    }

    @Test
    fun invalidDefaultSlotIsSkipped() {
        val g = SkillGuard(defaultCode = 0)
        g.block(1)
        assertEquals(2, g.pick(1))
    }
}
