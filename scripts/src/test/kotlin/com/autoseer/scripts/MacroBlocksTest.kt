package com.autoseer.scripts

import com.autoseer.libautomata.Region
import com.autoseer.scripts.MacroStep.Else
import com.autoseer.scripts.MacroStep.End
import com.autoseer.scripts.MacroStep.FindClick
import com.autoseer.scripts.MacroStep.IfSeen
import com.autoseer.scripts.MacroStep.Repeat
import com.autoseer.scripts.MacroStep.Tap
import com.autoseer.scripts.MacroStep.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [MacroBlocks]（FR-12 自製腳本）：區塊配對、參數檢查、縮排與描述文字。 */
class MacroBlocksTest {

    private val images = listOf(
        MacroImage("a", "開始", Region(100, 100, 80, 40)),
        MacroImage("b", "", Region(600, 300, 60, 60)),
    )

    private fun macro(vararg steps: MacroStep) = Macro(steps.toList(), images)

    private fun errorsOf(vararg steps: MacroStep) = MacroBlocks.parse(macro(*steps)).errors

    @Test
    fun nestedBlocksBuildTree() {
        val p = MacroBlocks.parse(
            macro(
                FindClick("a"),          // 1
                IfSeen("b"),             // 2
                Tap(10, 10),             // 3
                Else,                    // 4
                Wait(1000),              // 5
                End,                     // 6
                Repeat(3),               // 7
                Tap(20, 20),             // 8
                End,                     // 9
            ),
        )
        assertTrue(p.errors.toString(), p.ok)
        assertEquals(3, p.nodes.size)
        assertEquals(MacroNode.Action(1, FindClick("a")), p.nodes[0])
        val iff = p.nodes[1] as MacroNode.If
        assertEquals(2, iff.stepNo)
        assertEquals(listOf(MacroNode.Action(3, Tap(10, 10))), iff.then)
        assertEquals(listOf(MacroNode.Action(5, Wait(1000))), iff.otherwise)
        val loop = p.nodes[2] as MacroNode.Loop
        assertEquals(7, loop.stepNo)
        assertEquals(listOf(MacroNode.Action(8, Tap(20, 20))), loop.body)
    }

    @Test
    fun ifWithoutElseHasEmptyOtherwise() {
        val p = MacroBlocks.parse(macro(IfSeen("a"), Repeat(2), Tap(1, 1), End, End))
        assertTrue(p.ok)
        val iff = p.nodes.single() as MacroNode.If
        assertEquals(emptyList<MacroNode>(), iff.otherwise)
        assertEquals(1, (iff.then.single() as MacroNode.Loop).body.size)
    }

    @Test
    fun elseOutsideIfIsError() {
        assertEquals(listOf(MacroError(2, "「否則」要放在「如果看到」和「結束」之間")), errorsOf(Tap(1, 1), Else))
        // 「重複」裡直接放否則也不行
        assertEquals(3, errorsOf(Repeat(2), Tap(1, 1), Else, End).single().stepNo)
    }

    @Test
    fun secondElseIsError() {
        assertEquals(
            listOf(MacroError(5, "同一個「如果看到」只能有一個「否則」")),
            errorsOf(IfSeen("a"), Tap(1, 1), Else, Tap(2, 2), Else, End),
        )
    }

    @Test
    fun extraEndIsError() {
        assertEquals(2, errorsOf(Tap(1, 1), End).single().stepNo)
    }

    @Test
    fun unclosedBlockPointsAtItsOpening() {
        assertEquals(
            listOf(MacroError(2, "「重複 2 次」少了對應的「結束」")),
            errorsOf(Tap(1, 1), Repeat(2), Tap(3, 3)),
        )
    }

    @Test
    fun emptyRepeatIsErrorButEmptyIfIsFine() {
        assertEquals(listOf(MacroError(1, "「重複」裡面沒有任何步驟")), errorsOf(Repeat(Repeat.FOREVER), End))
        assertTrue(errorsOf(IfSeen("a"), End).isEmpty())
    }

    @Test
    fun stepParametersAreChecked() {
        val errs = errorsOf(
            FindClick("zzz"),                // 1 圖不存在
            FindClick("a", timeoutMs = 0),   // 2
            IfSeen("a", waitMs = -1),        // 3
            End,                             // 4
            Tap(1280, 0),                    // 5 超出右邊
            Tap(0, -1),                      // 6
            Wait(0),                         // 7
            Repeat(-1),                      // 8
            Tap(1, 1),                       // 9
            End,                             // 10
        )
        assertEquals(listOf(1, 2, 3, 5, 6, 7, 8), errs.map { it.stepNo })
        assertEquals("第 1 步：找不到圖片「zzz」（可能已刪除）", errs[0].toString())
    }

    @Test
    fun emptyMacroAndBadImageRegionAreErrors() {
        assertEquals(listOf(MacroError(null, "腳本沒有任何步驟")), MacroBlocks.parse(macro()).errors)
        val bad = Macro(listOf(Tap(1, 1)), listOf(MacroImage("x", "邊緣", Region(1250, 700, 40, 40))))
        assertEquals("圖片「邊緣」的框選範圍超出畫面", MacroBlocks.parse(bad).errors.single().toString())
    }

    @Test
    fun depthsIndentBlocksAndAlignElseAndEnd() {
        val steps = listOf(FindClick("a"), IfSeen("b"), Repeat(2), Tap(1, 1), End, Else, Wait(500), End, Tap(2, 2))
        assertEquals(listOf(0, 0, 1, 2, 1, 0, 1, 0, 0), MacroBlocks.depths(steps))
    }

    @Test
    fun depthsNeverGoNegativeOnStrayMarkers() {
        assertEquals(listOf(0, 0, 0), MacroBlocks.depths(listOf(End, Else, Tap(1, 1))))
    }

    @Test
    fun describeUsesImageNamesAndReadableUnits() {
        val m = macro()
        assertEquals("找圖並點擊「開始」", m.describe(FindClick("a")))
        assertEquals("如果看到「b」", m.describe(IfSeen("b")))   // 沒取名退回 id
        assertEquals("等待 2 秒", m.describe(Wait(2000)))
        assertEquals("等待 1.5 秒", m.describe(Wait(1500)))
        assertEquals("一直重複", m.describe(Repeat(Repeat.FOREVER)))
        assertEquals("重複 3 次", m.describe(Repeat(3)))
        assertEquals("點擊 (640, 360)", m.describe(Tap(640, 360)))
    }

    @Test
    fun nearSearchRegionExpandsAndClampsToScreen() {
        assertEquals(Region(0, 0, 280, 240), images[0].searchRegion(full = false))
        assertEquals(
            Region(1100, 580, 180, 140),
            MacroImage("c", "", Region(1200, 680, 80, 40)).searchRegion(full = false),
        )
        assertNull(images[0].searchRegion(full = true))
    }
}
