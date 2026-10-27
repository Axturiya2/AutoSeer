package com.autoseer.scripts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MultiSelection] / [ScriptBatch] — 腳本清單長按多選後的匯出／刪除（FR-10）。
 * 測試以 id 字串代替 SeerScript（後者在 app 模組）。
 */
class ScriptBatchTest {

    private data class S(val id: String)

    private val list = listOf(S("a"), S("b"), S("c"), S("d"))

    @Test
    fun longPressEntersSelectionWithThatItem() {
        val sel = MultiSelection()
        assertFalse(sel.active)
        sel.start("b")
        assertTrue(sel.active)
        assertTrue("b" in sel)
        assertEquals(1, sel.size)
    }

    @Test
    fun longPressOnCheckedItemDoesNotUncheck() {
        val sel = MultiSelection()
        sel.start("b"); sel.start("b")
        assertTrue("b" in sel)
    }

    @Test
    fun uncheckingLastItemLeavesSelectionMode() {
        val sel = MultiSelection()
        sel.start("a")
        assertTrue(sel.toggle("c"))
        assertFalse(sel.toggle("a"))
        assertTrue(sel.active)
        assertFalse(sel.toggle("c"))
        assertFalse(sel.active)
    }

    @Test
    fun toggleAllSelectsEverythingThenClears() {
        val sel = MultiSelection()
        sel.start("a")
        val ids = list.map { it.id }
        sel.toggleAll(ids)
        assertEquals(ids.toSet(), sel.selected)
        sel.toggleAll(ids)
        assertFalse(sel.active)
    }

    @Test
    fun retainDropsIdsNoLongerInList() {
        val sel = MultiSelection()
        sel.start("a"); sel.toggle("x")
        sel.retain(listOf("a", "b"))
        assertEquals(setOf("a"), sel.selected)
    }

    @Test
    fun pickKeepsListOrderNotTapOrder() {
        val picked = ScriptBatch.pick(list, linkedSetOf("d", "a"), S::id)
        assertEquals(listOf(S("a"), S("d")), picked)
    }

    @Test
    fun removeKeepsSelectedWhenNotDeleted() {
        val r = ScriptBatch.remove(list, setOf("a", "c"), selectedId = "b", idOf = S::id)
        assertEquals(listOf(S("b"), S("d")), r.remaining)
        assertEquals("b", r.selectedId)
        assertEquals(2, r.removed)
    }

    @Test
    fun removingSelectedFallsBackToFirstRemaining() {
        val r = ScriptBatch.remove(list, setOf("a", "b"), selectedId = "b", idOf = S::id)
        assertEquals("c", r.selectedId)
    }

    @Test
    fun removingEverythingClearsSelected() {
        val r = ScriptBatch.remove(list, list.map { it.id }.toSet(), selectedId = "a", idOf = S::id)
        assertTrue(r.remaining.isEmpty())
        assertNull(r.selectedId)
        assertEquals(4, r.removed)
    }

    @Test
    fun unknownIdsAreIgnored() {
        val r = ScriptBatch.remove(list, setOf("zz"), selectedId = null, idOf = S::id)
        assertEquals(list, r.remaining)
        assertNull(r.selectedId)
        assertEquals(0, r.removed)
    }
}
