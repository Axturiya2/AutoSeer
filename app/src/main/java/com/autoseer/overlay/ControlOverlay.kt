package com.autoseer.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoseer.core.RunPrefs
import com.autoseer.core.ScriptStore
import com.autoseer.core.SeerScript
import com.autoseer.core.DelayPrefs
import com.autoseer.scripts.RunMode

/**
 * FGA-style floating control. Collapsed it is a small draggable dot ("AS") with a
 * one-line live status above it (what the sweep is doing right now) — small enough
 * to drag clear of any region the vision needs. Tapping the dot expands a panel:
 * live status, current progress (第 X 關｜已清 N 關), quick script switch, 起始關卡
 * stepper, a link to the global delay settings, Start/Stop, and the debug tools.
 */
class ControlOverlay(
    private val context: Context,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onCapture: () -> Unit = {},
    private val onProbe: () -> Unit = {},
    private val onCaptureCards: () -> Unit = {},
    private val onNavTest: () -> Unit = {},
    /** 自製腳本（FR-12）：截圖存進截圖庫並開啟。 */
    private val onMacroShot: () -> Unit = {},
) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: FrameLayout? = null
    private lateinit var collapsed: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var capText: TextView      // status above the dot (collapsed)
    private lateinit var dotView: TextView      // the "AS" circle
    private lateinit var statusText: TextView   // status line (panel)
    private lateinit var progressText: TextView // 目前進度 (panel)
    private lateinit var scriptButton: Button   // opens the script popup
    private lateinit var scriptList: LinearLayout   // script rows (content of scriptPopup)
    private lateinit var scriptPopup: LinearLayout  // floating card over the panel
    private lateinit var delayPanel: LinearLayout   // delay rows (content of delayPopup)
    private lateinit var delayPopup: LinearLayout    // floating card over the panel
    private lateinit var modeButton: Button     // 功能選單（精靈因子／每日／一般戰鬥）
    private lateinit var modeList: LinearLayout
    private lateinit var modePopup: LinearLayout
    private lateinit var navTestBtn: Button     // 只在精靈因子模式顯示
    private lateinit var stageValue: TextView   // 起始關卡 value
    private lateinit var runButton: Button

    private var running = false
    private lateinit var params: WindowManager.LayoutParams

    // ---- colors ----
    private val cPanel = Color.parseColor("#1F2226")
    private val cLine = Color.parseColor("#33383F")
    private val cInk = Color.parseColor("#F2F4F8")
    private val cSub = Color.parseColor("#AAB1BD")
    private val cField = Color.parseColor("#2A2F36")
    private val cAccent = Color.parseColor("#3F7BFF")
    private val cGood = Color.parseColor("#2EA36B")
    private val cStop = Color.parseColor("#E05555")
    private val cProgBg = Color.parseColor("#131C16")
    private val cProgInk = Color.parseColor("#BFE6CF")

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return
        val container = FrameLayout(context)
        collapsed = buildCollapsed()
        panel = buildPanel()
        panel.visibility = View.GONE
        container.addView(collapsed)
        container.addView(panel)

        // 獨立浮動子頁：疊在主面板上、可上下滑；顯示時主面板尺寸不變。
        scriptPopup = makePopupCard("選擇腳本", scriptList)
        delayPopup = makePopupCard("延遲設定（全域）", delayPanel)
        modePopup = makePopupCard("選擇功能", modeList)
        val popupLp = {
            FrameLayout.LayoutParams(dp(250), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START; leftMargin = dp(6); topMargin = dp(70)
            }
        }
        container.addView(scriptPopup, popupLp())
        container.addView(delayPopup, popupLp())
        container.addView(modePopup, popupLp())

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(300)   // 左側中段，避開左上快速選單/回大廳判斷區
        }
        enableDrag(collapsed) { setExpanded(true) }   // 拖曳移動；未拖動＝展開
        windowManager.addView(container, params)
        root = container
    }

    // ---- collapsed ----
    private fun buildCollapsed(): LinearLayout {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        capText = TextView(context).apply {
            text = "待命"
            setTextColor(cInk)
            textSize = 11f
            // 固定寬度＋單行：狀態文字每次變動都不改變收合視窗的尺寸，避免視窗 resize →
            // Surface 重建 → 撞上 MuMu 虛擬GPU 的 eglCreateWindowSurface/rcCreateColorBuffer
            // 卡死（整個 app 凍結的根因）。完整狀態仍可在展開面板/Logcat 看。
            width = dp(210)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            background = rounded(Color.parseColor("#EA1F2226"), 8, cLine)
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }
        dotView = TextView(context).apply {
            text = "AS"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cAccent) }
        }
        wrap.addView(capText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        wrap.addView(dotView, LinearLayout.LayoutParams(dp(52), dp(52)))
        return wrap
    }

    // ---- expanded panel ----
    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cPanel, 14, cLine)
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        // header
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(context).apply {
            text = "AutoSeer"; setTextColor(cInk); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
        }
        val collapseBtn = makeBtn("▬", cField).apply { setOnClickListener { setExpanded(false) } }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(collapseBtn, LinearLayout.LayoutParams(dp(40), dp(34)))
        enableDrag(header, null)

        statusText = TextView(context).apply {
            text = "狀態：待命"; setTextColor(cSub); textSize = 12f
            background = rounded(Color.parseColor("#171A1E"), 8); setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        progressText = TextView(context).apply {
            text = "目前進度：第 1 關｜已清 0 關"; setTextColor(cProgInk); textSize = 12f
            background = rounded(cProgBg, 8); setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        scriptButton = makeBtn("腳本：--", cField).apply {
            setOnClickListener { toggleScriptList() }
        }
        scriptList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        modeButton = makeBtn("功能：--", cField).apply { setOnClickListener { toggleModeList() } }
        modeList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // 起始關卡 stepper
        val stageRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val stageLabel = TextView(context).apply { text = "起始關卡"; setTextColor(cSub); textSize = 12f }
        val minus = makeBtn("－", cField).apply { setOnClickListener { bumpStage(-1) } }
        stageValue = TextView(context).apply {
            text = RunPrefs.startStage(context).toString(); setTextColor(cInk); textSize = 14f; gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#171A1E"), 8)
        }
        val plus = makeBtn("＋", cField).apply { setOnClickListener { bumpStage(1) } }
        stageRow.addView(stageLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        stageRow.addView(minus, LinearLayout.LayoutParams(dp(38), dp(34)))
        stageRow.addView(stageValue, LinearLayout.LayoutParams(dp(48), dp(34)).apply { leftMargin = dp(4); rightMargin = dp(4) })
        stageRow.addView(plus, LinearLayout.LayoutParams(dp(38), dp(34)))

        val delayBtn = makeBtn("延遲設定…（全域）", cField).apply {
            textSize = 12f
            setOnClickListener { toggleDelayPanel() }
        }
        delayPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        runButton = makeBtn("開始", cGood).apply {
            textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setOnClickListener {
                if (running) onStop() else { onStart(); setExpanded(false) }  // 開始後自動收合
            }
        }
        // debug tools
        val tools = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val cap = makeBtn("存畫面", cField).apply { textSize = 12f; setOnClickListener { onCapture() } }
        val probe = makeBtn("測試偵測", cField).apply { textSize = 12f; setOnClickListener { onProbe() } }
        val cards = makeBtn("擷取卡", cField).apply { textSize = 12f; setOnClickListener { onCaptureCards() } }
        tools.addView(cap, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(3) })
        tools.addView(probe, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        tools.addView(cards, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(3) })

        val macroShotBtn = makeBtn("截圖製作（自製腳本）", cField).apply {
            textSize = 12f; setOnClickListener { onMacroShot() }
        }

        // 純導航測試（不打戰鬥）：測「進入→退出→下一個因子＋捲動尋找」的銜接流程。
        navTestBtn = makeBtn("測因子導航（不打戰鬥）", cField).apply {
            textSize = 12f; setOnClickListener { onNavTest() }
        }

        val w = dp(250)
        fun add(v: View, topMargin: Int = dp(9)) =
            p.addView(v, LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply { this.topMargin = topMargin })
        p.addView(header, LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT))
        add(statusText); add(progressText)
        add(modeButton); add(scriptButton)
        // 起始關卡 stepper 已休眠（v9+：改以動態辨識「關卡 N」為準，不再由使用者預設）。
        // stageRow 及 bumpStage() 程式碼保留但不加進面板。
        add(delayBtn)
        add(runButton); add(tools); add(macroShotBtn, dp(6)); add(navTestBtn, dp(6))
        return p
    }

    private fun makeBtn(text: String, bg: Int): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        setTextColor(cInk)
        textSize = 13f
        background = rounded(bg, 9, if (bg == cField) cLine else null)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        minWidth = 0; minHeight = 0
    }

    /** A floating card (title + 返回 + scrollable content) that overlays the panel. */
    private fun makePopupCard(title: String, content: View): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cPanel, 12, cAccent)
            setPadding(dp(10), dp(8), dp(10), dp(10))
            visibility = View.GONE
            elevation = dp(10).toFloat()
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val t = TextView(context).apply { text = title; setTextColor(cInk); textSize = 13f; setTypeface(typeface, Typeface.BOLD) }
        val close = makeBtn("返回", cField).apply { textSize = 12f; setOnClickListener { card.visibility = View.GONE } }
        head.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(close, LinearLayout.LayoutParams(dp(56), dp(32)))
        card.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(
            boundedScroll(content, 240),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) },
        )
        return card
    }

    /** ScrollView that wraps content but never grows past [maxDp] tall (then scrolls). */
    private fun boundedScroll(content: View, maxDp: Int): ScrollView {
        val sv = object : ScrollView(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(dp(maxDp), MeasureSpec.AT_MOST))
            }
        }
        sv.isVerticalScrollBarEnabled = true
        sv.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return sv
    }

    // ---- actions ----
    private fun setExpanded(expanded: Boolean) {
        if (root == null) return
        if (expanded) {
            refreshModeButton()   // 也會刷新腳本鈕
            // 起始關卡 stepper 已休眠，不再刷新 stageValue。
        }
        scriptPopup.visibility = View.GONE
        delayPopup.visibility = View.GONE
        modePopup.visibility = View.GONE
        collapsed.visibility = if (expanded) View.GONE else View.VISIBLE
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    // ---- 腳本下拉（自繪，避開懸浮窗 Spinner 焦點問題）----
    private fun toggleScriptList() {
        if (scriptPopup.visibility == View.VISIBLE) { scriptPopup.visibility = View.GONE; return }
        delayPopup.visibility = View.GONE
        modePopup.visibility = View.GONE
        populateScriptList()
        scriptPopup.visibility = View.VISIBLE
    }

    private fun populateScriptList() {
        scriptList.removeAllViews()
        val list = ScriptStore.list(context, SeerScript.CATEGORY_SEER_FACTOR)
        val sel = ScriptStore.selectedId(context)
        if (list.isEmpty()) {
            scriptList.addView(TextView(context).apply {
                text = "（無腳本，請到周回腳本新增）"; setTextColor(cSub); textSize = 12f
                setPadding(dp(10), dp(8), dp(10), dp(8))
            })
            return
        }
        for (s in list) {
            val row = TextView(context).apply {
                text = (if (s.id == sel) "● " else "　") + s.displayName
                setTextColor(cInk); textSize = 13f
                setPadding(dp(10), dp(9), dp(10), dp(9))
                setOnClickListener {
                    ScriptStore.setSelected(context, s.id)
                    refreshScriptButton()
                    scriptPopup.visibility = View.GONE
                }
            }
            scriptList.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    // ---- 延遲懸浮子頁（±100ms，免鍵盤）----
    private val delayNames = listOf("恢復後", "進戰後", "技能後", "換精靈後", "結算後", "撤退各步")

    private fun currentDelays(): IntArray = intArrayOf(
        DelayPrefs.afterHeal(context), DelayPrefs.afterEnter(context), DelayPrefs.afterSkill(context),
        DelayPrefs.afterSwitch(context), DelayPrefs.afterResult(context), DelayPrefs.afterRetreat(context),
    )
    private fun saveDelays(v: IntArray) = DelayPrefs.save(context, v[0], v[1], v[2], v[3], v[4], v[5])

    private fun toggleDelayPanel() {
        if (delayPopup.visibility == View.VISIBLE) { delayPopup.visibility = View.GONE; return }
        scriptPopup.visibility = View.GONE
        modePopup.visibility = View.GONE
        populateDelayPanel()
        delayPopup.visibility = View.VISIBLE
    }

    private fun populateDelayPanel() {
        delayPanel.removeAllViews()
        val values = currentDelays()
        val valueViews = arrayOfNulls<TextView>(6)
        for (i in 0 until 6) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val label = TextView(context).apply { text = delayNames[i]; setTextColor(cSub); textSize = 12f }
            val minus = makeBtn("－", cField)
            val vv = TextView(context).apply {
                text = values[i].toString(); setTextColor(cInk); textSize = 13f; gravity = Gravity.CENTER
                background = rounded(cPanel, 8)
            }
            val plus = makeBtn("＋", cField)
            valueViews[i] = vv
            minus.setOnClickListener { values[i] = (values[i] - 100).coerceAtLeast(0); vv.text = values[i].toString(); saveDelays(values) }
            plus.setOnClickListener { values[i] = values[i] + 100; vv.text = values[i].toString(); saveDelays(values) }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(minus, LinearLayout.LayoutParams(dp(36), dp(32)))
            row.addView(vv, LinearLayout.LayoutParams(dp(54), dp(32)).apply { leftMargin = dp(3); rightMargin = dp(3) })
            row.addView(plus, LinearLayout.LayoutParams(dp(36), dp(32)))
            delayPanel.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = if (i == 0) 0 else dp(6) },
            )
        }
        val bottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val reset = makeBtn("還原 500", cField).apply {
            textSize = 12f
            setOnClickListener {
                for (i in 0 until 6) { values[i] = DelayPrefs.DEFAULT_MS; valueViews[i]?.text = values[i].toString() }
                saveDelays(values)
            }
        }
        bottom.addView(reset, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))
        delayPanel.addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun refreshScriptButton() {
        val list = ScriptStore.list(context, SeerScript.CATEGORY_SEER_FACTOR)
        val sel = ScriptStore.selectedId(context)
        val name = list.firstOrNull { it.id == sel }?.displayName
            ?: list.firstOrNull()?.displayName ?: "（無，請先新增）"
        val hint = if (RunPrefs.mode(context) == RunMode.BATTLE) "用第1關技能" else "點擊切換"
        scriptButton.text = "腳本：$name（$hint）"
    }

    // ---- 功能選單（FR-11）----
    private fun toggleModeList() {
        if (modePopup.visibility == View.VISIBLE) { modePopup.visibility = View.GONE; return }
        if (running) { setStatus("執行中不能切換功能，請先停止"); return }
        scriptPopup.visibility = View.GONE
        delayPopup.visibility = View.GONE
        populateModeList()
        modePopup.visibility = View.VISIBLE
    }

    private fun populateModeList() {
        modeList.removeAllViews()
        val sel = RunPrefs.mode(context)
        for (mode in RunMode.entries) {
            val row = TextView(context).apply {
                text = (if (mode == sel) "● " else "　") + mode.label + (if (mode.ready) "" else "（尚未開發）")
                setTextColor(if (mode.ready) cInk else cSub); textSize = 13f
                setPadding(dp(10), dp(9), dp(10), dp(9))
                setOnClickListener {
                    if (!mode.ready) { setStatus("${mode.label}功能尚未開發"); return@setOnClickListener }
                    RunPrefs.setMode(context, mode)
                    refreshModeButton()
                    modePopup.visibility = View.GONE
                    progressText.text = if (mode == RunMode.BATTLE) "一般戰鬥：已打 0 場" else "目前進度：第 1 關｜已清 0 關"
                    setStatus(if (mode == RunMode.BATTLE) "已切換：一般戰鬥（按開始後待命，進戰鬥自動接手）" else "已切換：${mode.label}")
                }
            }
            modeList.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    /** 依模式刷新功能鈕、腳本鈕文字與導航測試鈕；只在使用者點選（沒在跑）時呼叫，不會在執行中改視窗大小。 */
    private fun refreshModeButton() {
        val mode = RunPrefs.mode(context)
        modeButton.text = "功能：${mode.label} ▾"
        navTestBtn.visibility = if (mode == RunMode.FACTOR) View.VISIBLE else View.GONE
        refreshScriptButton()
    }

    private fun bumpStage(delta: Int) {
        val v = (RunPrefs.startStage(context) + delta).coerceAtLeast(1)
        RunPrefs.save(
            ctx = context,
            startStage = v,
            defaultSlot = RunPrefs.defaultSlot(context),
            healBeforeBattle = RunPrefs.healBeforeBattle(context),
            backToLobbyOnRetryExhausted = RunPrefs.backToLobbyOnRetryExhausted(context),
            maxRetriesPerStage = RunPrefs.maxRetriesPerStage(context),
        )
        stageValue.text = v.toString()
    }

    /** Reflect the runner state. Safe to call from any thread. */
    fun setRunning(isRunning: Boolean) {
        dotView.post {
            running = isRunning
            runButton.text = if (isRunning) "停止" else "開始"
            runButton.background = rounded(if (isRunning) cStop else cGood, 9)
            dotView.text = if (isRunning) "⏸" else "AS"
            (dotView.background as? GradientDrawable)?.setColor(if (isRunning) cGood else cAccent)
            // 停止時「不」強制刷成待命——保留最後一行狀態(常是結束/失敗原因)，方便直接在氣泡上看，
            // 不必再開 adb logcat。真正閒置的初始文字仍是「待命」。
        }
    }

    fun setStatus(text: String) {
        capText.post { capText.text = text }
        statusText.post { statusText.text = text }
    }

    /**
     * Hide/show the whole overlay so it doesn't appear in a screen capture. Uses
     * alpha (fully transparent) rather than View.GONE: GONE relayouts the window to
     * 0 size and releases its Surface, so restoring it recreates the Surface — and
     * that eglCreateWindowSurface → rcCreateColorBuffer path is exactly where MuMu's
     * virtual GPU hangs (the whole-app freeze). alpha=0 keeps the same Surface, is
     * still absent from the captured frame, and never triggers a recreate.
     */
    fun setChromeVisible(visible: Boolean) {
        root?.post { root?.alpha = if (visible) 1f else 0f }
    }

    fun setProgress(stageNo: Int, cleared: Int) = setProgressText("目前進度：第 $stageNo 關｜已清 $cleared 關")

    fun setProgressText(text: String) {
        progressText.post { progressText.text = text }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(handle: View, onTap: (() -> Unit)?) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY; moved = false
                    true   // 消費 DOWN 以確保收到後續 MOVE/UP（拖曳＋點擊展開）
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt(); val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(6)) moved = true
                    params.x = initialX + dx; params.y = initialY + dy
                    root?.let { windowManager.updateViewLayout(it, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && onTap != null) onTap()
                    false
                }
                else -> false
            }
        }
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }
}
