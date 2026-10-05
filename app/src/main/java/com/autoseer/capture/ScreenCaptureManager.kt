package com.autoseer.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.autoseer.core.OpenCvPattern
import com.autoseer.libautomata.IPattern
import com.autoseer.libautomata.IScreenshotProvider
import com.autoseer.libautomata.ScreenshotTimeoutException
import com.autoseer.libautomata.Size
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Captures the screen via MediaProjection and hands back normalized grayscale
 * screenshots (scaled so height == [NORMALIZED_HEIGHT]) as [OpenCvPattern]s.
 *
 * OBS-1 fix: the script polls only a few times/sec while the VirtualDisplay
 * produces frames far faster, so the ImageReader's buffers fill and the producer
 * stalls — after which `acquireLatestImage()` keeps returning a STALE frame
 * (你的回合 already on screen but never in the analyzed image). Manually pressing
 * 存畫面 "unstuck" it by doing one extra acquire that freed a buffer. So every
 * grab now does that itself: **discard one (stale) frame to relieve back-pressure,
 * then wait for a genuinely fresh frame.**
 */
class ScreenCaptureManager(
    private val projection: MediaProjection,
    private val deviceWidth: Int,
    private val deviceHeight: Int,
    private val densityDpi: Int,
) : IScreenshotProvider {

    private val normalizedHeight = NORMALIZED_HEIGHT
    private val normalizedWidth =
        (deviceWidth.toDouble() * normalizedHeight / deviceHeight).toInt()

    // Capture at the normalized size (~1280x720) instead of full device resolution:
    // the MediaProjection VirtualDisplay then mirrors and reads back a ~64%-smaller
    // surface, cutting the per-frame compositor + GPU-readback load that (together
    // with the game's 換精靈 animation spike) can stall the emulator's display on
    // lower-throughput GPUs. Gestures still use the real device size (deviceSize()),
    // so tap coordinates are unaffected; the captured frame is already normalized.
    private val captureWidth = normalizedWidth
    private val captureHeight = normalizedHeight

    private val handlerThread = HandlerThread("AutoSeerCapture").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val imageReader: ImageReader =
        ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, MAX_IMAGES)

    private var virtualDisplay: VirtualDisplay? = null

    fun start() {
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Log.i(TAG, "MediaProjection 已停止") }
        }, handler)
        virtualDisplay = projection.createVirtualDisplay(
            "AutoSeerCapture",
            captureWidth,
            captureHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler,
        )
    }

    override fun normalizedSize(): Size = Size(normalizedWidth, normalizedHeight)
    override fun deviceSize(): Size = Size(deviceWidth, deviceHeight)

    /**
     * Grab a fresh device-size frame. Discards one possibly-stale buffered frame
     * first (relieving producer back-pressure — the "存畫面" effect), then waits
     * for the next frame the producer pushes.
     */
    private fun grabDeviceBitmap(): Bitmap {
        imageReader.acquireLatestImage()?.close()   // drop stale, free a buffer
        var attempts = 0
        while (true) {
            Thread.sleep(FRAME_WAIT_MS)
            val image = imageReader.acquireLatestImage()
            if (image != null) {
                try {
                    return imageToBitmap(image)
                } finally {
                    image.close()
                }
            }
            if (++attempts > MAX_WAIT_TICKS) {
                // Recoverable: AutomataApi.refreshScreen reuses the last frame for a
                // bounded run of these so a transient display stall doesn't kill the run.
                throw ScreenshotTimeoutException("擷取螢幕逾時：沒有可用的畫面影格")
            }
        }
    }

    /** Copy an ImageReader frame into a device-size ARGB bitmap (cropping row padding). */
    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * captureWidth
        val bufferedWidth = captureWidth + rowPadding / pixelStride
        val buf = Bitmap.createBitmap(bufferedWidth, captureHeight, Bitmap.Config.ARGB_8888)
        buf.copyPixelsFromBuffer(plane.buffer)
        return if (bufferedWidth != captureWidth) {
            val cropped = Bitmap.createBitmap(buf, 0, 0, captureWidth, captureHeight)
            buf.recycle()
            cropped
        } else {
            buf
        }
    }

    override fun takeScreenshot(): IPattern {
        val bitmap = grabDeviceBitmap()
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        bitmap.recycle()
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val resized = Mat()
        Imgproc.resize(
            gray,
            resized,
            org.opencv.core.Size(normalizedWidth.toDouble(), normalizedHeight.toDouble()),
        )
        gray.release()
        return OpenCvPattern(resized)
    }

    /**
     * A viewable color snapshot at the normalized size (same scaling the matcher
     * sees), for template capture / debugging. Caller owns the returned bitmap.
     */
    fun captureColorBitmap(): Bitmap {
        val src = grabDeviceBitmap()
        val scaled = Bitmap.createScaledBitmap(src, normalizedWidth, normalizedHeight, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader.close()
        runCatching { projection.stop() }
        handlerThread.quitSafely()
    }

    companion object {
        private const val TAG = "AutoSeer"
        const val NORMALIZED_HEIGHT = 720
        private const val MAX_IMAGES = 3
        // Wait ~1+ frame between the discard and the fresh acquire.
        private const val FRAME_WAIT_MS = 45L
        private const val MAX_WAIT_TICKS = 45   // ~2s timeout
    }
}
