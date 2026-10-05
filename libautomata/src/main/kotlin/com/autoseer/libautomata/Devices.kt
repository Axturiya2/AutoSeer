package com.autoseer.libautomata

/** Supplies screenshots already normalized into the 720p working space. */
interface IScreenshotProvider {
    /** Grab the current screen, normalized (grayscale, scaled to [normalizedSize]). */
    fun takeScreenshot(): IPattern

    /** Size of the normalized working space (height == 720 by convention). */
    fun normalizedSize(): Size

    /** Actual device/emulator screen size in pixels. */
    fun deviceSize(): Size
}

/** Injects touch input. Coordinates passed here are in *device* pixels. */
interface IGestureService {
    fun click(location: Location, durationMs: Long = 50)
    fun swipe(from: Location, to: Location, durationMs: Long = 300)

    /**
     * Like [swipe] but ends with the pointer held stationary for [holdMs] so the
     * platform sees ~zero release velocity — a fling-free, 1:1 drag. Used for
     * precise list scrolling (a normal swipe flings ~2× the drag distance). Default
     * falls back to [swipe] for impls/tests that don't need it.
     */
    fun dragSteady(from: Location, to: Location, durationMs: Long = 700, holdMs: Long = 150) =
        swipe(from, to, durationMs)

    /**
     * Set the text of the currently focused editable field (the game's 因子 ID 搜尋框),
     * via the accessibility SET_TEXT action — overwrites whatever is there (no key
     * simulation, no clipboard). Returns false if no editable field is focused or the
     * platform layer doesn't support it. Default no-op for impls/tests that don't.
     */
    fun setText(text: String): Boolean = false

    /** Read back the focused editable field's text (to verify [setText]), or null. */
    fun focusedText(): String? = null
}

/** Matches a [template] within an [image]; both share a coordinate space. */
interface IImageMatcher {
    /**
     * @return every location where [template] appears in [image] with a score
     *   >= [threshold] (0..1), best first.
     */
    fun match(image: IPattern, template: IPattern, threshold: Double): List<Match>
}
