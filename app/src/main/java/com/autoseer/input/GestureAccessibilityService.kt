package com.autoseer.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoseer.libautomata.IGestureService
import com.autoseer.libautomata.Location
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Injects taps and swipes via the Accessibility gesture API (no root needed).
 * Exposes itself as a singleton so the running script can reach it. Coordinates
 * received here are in device pixels.
 */
class GestureAccessibilityService : AccessibilityService(), IGestureService {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "無障礙手勢服務已連線")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* unused */ }
    override fun onInterrupt() { /* unused */ }

    override fun click(location: Location, durationMs: Long) {
        val path = Path().apply { moveTo(location.x.toFloat(), location.y.toFloat()) }
        dispatch(path, 0, durationMs.coerceAtLeast(1))
    }

    override fun swipe(from: Location, to: Location, durationMs: Long) {
        val path = Path().apply {
            moveTo(from.x.toFloat(), from.y.toFloat())
            lineTo(to.x.toFloat(), to.y.toFloat())
        }
        dispatch(path, 0, durationMs.coerceAtLeast(1))
    }

    /**
     * Fling-free drag, modelled on FGA's `swipe8`: a finger-down settle, then the
     * move split into many small continued sub-strokes (human-like, no flick), then
     * a finger-up hold so the platform releases with ~zero velocity — no momentum
     * fling, so the list advances by the drag distance (1:1) instead of overshooting.
     *
     * Continued strokes must be dispatched one at a time, each after the previous
     * completes (the finger stays down between them via willContinue=true) — they
     * can NOT be added to a single GestureDescription. So each stroke is dispatched
     * and awaited before the next. Falls back to a plain [swipe] below API 26.
     */
    override fun dragSteady(from: Location, to: Location, durationMs: Long, holdMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            swipe(from, to, durationMs); return
        }
        val fx = from.x.toFloat(); val fy = from.y.toFloat()
        val tx = to.x.toFloat(); val ty = to.y.toFloat()
        val moveMs = (durationMs / SEGMENTS).coerceAtLeast(1)
        val upHold = holdMs.coerceAtLeast(1)

        // 1) finger down + brief settle (no movement)
        var stroke = GestureDescription.StrokeDescription(Path().apply { moveTo(fx, fy) }, 0, DOWN_HOLD_MS, true)
        if (!dispatchAndWait(stroke)) return

        // 2) segmented linear move
        var px = fx; var py = fy
        for (i in 1..SEGMENTS) {
            val t = i.toFloat() / SEGMENTS
            val nx = fx + (tx - fx) * t
            val ny = fy + (ty - fy) * t
            stroke = stroke.continueStroke(Path().apply { moveTo(px, py); lineTo(nx, ny) }, 0, moveMs, true)
            if (!dispatchAndWait(stroke)) return
            px = nx; py = ny
        }

        // 3) finger up hold (stationary → zero release velocity → no fling)
        stroke = stroke.continueStroke(Path().apply { moveTo(px, py); lineTo(px, py + 1f) }, 0, upHold, false)
        dispatchAndWait(stroke)
    }

    /** Dispatch one stroke and block until the gesture completes/cancels (or times out). */
    private fun dispatchAndWait(stroke: GestureDescription.StrokeDescription): Boolean {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var ok = false
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { ok = false; latch.countDown() }
        }
        mainHandler.post {
            if (!dispatchGesture(gesture, callback, mainHandler)) { ok = false; latch.countDown() }
        }
        return try {
            latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && ok
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); false
        }
    }

    /**
     * Overwrite the focused editable field's text via ACTION_SET_TEXT (used to type
     * a 因子 ID into the game's search box). No key events / clipboard — SET_TEXT
     * replaces the whole content, so re-input is just another call.
     */
    override fun setText(text: String): Boolean {
        val node = findEditableNode() ?: run {
            Log.w(TAG, "setText：找不到可編輯輸入框（無障礙節點）"); return false
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun focusedText(): String? = findEditableNode()?.text?.toString()

    /** Focused editable node, else the first editable node across all interactive windows. */
    private fun findEditableNode(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        rootInActiveWindow?.let { editableIn(it)?.let { n -> return n } }
        runCatching {
            for (w in windows) w.root?.let { editableIn(it)?.let { n -> return n } }
        }
        return null
    }

    private fun editableIn(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) editableIn(node.getChild(i))?.let { return it }
        return null
    }

    private fun dispatch(path: Path, startMs: Long, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, startMs, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        mainHandler.post { dispatchGesture(gesture, null, null) }
    }

    companion object {
        private const val TAG = "AutoSeer"
        private const val SEGMENTS = 10          // 把移動切成幾段（模擬真人、避免被判成快速甩動）
        private const val DOWN_HOLD_MS = 150L     // 手指按下後的停頓
        private const val GESTURE_TIMEOUT_MS = 3000L

        @Volatile
        var instance: GestureAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }
}
