package com.autoseer.libautomata

/** Thrown to unwind a running script when the user stops it. */
class ScriptAbortException(message: String = "Script aborted") : Exception(message)

/**
 * Thrown by the screenshot provider when no fresh frame arrives in time (e.g. the
 * emulator's display pipeline stalls). Recoverable: [AutomataApi.refreshScreen]
 * swallows a bounded run of these — reusing the last good frame — so a transient
 * freeze doesn't kill the whole script; the script resumes once frames return.
 */
class ScreenshotTimeoutException(message: String = "Screenshot timed out") : Exception(message)

/**
 * The high-level automation surface scripts use. It ties together the screen
 * source, the image matcher and the gesture injector, and works entirely in the
 * normalized 720p coordinate space. Gesture coordinates are transformed to
 * device pixels here, so scripts never deal with real resolutions.
 */
class AutomataApi(
    private val screenshotProvider: IScreenshotProvider,
    private val matcher: IImageMatcher,
    private val gestures: IGestureService,
    val logger: Logger = Logger.NoOp,
    /** Returns false once the user asks the script to stop. */
    private val isRunning: () -> Boolean = { true },
) {
    companion object {
        const val DEFAULT_THRESHOLD = 0.8

        /**
         * How many screenshots may time out back-to-back before [refreshScreen]
         * gives up and rethrows. Each timeout costs ~2s, so ~150 ≈ 5 min: long
         * enough to ride out the observed emulator freezes (tens of seconds to
         * ~3 min) and resume, but still bounded so a genuine hang eventually stops.
         */
        const val MAX_SCREENSHOT_TIMEOUTS = 150

        /**
         * Scales tried when comparing two on-screen crops with [similarity].
         * The 場上精靈頭像 is larger than the 換精靈卡頭像, so the template is both
         * shrunk and grown a little to find the best fit. Tune against real
         * device captures if matches come back weak.
         */
        val DEFAULT_SCALES: List<Double> = listOf(0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3)
    }

    private var currentScreen: IPattern? = null
    private var consecutiveTimeouts = 0

    val normalizedSize: Size get() = screenshotProvider.normalizedSize()

    /** Full-screen region in normalized coordinates. */
    val fullRegion: Region get() = Region.of(normalizedSize)

    /**
     * Grab a fresh screenshot, replacing the cached one. If the capture times out
     * (a transient emulator display stall), reuse the last good frame instead of
     * crashing the script — but only for a bounded run of consecutive timeouts
     * ([MAX_SCREENSHOT_TIMEOUTS]); past that, rethrow so a real hang stops cleanly.
     * The new frame is fetched BEFORE the old one is closed, so a timeout never
     * leaves us holding a closed pattern.
     */
    fun refreshScreen(): IPattern {
        checkRunning()
        val shot = try {
            screenshotProvider.takeScreenshot()
        } catch (e: ScreenshotTimeoutException) {
            val cached = currentScreen
            if (cached != null && ++consecutiveTimeouts <= MAX_SCREENSHOT_TIMEOUTS) {
                logger.w("擷取螢幕逾時（連續第 $consecutiveTimeouts 次）：暫用上一幀，等模擬器畫面恢復…")
                return cached
            }
            logger.e("擷取螢幕連續逾時超過上限（$MAX_SCREENSHOT_TIMEOUTS 次），停止。")
            throw e
        }
        consecutiveTimeouts = 0
        currentScreen?.close()
        currentScreen = shot
        return shot
    }

    private fun screen(): IPattern = currentScreen ?: refreshScreen()

    /** All places [template] is found within [region] (default: whole screen). */
    fun findAll(
        template: IPattern,
        region: Region? = null,
        threshold: Double = DEFAULT_THRESHOLD,
    ): List<Match> {
        checkRunning()
        val screen = screen()
        val searchRegion = region ?: Region.of(screen.size)
        return if (region == null) {
            matcher.match(screen, template, threshold)
        } else {
            screen.crop(searchRegion).use { cropped ->
                matcher.match(cropped, template, threshold).map { m ->
                    m.copy(region = m.region.copy(x = m.region.x + searchRegion.x, y = m.region.y + searchRegion.y))
                }
            }
        }
    }

    fun find(
        template: IPattern,
        region: Region? = null,
        threshold: Double = DEFAULT_THRESHOLD,
    ): Match? = findAll(template, region, threshold).maxByOrNull { it.score }

    fun exists(
        template: IPattern,
        region: Region? = null,
        threshold: Double = DEFAULT_THRESHOLD,
    ): Boolean = find(template, region, threshold) != null

    /**
     * Crop [region] out of the current screen as a standalone pattern. The
     * caller owns it and must [IPattern.close] it. Use this to remember an
     * on-screen area now (e.g. a 換精靈卡頭像) and compare it against a later
     * screen with [similarity] — no pre-stored template needed.
     */
    fun cropScreen(region: Region): IPattern {
        checkRunning()
        return screen().crop(region)
    }

    /**
     * Mean grayscale brightness (0..255) of [region] on the current screen. Cheap
     * (a single region average, not a template match) — reads colour-coded UI by
     * brightness, e.g. the top HP bar is bright when a pet is alive (橘) and dark
     * when it is 陣亡 (深褐)。
     */
    fun regionMeanGray(region: Region): Double {
        checkRunning()
        return screen().crop(region).use { it.meanGray() }
    }

    /**
     * How well [a] matches somewhere inside [b], as the best normalized
     * template-match score (0..1) over [scales] (a is resized and slid across
     * b). Used to test whether two captured crops show the same art — e.g. a
     * remembered 換精靈卡頭像 vs the current 場上精靈頭像 — tolerating the size and
     * position differences between the two UI spots. Returns -1.0 if no scale
     * of [a] fits within [b].
     */
    fun similarity(a: IPattern, b: IPattern, scales: List<Double> = DEFAULT_SCALES): Double {
        checkRunning()
        // Slide the SMALLER crop (as template) across the larger one; a template
        // bigger than the searched image fits no scale and would always return -1.
        val (tmpl, img) =
            if (a.width.toLong() * a.height <= b.width.toLong() * b.height) a to b else b to a
        var best = -1.0
        for (s in scales) {
            val w = (tmpl.width * s).toInt()
            val h = (tmpl.height * s).toInt()
            if (w < 4 || h < 4 || w > img.width || h > img.height) continue
            tmpl.resize(Size(w, h)).use { scaled ->
                // threshold below any CCOEFF_NORMED value so the top peak is always returned
                matcher.match(img, scaled, -1.0).firstOrNull()?.let { m ->
                    if (m.score > best) best = m.score
                }
            }
        }
        return best
    }

    fun click(location: Location, durationMs: Long = 50) {
        checkRunning()
        val device = location.transform(normalizedSize, screenshotProvider.deviceSize())
        gestures.click(device, durationMs)
    }

    fun click(region: Region) = click(region.center)
    fun click(match: Match) = click(match.region.center)

    fun swipe(from: Location, to: Location, durationMs: Long = 300) {
        checkRunning()
        val device = screenshotProvider.deviceSize()
        gestures.swipe(from.transform(normalizedSize, device), to.transform(normalizedSize, device), durationMs)
    }

    /**
     * Fling-free drag (holds briefly at the end) for precise list scrolling —
     * advances by the drag distance instead of flinging past it. Coordinates are
     * normalized like [swipe].
     */
    fun dragSteady(from: Location, to: Location, durationMs: Long = 700, holdMs: Long = 150) {
        checkRunning()
        val device = screenshotProvider.deviceSize()
        gestures.dragSteady(
            from.transform(normalizedSize, device), to.transform(normalizedSize, device), durationMs, holdMs,
        )
    }

    /**
     * Overwrite the game's focused input field (the 因子 ID 搜尋框) with [text], via
     * the accessibility SET_TEXT action — replaces the whole content, no keystrokes.
     * Returns false if no editable field is focused / unsupported.
     */
    fun setText(text: String): Boolean {
        checkRunning()
        return gestures.setText(text)
    }

    /** Read back the focused input field's text (to verify [setText]), or null. */
    fun focusedText(): String? {
        checkRunning()
        return gestures.focusedText()
    }

    /** Sleep [ms], remaining responsive to stop requests. */
    fun sleep(ms: Long) {
        val step = 50L
        var remaining = ms
        while (remaining > 0) {
            checkRunning()
            val slice = minOf(step, remaining)
            Thread.sleep(slice)
            remaining -= slice
        }
    }

    /**
     * Poll [condition] every [pollMs] until it is true or [timeoutMs] elapses.
     * Refreshes the screen before each poll. Returns true if it became true.
     */
    fun waitUntil(timeoutMs: Long, pollMs: Long = 500, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            refreshScreen()
            if (condition()) return true
            sleep(pollMs)
        }
        return false
    }

    private fun checkRunning() {
        if (!isRunning()) throw ScriptAbortException()
    }
}
