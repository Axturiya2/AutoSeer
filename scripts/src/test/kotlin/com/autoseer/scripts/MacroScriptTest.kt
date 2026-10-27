package com.autoseer.scripts

import com.autoseer.libautomata.AutomataApi
import com.autoseer.libautomata.IGestureService
import com.autoseer.libautomata.IImageMatcher
import com.autoseer.libautomata.IPattern
import com.autoseer.libautomata.IScreenshotProvider
import com.autoseer.libautomata.Location
import com.autoseer.libautomata.Match
import com.autoseer.libautomata.Region
import com.autoseer.libautomata.ScriptAbortException
import com.autoseer.libautomata.Size
import com.autoseer.libautomata.Templates
import com.autoseer.scripts.MacroStep.Else
import com.autoseer.scripts.MacroStep.End
import com.autoseer.scripts.MacroStep.FindClick
import com.autoseer.scripts.MacroStep.IfSeen
import com.autoseer.scripts.MacroStep.OnMiss
import com.autoseer.scripts.MacroStep.Repeat
import com.autoseer.scripts.MacroStep.Tap
import com.autoseer.scripts.MacroStep.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MacroScript]（FR-12 自製腳本執行引擎）。用假畫面跑真的 [AutomataApi]：
 * [World.visible] 記畫面上有哪些圖、在哪裡，點擊會記下來並可觸發畫面變化。
 */
class MacroScriptTest {

    private val start = MacroImage("start", "開始", Region(100, 100, 80, 40))
    private val reward = MacroImage("reward", "獎勵", Region(600, 300, 60, 60))
    private val fast = MacroScript.Timing(pollMs = 5, afterTapMs = 0)

    private fun run(world: World, vararg steps: MacroStep, onStep: (Int, String) -> Unit = { _, _ -> }): MacroScript {
        val script = MacroScript(world.api(), world.templates, Macro(steps.toList(), listOf(start, reward)), fast, onStep)
        script.run()
        return script
    }

    @Test
    fun runsActionsInOrder() {
        val w = World().show(start)
        val s = run(w, FindClick("start"), Wait(10), Tap(640, 360))
        assertEquals(MacroOutcome.Finished, s.outcome)
        assertEquals(listOf(start.region.center, Location(640, 360)), w.clicks)
    }

    @Test
    fun findClickWaitsUntilImageAppears() {
        val w = World()
        w.onShot = { n -> if (n == 3) w.show(start) }
        val s = run(w, FindClick("start", timeoutMs = 2_000))
        assertEquals(MacroOutcome.Finished, s.outcome)
        assertEquals(listOf(start.region.center), w.clicks)
    }

    @Test
    fun missWithStopEndsScriptAtThatStep() {
        val w = World()
        val s = run(w, Tap(1, 1), FindClick("reward", timeoutMs = 30), Tap(2, 2))
        assertEquals(MacroOutcome.Missed(2, "獎勵"), s.outcome)
        assertEquals(listOf(Location(1, 1)), w.clicks)
    }

    @Test
    fun missWithSkipContinues() {
        val w = World()
        val s = run(w, FindClick("reward", timeoutMs = 30, onMiss = OnMiss.SKIP), Tap(2, 2))
        assertEquals(MacroOutcome.Finished, s.outcome)
        assertEquals(listOf(Location(2, 2)), w.clicks)
    }

    @Test
    fun nearSearchIgnoresImageMovedFarAwayButFullScreenFindsIt() {
        val moved = Region(1000, 600, 80, 40)    // 「開始」跑到右下角，離原框超過擴張範圍
        val w = World().show(start, at = moved)
        val near = run(w, FindClick("start", timeoutMs = 30))
        assertEquals(MacroOutcome.Missed(1, "開始"), near.outcome)

        val full = run(w, FindClick("start", timeoutMs = 30, fullScreen = true))
        assertEquals(MacroOutcome.Finished, full.outcome)
        assertEquals(listOf(moved.center), w.clicks)
    }

    @Test
    fun nearSearchFindsImageShiftedSlightly() {
        val shifted = Region(150, 130, 80, 40)
        val w = World().show(start, at = shifted)
        run(w, FindClick("start", timeoutMs = 30))
        assertEquals(listOf(shifted.center), w.clicks)
    }

    @Test
    fun ifSeenPicksThenOrElseBranch() {
        val seen = World().show(reward)
        run(seen, IfSeen("reward"), Tap(1, 1), Else, Tap(2, 2), End, Tap(3, 3))
        assertEquals(listOf(Location(1, 1), Location(3, 3)), seen.clicks)

        val notSeen = World()
        run(notSeen, IfSeen("reward"), Tap(1, 1), Else, Tap(2, 2), End, Tap(3, 3))
        assertEquals(listOf(Location(2, 2), Location(3, 3)), notSeen.clicks)
    }

    @Test
    fun ifSeenWithWaitCatchesImageThatAppearsLater() {
        val w = World()
        w.onShot = { n -> if (n == 3) w.show(reward) }
        run(w, IfSeen("reward", waitMs = 2_000), Tap(1, 1), Else, Tap(2, 2), End)
        assertEquals(listOf(Location(1, 1)), w.clicks)
    }

    @Test
    fun repeatRunsBodyNTimes() {
        val w = World()
        run(w, Repeat(3), Tap(5, 5), End)
        assertEquals(List(3) { Location(5, 5) }, w.clicks)
    }

    @Test
    fun foreverRepeatRunsUntilStopped() {
        val w = World()
        w.stopAfterClicks = 5
        assertThrows(ScriptAbortException::class.java) { run(w, Repeat(Repeat.FOREVER), Tap(5, 5), End) }
        assertEquals(5, w.clicks.size)
    }

    @Test
    fun blocksNestAndReactToScreenChanges() {
        // 第 1 輪看到「開始」→ 點它（點了就消失）；第 2 輪沒看到 → 走否則
        val w = World().show(start)
        w.onClick = { at -> if (start.region.contains(at)) w.visible.remove("start") }
        run(w, Repeat(2), IfSeen("start"), FindClick("start"), Else, Tap(9, 9), End, End)
        assertEquals(listOf(start.region.center, Location(9, 9)), w.clicks)
    }

    @Test
    fun invalidMacroNeverClicks() {
        val w = World().show(start)
        val s = run(w, FindClick("start"), Repeat(2), Tap(1, 1))
        val outcome = s.outcome as MacroOutcome.Invalid
        assertEquals(listOf(2), outcome.errors.map { it.stepNo })
        assertTrue(w.clicks.isEmpty())
    }

    @Test
    fun missingImageFileIsInvalid() {
        val w = World()
        w.templates.known.remove("reward")
        val s = run(w, Tap(1, 1))
        assertEquals(
            MacroOutcome.Invalid(listOf(MacroError(null, "圖片「獎勵」的檔案不見了"))),
            s.outcome,
        )
        assertTrue(w.clicks.isEmpty())
    }

    @Test
    fun onStepReportsStepNumbersAndRounds() {
        val w = World()
        val seen = ArrayList<String>()
        run(w, Tap(1, 1), Repeat(2), Wait(1), End, onStep = { no, text -> seen += "$no $text" })
        assertEquals(
            listOf("1 點擊 (1, 1)", "2 重複 2 次（第 1/2 輪）", "3 等待 0.001 秒", "2 重複 2 次（第 2/2 輪）", "3 等待 0.001 秒"),
            seen,
        )
    }

    // ---- 假畫面 ----

    private class World {
        /** 畫面上看得到的圖：圖片 id → 目前位置。 */
        val visible = LinkedHashMap<String, Region>()
        val clicks = ArrayList<Location>()
        var onClick: (Location) -> Unit = {}
        /** 第 n 張截圖前呼叫（1 起算），用來讓圖晚點才出現。 */
        var onShot: (Int) -> Unit = {}
        /** 點了這麼多下之後當作玩家按了停止。 */
        var stopAfterClicks = Int.MAX_VALUE
        val templates = FakeTemplates()
        private var shots = 0

        fun show(img: MacroImage, at: Region = img.region): World {
            visible[img.id] = at
            templates.known[img.id] = img.region.size
            return this
        }

        fun api() = AutomataApi(
            screenshotProvider = object : IScreenshotProvider {
                override fun takeScreenshot(): IPattern {
                    onShot(++shots)
                    return Screen(visible.toMap(), Macro.SCREEN)
                }
                override fun normalizedSize() = Macro.SCREEN
                override fun deviceSize() = Macro.SCREEN
            },
            matcher = object : IImageMatcher {
                override fun match(image: IPattern, template: IPattern, threshold: Double): List<Match> {
                    val at = (image as Screen).items[(template as Tpl).id] ?: return emptyList()
                    return listOf(Match(at, 1.0))
                }
            },
            gestures = object : IGestureService {
                override fun click(location: Location, durationMs: Long) {
                    clicks += location
                    onClick(location)
                }
                override fun swipe(from: Location, to: Location, durationMs: Long) {}
            },
            isRunning = { clicks.size < stopAfterClicks },
        )
    }

    /** 預設兩張圖都有檔案；[known] 移掉就模擬檔案不見。 */
    private class FakeTemplates : Templates {
        val known = linkedMapOf("start" to Size(80, 40), "reward" to Size(60, 60))
        override fun has(id: String) = id in known
        override fun get(id: String): IPattern = Tpl(id, known.getValue(id))
    }

    /** 假截圖：裁切時只留完整落在範圍內的圖，座標改成相對裁切區。 */
    private class Screen(val items: Map<String, Region>, val sz: Size) : IPattern {
        override val width get() = sz.width
        override val height get() = sz.height
        override fun crop(region: Region): IPattern = Screen(
            items.filterValues { it.x >= region.x && it.y >= region.y && it.right <= region.right && it.bottom <= region.bottom }
                .mapValues { (_, r) -> r.copy(x = r.x - region.x, y = r.y - region.y) },
            region.size,
        )
        override fun resize(size: Size): IPattern = throw UnsupportedOperationException()
        override fun meanGray() = 0.0
        override fun save(path: String) {}
    }

    private class Tpl(val id: String, val sz: Size) : IPattern {
        override val width get() = sz.width
        override val height get() = sz.height
        override fun crop(region: Region): IPattern = throw UnsupportedOperationException()
        override fun resize(size: Size): IPattern = throw UnsupportedOperationException()
        override fun meanGray() = 0.0
        override fun save(path: String) {}
    }
}
