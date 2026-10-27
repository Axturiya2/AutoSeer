package com.autoseer.scripts

import com.autoseer.libautomata.Region
import com.autoseer.libautomata.Size
import com.autoseer.scripts.MacroStep.Else
import com.autoseer.scripts.MacroStep.End
import com.autoseer.scripts.MacroStep.FindClick
import com.autoseer.scripts.MacroStep.IfSeen
import com.autoseer.scripts.MacroStep.OnMiss
import com.autoseer.scripts.MacroStep.Repeat
import com.autoseer.scripts.MacroStep.Tap
import com.autoseer.scripts.MacroStep.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [MacroCodec]（FR-12 自製腳本存檔格式）與圖片 id／截圖正規化檢查。 */
class MacroCodecTest {

    private val sample = Macro(
        steps = listOf(
            FindClick("start"),
            FindClick("reward", timeoutMs = 3_000, onMiss = OnMiss.SKIP, fullScreen = true),
            IfSeen("reward", waitMs = 1_500),
            Tap(640, 360),
            Else,
            Wait(2_000),
            End,
            Repeat(Repeat.FOREVER),
            IfSeen("start", fullScreen = true),
            End,
            End,
        ),
        images = listOf(
            MacroImage("start", "開始 按鈕", Region(100, 100, 80, 40)),
            MacroImage("reward", "", Region(600, 300, 60, 60)),
        ),
    )

    @Test
    fun encodesOneLinePerItem() {
        assertEquals(
            """
            img start 100 100 80 40 開始 按鈕
            img reward 600 300 60 60
            find start 10000 stop near
            find reward 3000 skip full
            if reward 1500 near
            tap 640 360
            else
            wait 2000
            end
            repeat 0
            if start 0 full
            end
            end
            """.trimIndent(),
            MacroCodec.encode(sample),
        )
    }

    @Test
    fun roundTripKeepsEverything() {
        val d = MacroCodec.decode(MacroCodec.encode(sample))
        assertEquals(emptyList<String>(), d.errors)
        assertEquals(sample, d.macro)
    }

    @Test
    fun optionalParamsUseDefaultsAndBlankOrCommentLinesAreSkipped() {
        val d = MacroCodec.decode(
            """
            # 我的每日
            img a 1 2 3 4

            find a
            if   a
            end
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), d.errors)
        assertEquals(listOf(FindClick("a"), IfSeen("a"), End), d.macro.steps)
    }

    @Test
    fun badLinesAreReportedAndSkipped() {
        val d = MacroCodec.decode(
            """
            tap 1 2
            tap 1
            find a 10 maybe
            wait soon
            jump 3
            else extra
            tap 3 4
            """.trimIndent(),
        )
        assertEquals(listOf(Tap(1, 2), Tap(3, 4)), d.macro.steps)
        assertEquals(listOf(2, 3, 4, 5, 6), d.errors.map { it.substringAfter("第 ").substringBefore(" 行").toInt() })
        assertEquals("第 5 行看不懂：jump 3", d.errors[3])
    }

    @Test
    fun unsafeOrDuplicateImageIdsAreRejected() {
        val d = MacroCodec.decode(
            """
            img ../x 1 1 2 2
            img ok 1 1 2 2 第一張
            img ok 5 5 2 2 第二張
            find ../x
            """.trimIndent(),
        )
        assertEquals(listOf(MacroImage("ok", "第一張", Region(1, 1, 2, 2))), d.macro.images)
        assertEquals(emptyList<MacroStep>(), d.macro.steps)
        assertEquals(3, d.errors.size)
        assertEquals("第 3 行：圖片 id「ok」重複", d.errors[1])
    }

    @Test
    fun namesAreFlattenedToOneLine() {
        val m = Macro(listOf(Tap(1, 1)), listOf(MacroImage("a", "兩\n行  名稱 ", Region(0, 0, 1, 1))))
        assertEquals("img a 0 0 1 1 兩 行 名稱\ntap 1 1", MacroCodec.encode(m))
    }

    @Test
    fun imageIdValidation() {
        assertTrue(Macro.isValidImageId("img_01-a"))
        assertFalse(Macro.isValidImageId(""))
        assertFalse(Macro.isValidImageId("a/b"))
        assertFalse(Macro.isValidImageId("圖"))
        assertFalse(Macro.isValidImageId("x".repeat(41)))
    }

    @Test
    fun normalizedSizeScalesToHeight720() {
        assertEquals(Size(1280, 720), Macro.normalizedSize(1600, 900))
        assertEquals(Size(1280, 720), Macro.normalizedSize(1920, 1080))
        assertEquals(Size(1280, 720), Macro.normalizedSize(1280, 720))
        assertEquals(Size(1560, 720), Macro.normalizedSize(2340, 1080))   // 19.5:9 手機
    }
}
