package com.autoseer.libautomata

/**
 * An in-memory image (a screenshot or a template to search for). The concrete
 * implementation lives in the platform layer (e.g. an OpenCV `Mat` wrapper in
 * the `app` module); `libautomata` only depends on this abstraction.
 */
interface IPattern : AutoCloseable {
    val width: Int
    val height: Int
    val size: Size get() = Size(width, height)

    /** Return a new pattern containing only [region] of this image. */
    fun crop(region: Region): IPattern

    /** Return a new pattern resized to [size]. */
    fun resize(size: Size): IPattern

    /**
     * Mean pixel intensity (0..255) over the whole pattern, on the grayscale image.
     * Cheap brightness read for colour-coded UI: e.g. the 血條 reads bright when a
     * pet is alive (橘) and dark when it is 陣亡 (深褐)。
     */
    fun meanGray(): Double

    /** Persist to [path] as PNG (debugging / capturing template assets). */
    fun save(path: String)

    override fun close() {}
}
