package com.autoseer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.autoseer.core.FactorLibrary
import com.autoseer.core.PetImagePrefs
import com.autoseer.core.SafStore
import com.autoseer.scripts.PetIdTag
import com.autoseer.vision.CardDetector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Review page for 精靈圖像卡擷取 (階段 A). Loads the captured screen (a temp PNG
 * the service wrote), shows auto-detected card boxes over it for the user to
 * correct (add/move/resize/select), lets each selected card be named, then crops
 * and saves the selected cards as PNGs into the user's SAF folder.
 */
class CardCaptureActivity : AppCompatActivity() {

    private lateinit var boxView: CardBoxView
    private lateinit var nameList: LinearLayout
    private lateinit var countText: TextView
    private var source: Bitmap? = null
    private var tempPath: String? = null

    // dark palette matching the overlay
    private val cBg = Color.parseColor("#15181C")
    private val cPanel = Color.parseColor("#1F2226")
    private val cField = Color.parseColor("#2A2F36")
    private val cLine = Color.parseColor("#33383F")
    private val cInk = Color.parseColor("#F2F4F8")
    private val cSub = Color.parseColor("#AAB1BD")
    private val cAccent = Color.parseColor("#3F7BFF")
    private val cGood = Color.parseColor("#2EA36B")
    private val cBad = Color.parseColor("#E5484D")

    private var checking = false   // 存檔前背景比對圖庫編號中，防連按

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tempPath = intent.getStringExtra(EXTRA_PATH)
        val bmp = tempPath?.let { BitmapFactory.decodeFile(it) }
        if (bmp == null) {
            Toast.makeText(this, "讀不到擷取畫面，請重試", Toast.LENGTH_LONG).show()
            finish(); return
        }
        source = bmp
        setContentView(buildUi())
        boxView.setBitmap(bmp)
        // detect on a worker thread, then populate
        countText.text = "偵測中…"
        Thread {
            val rects = runCatching { CardDetector.detect(bmp) }.getOrDefault(emptyList())
            runOnUiThread { boxView.setBoxes(rects) }
        }.start()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val title = TextView(this).apply {
            text = "擷取檢視 · 精靈圖像卡"; setTextColor(cInk); textSize = 17f; setTypeface(typeface, Typeface.BOLD)
        }
        val hint = TextView(this).apply {
            text = "點框選取/取消（實線＝已選）；拖動框身可移動，拖右下角可縮放。偵測不準就用下方按鈕增/刪框。\n" +
                "每張卡填：檔名（自由命名）＋精靈編號＊（遊戲因子搜尋框用的編號，AS 用它自動找關；存在圖片內，不會出現在檔名）。"
            setTextColor(cSub); textSize = 12f; setPadding(0, dp(4), 0, dp(8))
        }

        boxView = CardBoxView(this).apply {
            background = rounded(Color.parseColor("#0B0F14"), 10)
            onChange = { refreshNameList() }
        }

        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(6)) }
        toolbar.addView(btn("＋ 新增框", cField) { boxView.addBox() }, rowLp())
        toolbar.addView(btn("－ 刪除選取", cField) { boxView.deleteSelected() }, rowLp(dp(6)))
        toolbar.addView(btn("↻ 重新偵測", cField) { redetect() }, rowLp(dp(6)))

        countText = TextView(this).apply {
            text = "已選取 0 張"; setTextColor(cSub); textSize = 13f; setPadding(0, dp(6), 0, dp(6))
        }

        nameList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(nameList) }

        val save = btn("確認存檔", cGood) { confirmSave() }.apply {
            textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        }

        root.addView(title)
        root.addView(hint)
        root.addView(boxView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f))
        root.addView(toolbar)
        root.addView(countText)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f))
        root.addView(save, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        return root
    }

    private fun redetect() {
        val bmp = source ?: return
        countText.text = "偵測中…"
        Thread {
            val rects = runCatching { CardDetector.detect(bmp) }.getOrDefault(emptyList())
            runOnUiThread { boxView.setBoxes(rects) }
        }.start()
    }

    /** Rebuild the per-card naming rows for the currently-selected boxes. */
    private fun refreshNameList() {
        val bmp = source ?: return
        val sel = boxView.selectedBoxes()
        countText.text = "已選取 ${sel.size} 張（命名後存檔）"
        nameList.removeAllViews()
        if (sel.isEmpty()) {
            nameList.addView(TextView(this).apply {
                text = "尚未選取任何卡"; setTextColor(cSub); textSize = 12f; setPadding(dp(4), dp(10), dp(4), dp(10))
            })
            return
        }
        for (box in sel) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded(cPanel, 10); setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            val thumb = ImageView(this).apply {
                setImageBitmap(boxView.crop(bmp, box)); scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val edit = EditText(this).apply {
                setText(box.name); hint = "檔名：自由命名（例：厄孽提亞）"
                setTextColor(cInk); setHintTextColor(cSub); textSize = 14f
                setBackgroundColor(Color.TRANSPARENT); setSingleLine()
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { box.name = s?.toString() ?: "" }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }
            // 精靈編號＊：必填、只收數字；存成圖片內的隱藏標籤（PetIdTag），不進檔名。
            val idEdit = EditText(this).apply {
                setText(box.petId); hint = "精靈編號＊（必填）"
                setTextColor(cInk); setHintTextColor(cSub); textSize = 14f
                inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine()
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = idBg(PetIdTag.isValidId(box.petId.trim()))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                        box.petId = s?.toString()?.trim() ?: ""
                        background = idBg(PetIdTag.isValidId(box.petId))
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }
            val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fields.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            fields.addView(idEdit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            row.addView(thumb, LinearLayout.LayoutParams(dp(40), dp(56)).apply { rightMargin = dp(10) })
            row.addView(fields, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            nameList.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
    }

    private fun confirmSave() {
        val bmp = source ?: return
        val tree = PetImagePrefs.treeUri(this)
        if (tree.isNullOrBlank()) {
            Toast.makeText(this, "尚未設定圖庫資料夾，請回主頁「精靈圖庫儲存位置」選擇", Toast.LENGTH_LONG).show()
            return
        }
        val sel = boxView.selectedBoxes()
        if (sel.isEmpty()) { Toast.makeText(this, "請先選取要存的卡", Toast.LENGTH_SHORT).show(); return }
        val missing = sel.indexOfFirst { !PetIdTag.isValidId(it.petId.trim()) }
        if (missing >= 0) {
            Toast.makeText(this, "第 ${missing + 1} 張還沒填精靈編號（必填，只能是數字）", Toast.LENGTH_LONG).show()
            return
        }
        val dup = sel.groupBy { it.petId.trim() }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) {
            Toast.makeText(this, "精靈編號重複：${dup.joinToString()}（同一批每張要不同）", Toast.LENGTH_LONG).show()
            return
        }
        if (checking) return
        checking = true
        countText.text = "檢查圖庫是否已有相同編號…"
        // 讀整個圖庫的編號要碰 SAF I/O → 背景執行緒，回主執行緒再決定直接存或先確認。
        Thread {
            val existing = FactorLibrary.petIds(this, FactorLibrary.list(this))
            val clash = sel.mapNotNull { b ->
                existing.entries.firstOrNull { it.value == b.petId.trim() }?.let { "${b.petId.trim()}（${it.key}）" }
            }
            runOnUiThread {
                checking = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                countText.text = "已選取 ${sel.size} 張（命名後存檔）"
                if (clash.isEmpty()) { doSave(bmp, tree, sel); return@runOnUiThread }
                AlertDialog.Builder(this)
                    .setTitle("精靈編號已在圖庫")
                    .setMessage("以下編號已有卡：\n${clash.joinToString("\n")}\n\n仍要另存一張嗎？")
                    .setPositiveButton("仍要存檔") { _, _ -> doSave(bmp, tree, sel) }
                    .setNegativeButton("返回修改", null)
                    .show()
            }
        }.start()
    }

    private fun doSave(bmp: Bitmap, tree: String, sel: List<CardBox>) {
        // 預設檔名不含底線，避免被舊的「檔名尾段＝編號」規則誤判；編號一律存在圖片內的隱藏標籤。
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var ok = 0
        sel.forEachIndexed { i, box ->
            val safe = sanitize(box.name).ifBlank { "pet-${stamp}-${i + 1}" }
            val crop = boxView.crop(bmp, box)
            if (SafStore.writePng(this, tree, safe, crop, box.petId.trim())) ok++
            crop.recycle()
        }
        Toast.makeText(this, "已儲存 $ok / ${sel.size} 張到圖庫（${SafStore.folderLabel(tree)}）", Toast.LENGTH_LONG).show()
        if (ok > 0) finish()
    }

    /** 精靈編號欄底色：合法＝一般框，不合法／未填＝紅框。 */
    private fun idBg(valid: Boolean) = rounded(cField, 8).apply {
        setStroke(dp(1), if (valid) cLine else cBad)
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)

    private fun rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = dp(radius).toFloat()
    }

    private fun btn(text: String, bg: Int, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false; setTextColor(cInk); textSize = 13f
        background = rounded(bg, 9); setPadding(dp(8), dp(8), dp(8), dp(8))
        minWidth = 0; minHeight = 0
        setOnClickListener { onClick() }
    }

    private fun rowLp(left: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = left }

    override fun onDestroy() {
        super.onDestroy()
        source?.recycle(); source = null
        tempPath?.let { runCatching { File(it).delete() } }
    }

    companion object {
        const val EXTRA_PATH = "capture_path"
    }
}
