package com.autoseer.scripts

import com.autoseer.libautomata.IPattern
import com.autoseer.libautomata.Region
import com.autoseer.libautomata.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering logic for the multi-factor sweep — pure, no device. The reference
 * images are irrelevant here, so a trivial [FakePattern] stands in.
 */
class FactorSweepProgressTest {

    private class FakePattern : IPattern {
        override val width = 1
        override val height = 1
        override fun crop(region: Region): IPattern = this
        override fun resize(size: Size): IPattern = this
        override fun meanGray(): Double = 0.0
        override fun save(path: String) {}
    }

    private fun targets(n: Int) = List(n) { FactorTarget("f${it + 1}", FakePattern()) }

    @Test
    fun emptyHasNoCurrent() {
        val p = FactorSweepProgress(emptyList())
        assertEquals(0, p.size)
        assertFalse(p.hasCurrent())
        assertNull(p.current())
        assertFalse(p.hasNext())
        assertNull(p.advance())
    }

    @Test
    fun walksInOrderThenFinishes() {
        val p = FactorSweepProgress(targets(3))
        assertEquals("f1", p.current()?.name)
        assertTrue(p.hasNext())
        assertEquals("f2", p.advance()?.name)
        assertEquals("f3", p.advance()?.name)
        assertFalse(p.hasNext())
        assertNull(p.advance())            // past the end
        assertFalse(p.hasCurrent())
        assertNull(p.advance())            // stays exhausted, no crash
    }

    @Test
    fun startIndexRespected() {
        val p = FactorSweepProgress(targets(3), startIndex = 1)
        assertEquals(1, p.index)
        assertEquals("f2", p.current()?.name)
        assertEquals("f3", p.advance()?.name)
    }

    @Test
    fun negativeStartIndexClampsToZero() {
        val p = FactorSweepProgress(targets(2), startIndex = -5)
        assertEquals(0, p.index)
        assertEquals("f1", p.current()?.name)
    }
}
