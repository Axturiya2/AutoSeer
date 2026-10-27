package com.autoseer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.autoseer.libautomata.Location
import com.autoseer.libautomata.Region
import com.autoseer.scripts.SeerLayout

/**
 * Shows a mock battle / switch-pet screen and overlays tappable regions.
 *  - SKILL mode: skills 1..5 (green) + 戰鬥/道具/精靈/撤退 (codes 6..9, blue)
 *  - PET mode: 精靈1..6 (codes 11..16, orange)
 * Tapping one invokes [onTap] with its plan code.
 */
class BattleEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    enum class Mode { SKILL, PET }

    var onTap: ((code: Int) -> Unit)? = null

    var mode: Mode = Mode.SKILL
        set(value) { field = value; invalidate() }

    /** Optional display names for skill slots 1..5; shown under the badge when set. */
    var skillNames: List<String> = emptyList()
        set(value) { field = value; invalidate() }

    private fun load(name: String): Bitmap? = runCatching {
        context.assets.open(name).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private val skillBmp = load("editor/battle_canvas.png")
    private val petBmp = load("editor/switch_canvas.png")

    private val dstRect = RectF()
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    private data class Hot(val code: Int, val region: Region, val badge: String, val skillStyle: Boolean, val pet: Boolean)

    private val skillHots: List<Hot> =
        SeerLayout.SKILL_REGIONS.mapIndexed { i, r -> Hot(i + 1, r, (i + 1).toString(), true, false) } +
            SeerLayout.ACTION_REGIONS.mapIndexed { i, r ->
                Hot(SeerLayout.SKILL_COUNT + 1 + i, r, (SeerLayout.SKILL_COUNT + 1 + i).toString(), false, false)
            }

    private val petHots: List<Hot> =
        SeerLayout.PET_REGIONS.mapIndexed { i, r -> Hot(SeerLayout.PET_CODE_BASE + 1 + i, r, (i + 1).toString(), false, true) }

    private fun hots(): List<Hot> = if (mode == Mode.SKILL) skillHots else petHots

    private val greenFill = paint(0x3300C853); private val greenStroke = stroke("#00C853")
    private val blueFill = paint(0x331E88E5); private val blueStroke = stroke("#1E88E5")
    private val orangeFill = paint(0x33FF9800); private val orangeStroke = stroke("#FF9800")
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 30f; isFakeBoldText = true
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 26f; isFakeBoldText = true
    }
    private val nameBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }

    private fun paint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
    private fun stroke(c: String) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(c); style = Paint.Style.STROKE; strokeWidth = 3f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        computeFit()
        val bmp = if (mode == Mode.SKILL) skillBmp else petBmp
        bmp?.let { canvas.drawBitmap(it, Rect(0, 0, it.width, it.height), dstRect, null) }

        hots().forEach { h ->
            val rf = mapRegion(h.region)
            val fill = if (h.pet) orangeFill else if (h.skillStyle) greenFill else blueFill
            val strokeP = if (h.pet) orangeStroke else if (h.skillStyle) greenStroke else blueStroke
            canvas.drawRoundRect(rf, 10f, 10f, fill)
            canvas.drawRoundRect(rf, 10f, 10f, strokeP)
            drawBadge(canvas, rf.centerX(), rf.top + 4f, h.badge)
            nameFor(h)?.let { drawName(canvas, rf.centerX(), rf.centerY(), it) }
        }
    }

    /** Custom display name for a skill slot (1..5), or null to show just the badge. */
    private fun nameFor(h: Hot): String? {
        if (mode != Mode.SKILL || !h.skillStyle || h.code !in 1..5) return null
        return skillNames.getOrNull(h.code - 1)?.takeIf { it.isNotBlank() }
    }

    private fun drawName(canvas: Canvas, cx: Float, cy: Float, text: String) {
        val fm = namePaint.fontMetrics
        val tw = namePaint.measureText(text)
        val padX = 8f; val padY = 4f
        val boxW = tw + padX * 2; val boxH = (fm.descent - fm.ascent) + padY * 2
        canvas.drawRoundRect(
            cx - boxW / 2, cy - boxH / 2, cx + boxW / 2, cy + boxH / 2, 6f, 6f, nameBg,
        )
        canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2, namePaint)
    }

    private fun drawBadge(canvas: Canvas, cx: Float, top: Float, text: String) {
        val r = 18f
        canvas.drawCircle(cx, top + r, r, labelBg)
        val fm = labelPaint.fontMetrics
        canvas.drawText(text, cx, top + r - (fm.ascent + fm.descent) / 2, labelPaint)
    }

    private fun computeFit() {
        val vw = width.toFloat(); val vh = height.toFloat()
        scale = minOf(vw / NORM_W, vh / NORM_H)
        val dw = NORM_W * scale; val dh = NORM_H * scale
        offX = (vw - dw) / 2f; offY = (vh - dh) / 2f
        dstRect.set(offX, offY, offX + dw, offY + dh)
    }

    private fun mapRegion(r: Region): RectF = RectF(
        offX + r.x * scale, offY + r.y * scale,
        offX + (r.x + r.width) * scale, offY + (r.y + r.height) * scale,
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)
        val p = Location(((event.x - offX) / scale).toInt(), ((event.y - offY) / scale).toInt())
        hots().forEach { h ->
            if (h.region.contains(p)) { onTap?.invoke(h.code); performClick(); return true }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        private const val NORM_W = 1280
        private const val NORM_H = 720
    }
}
