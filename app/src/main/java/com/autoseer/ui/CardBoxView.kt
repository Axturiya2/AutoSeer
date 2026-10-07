package com.autoseer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/** One editable card box in bitmap-pixel coordinates. */
class CardBox(var rect: RectF, var selected: Boolean = true, var name: String = "", var petId: String = "")

/**
 * Shows the captured screen and lets the user correct the auto-detected card
 * boxes: tap a box to select/deselect, drag its body to move, drag the
 * bottom-right handle to resize. [onChange] fires whenever boxes or selection
 * change so the host can refresh the naming list.
 */
class CardBoxView(context: Context) : View(context) {

    private var bmp: Bitmap? = null
    private val items = ArrayList<CardBox>()
    var onChange: (() -> Unit)? = null

    // fit transform (bitmap -> view)
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val handleTouch = dp(22f)

    private val cAccent = Color.parseColor("#3F7BFF")
    private val cAccentFill = Color.parseColor("#333F7BFF")
    private val cSub = Color.parseColor("#C8AAB1BD")

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = cAccentFill }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = cAccent }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = cAccent }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(12f); textAlign = Paint.Align.CENTER }
    private val dash = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)

    fun setBitmap(b: Bitmap) { bmp = b; requestLayout(); invalidate() }

    fun setBoxes(list: List<RectF>) {
        items.clear()
        list.forEach { items += CardBox(RectF(it)) }
        onChange?.invoke(); invalidate()
    }

    fun boxes(): List<CardBox> = items
    fun selectedBoxes(): List<CardBox> = items.filter { it.selected }

    fun addBox() {
        val b = bmp ?: return
        val w = b.width * 0.14f
        val h = w / 0.59f   // full-card aspect from samples
        val cx = b.width / 2f
        val cy = b.height / 2f
        items += CardBox(RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), selected = true)
        onChange?.invoke(); invalidate()
    }

    fun deleteSelected() {
        val before = items.size
        items.removeAll { it.selected }
        if (items.size != before) { onChange?.invoke(); invalidate() }
    }

    private fun computeFit() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
        offX = (width - b.width * scale) / 2f
        offY = (height - b.height * scale) / 2f
    }

    private fun bx(x: Float) = x * scale + offX
    private fun by(y: Float) = y * scale + offY
    private fun ivx(x: Float) = (x - offX) / scale
    private fun ivy(y: Float) = (y - offY) / scale

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        computeFit()
        val dst = RectF(offX, offY, offX + b.width * scale, offY + b.height * scale)
        canvas.drawBitmap(b, null, dst, null)

        items.forEachIndexed { i, box ->
            val r = RectF(bx(box.rect.left), by(box.rect.top), bx(box.rect.right), by(box.rect.bottom))
            if (box.selected) {
                canvas.drawRect(r, fillPaint)
                framePaint.color = cAccent; framePaint.pathEffect = null
            } else {
                framePaint.color = cSub; framePaint.pathEffect = dash
            }
            canvas.drawRect(r, framePaint)
            // index label
            canvas.drawText("${i + 1}", r.left + dp(11f), r.top + dp(15f), labelPaint)
            if (box.selected) {
                // check badge (top-left) + resize handle (bottom-right)
                canvas.drawCircle(r.left, r.top, dp(9f), badgePaint)
                canvas.drawText("✓", r.left, r.top + dp(4f), labelPaint)
                canvas.drawRect(r.right - dp(9f), r.bottom - dp(9f), r.right + dp(3f), r.bottom + dp(3f), handlePaint)
            }
        }
    }

    private enum class Mode { NONE, MOVE, RESIZE }
    private var mode = Mode.NONE
    private var active: CardBox? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; moved = false; mode = Mode.NONE; active = null
                // resize handle of a selected box first
                for (box in items.asReversed()) {
                    if (!box.selected) continue
                    val hx = bx(box.rect.right); val hy = by(box.rect.bottom)
                    if (abs(event.x - hx) <= handleTouch && abs(event.y - hy) <= handleTouch) {
                        active = box; mode = Mode.RESIZE; return true
                    }
                }
                // otherwise body hit → move (and candidate for tap-select)
                for (box in items.asReversed()) {
                    val r = RectF(bx(box.rect.left), by(box.rect.top), bx(box.rect.right), by(box.rect.bottom))
                    if (r.contains(event.x, event.y)) { active = box; mode = Mode.MOVE; return true }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) + abs(event.y - downY) > dp(6f)) moved = true
                val box = active ?: return true
                val dxb = (event.x - downX) / scale
                val dyb = (event.y - downY) / scale
                when (mode) {
                    Mode.MOVE -> { box.rect.offset(dxb, dyb); clampBox(box) }
                    Mode.RESIZE -> {
                        box.rect.right = (box.rect.right + dxb).coerceAtLeast(box.rect.left + 20f)
                        box.rect.bottom = (box.rect.bottom + dyb).coerceAtLeast(box.rect.top + 20f)
                        clampBox(box)
                    }
                    else -> {}
                }
                downX = event.x; downY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    // tap = toggle selection of the box under the finger
                    val box = active
                    if (box != null) { box.selected = !box.selected; onChange?.invoke() }
                } else {
                    onChange?.invoke()
                }
                mode = Mode.NONE; active = null; invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun clampBox(box: CardBox) {
        val b = bmp ?: return
        val r = box.rect
        val w = r.width(); val h = r.height()
        if (r.left < 0) { r.left = 0f; r.right = w }
        if (r.top < 0) { r.top = 0f; r.bottom = h }
        if (r.right > b.width) { r.right = b.width.toFloat(); if (mode == Mode.MOVE) r.left = r.right - w }
        if (r.bottom > b.height) { r.bottom = b.height.toFloat(); if (mode == Mode.MOVE) r.top = r.bottom - h }
    }

    /** Crop this box out of the source bitmap (clamped). */
    fun crop(bmpSrc: Bitmap, box: CardBox): Bitmap {
        val r = box.rect
        val x = r.left.toInt().coerceIn(0, bmpSrc.width - 1)
        val y = r.top.toInt().coerceIn(0, bmpSrc.height - 1)
        val w = r.width().toInt().coerceIn(1, bmpSrc.width - x)
        val h = r.height().toInt().coerceIn(1, bmpSrc.height - y)
        return Bitmap.createBitmap(bmpSrc, x, y, w, h)
    }
}
