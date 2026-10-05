package com.autoseer.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import com.autoseer.core.FactorLibrary
import com.autoseer.core.FactorPlanEntry
import com.autoseer.core.FactorPlanPrefs
import com.autoseer.core.PetImagePrefs
import com.autoseer.core.SafImage
import com.autoseer.core.SafStore
import com.autoseer.core.ScriptStore
import com.autoseer.core.SeerScript

/**
 * 因子關卡選關 — 點選剔除式（取代舊的兩層拖放）。
 *
 * 因為導航改用「搜尋 ID 直達」(跟運行順序無關)，這裡不再需要拖曳排順序：一格一個因子，
 * **點卡片 = 加入/剔除**，預設全選；配 全選/全不選/反選、搜尋過濾、舊↔新 排序切換。
 * 長按一張「已選」卡可指派它專屬的腳本(否則跟隨主頁所選腳本)。儲存只寫入 [FactorPlanPrefs]，
 * 不碰導航/戰鬥模組。運行順序＝依檔名(NN)排列的已選子集。
 */
class FactorListActivity : AppCompatActivity() {

    private val cBg = Color.parseColor("#15181C")
    private val cPanel = Color.parseColor("#1F2226")
    private val cField = Color.parseColor("#2A2F36")
    private val cLine = Color.parseColor("#33383F")
    private val cInk = Color.parseColor("#F2F4F8")
    private val cSub = Color.parseColor("#AAB1BD")
    private val cAccent = Color.parseColor("#3F7BFF")
    private val cGood = Color.parseColor("#2EA36B")
    private val cCell = Color.parseColor("#0B0F14")

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val allImages = ArrayList<SafImage>()            // 全部卡（依檔名）
    private val selected = HashSet<String>()                 // 已選 displayName
    private val assign = HashMap<String, String?>()          // 每因子腳本 id（null＝預設）
    private val thumbs = HashMap<String, Bitmap>()
    private var factorScripts: List<SeerScript> = emptyList()
    private var selectedScriptName: String = ""

    private var sortDesc = true                              // 預設 新→舊（對齊遊戲顯示）
    private var filter = ""
    private var cols = 5

    private lateinit var grid: LinearLayout
    private lateinit var countText: TextView
    private lateinit var sortBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "因子關卡選關"

        val tree = PetImagePrefs.treeUri(this)
        if (!tree.isNullOrBlank()) allImages.addAll(FactorLibrary.list(this))
        factorScripts = ScriptStore.list(this, SeerScript.CATEGORY_SEER_FACTOR)
        selectedScriptName = ScriptStore.selected(this)?.displayName ?: ""

        // 初始化已選：有存過計畫→沿用(對齊現存檔案)；沒存過→預設全選。
        val existing = allImages.map { it.displayName }.toHashSet()
        val saved = FactorPlanPrefs.getEntries(this)
        if (saved != null) {
            saved.forEach { if (it.name in existing) { selected.add(it.name); assign[it.name] = it.scriptId } }
        } else {
            selected.addAll(existing)
        }

        setContentView(buildUi(tree))
        if (allImages.isNotEmpty()) loadThumbsAsync()
    }

    private fun buildUi(tree: String?): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        content.addView(titleTv("因子關卡選關"))
        content.addView(hintTv("點卡片＝加入/剔除（打勾＝會打）。預設全選，要跳過哪關就點掉。導航用搜尋 ID 直達，與順序無關。長按已選卡＝指派專屬腳本。"))

        if (tree.isNullOrBlank() || allImages.isEmpty()) {
            content.addView(TextView(this).apply {
                text = if (tree.isNullOrBlank())
                    "尚未設定精靈圖庫資料夾。\n請回主頁點「選擇精靈圖庫資料夾」，再用懸浮列『擷取卡』存因子卡。"
                else
                    "圖庫沒有因子卡。\n請用懸浮列『擷取卡』先擷取幾張再回來選關。"
                setTextColor(cSub); textSize = 14f; setPadding(dp(4), dp(20), dp(4), dp(20))
            })
            return content
        }

        // 控制列一：全選 / 全不選 / 反選
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(btn("全選", cField) { selected.clear(); selected.addAll(allImages.map { it.displayName }); rebuild() }, lpW(1f))
        row1.addView(btn("全不選", cField) { selected.clear(); rebuild() }, lpW(1f, dp(6)))
        row1.addView(btn("反選", cField) {
            val all = allImages.map { it.displayName }.toHashSet()
            val inv = all - selected
            selected.clear(); selected.addAll(inv); rebuild()
        }, lpW(1f, dp(6)))
        content.addView(row1, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        // 控制列二：排序切換 + 搜尋 + 已選計數
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sortBtn = btn(sortLabel(), cField) { sortDesc = !sortDesc; sortBtn.text = sortLabel(); rebuild() }
        val search = EditText(this).apply {
            hint = "搜尋名稱/ID"; setHintTextColor(cSub); setTextColor(cInk); textSize = 13f
            background = rounded(cField, 8); setPadding(dp(10), dp(8), dp(10), dp(8))
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { filter = s?.toString()?.trim() ?: ""; rebuild() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        countText = TextView(this).apply { setTextColor(cSub); textSize = 12f; gravity = Gravity.CENTER }
        row2.addView(sortBtn, LinearLayout.LayoutParams(WRAP, dp(38)))
        row2.addView(search, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(6); rightMargin = dp(6) })
        row2.addView(countText, LinearLayout.LayoutParams(WRAP, WRAP))
        content.addView(row2, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        // 卡片格
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(grid) }
        content.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })

        // 儲存
        content.addView(
            btn("儲存並返回", cGood) { persist(); toast("已選 ${selected.size} 個因子（會打）"); finish() }
                .apply { setTypeface(typeface, Typeface.BOLD) },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) },
        )

        content.doOnLayout {
            val wdp = it.width / resources.displayMetrics.density
            cols = (wdp / 92f).toInt().coerceIn(3, 9)
            rebuild()
        }
        return content
    }

    private fun sortLabel() = if (sortDesc) "排序：新→舊" else "排序：舊→新"

    /** 目前要顯示的卡（過濾＋排序）。 */
    private fun visibleImages(): List<SafImage> {
        val f = allImages.filter { filter.isEmpty() || it.displayName.contains(filter, ignoreCase = true) }
        return if (sortDesc) f.sortedByDescending { it.displayName } else f.sortedBy { it.displayName }
    }

    private fun rebuild() {
        countText.text = "已選 ${selected.size}/${allImages.size}"
        grid.removeAllViews()
        val cells = visibleImages().map { makeCell(it) }
        var row: LinearLayout? = null
        cells.forEachIndexed { i, cell ->
            if (i % cols == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
            }
            row!!.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = if (i % cols == 0) 0 else dp(6) })
        }
        val rem = cells.size % cols
        if (rem != 0 && row != null) repeat(cols - rem) {
            row!!.addView(View(this), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
        }
    }

    private fun makeCell(image: SafImage): View {
        val name = image.displayName
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val frame = FrameLayout(this)
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        thumbs[name]?.let { img.setImageBitmap(it) }
        frame.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(104)))
        val dim = View(this).apply { setBackgroundColor(Color.parseColor("#99000000")) }
        frame.addView(dim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(104)))
        val badge = TextView(this).apply {
            text = "✓"; setTextColor(Color.WHITE); textSize = 13f
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; background = oval(cGood)
        }
        frame.addView(badge, FrameLayout.LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(5); topMargin = dp(5) })

        val label = TextView(this).apply {
            text = name.substringBeforeLast('.'); setTextColor(cSub); textSize = 10f
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0)
        }
        col.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP))
        col.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP))

        fun decorate() {
            val on = name in selected
            frame.background = rounded(if (on) cAccent else cCell, 10)
            dim.visibility = if (on) View.GONE else View.VISIBLE
            badge.visibility = if (on) View.VISIBLE else View.GONE
            label.setTextColor(if (name in assign && assign[name] != null) cAccent else cSub)
        }
        decorate()

        col.setOnClickListener {
            if (name in selected) selected.remove(name) else selected.add(name)
            decorate(); countText.text = "已選 ${selected.size}/${allImages.size}"
        }
        col.setOnLongClickListener {
            if (name in selected) { pickScript(name); true } else {
                toast("先點選加入，才能指派腳本"); true
            }
        }
        return col
    }

    private fun loadThumbsAsync() {
        val imgs = allImages.toList()
        Thread {
            val local = HashMap<String, Bitmap>()
            for (im in imgs) {
                if (thumbs.containsKey(im.displayName)) continue
                val b = SafStore.readBitmap(this, im.uri) ?: continue
                local[im.displayName] = b
            }
            runOnUiThread { thumbs.putAll(local); rebuild() }
        }.start()
    }

    /** 儲存：已選子集依檔名(NN)排列 + 各自的腳本指派。運行順序對搜尋導航無影響。 */
    private fun persist() {
        val ordered = allImages.map { it.displayName }.filter { it in selected }
        FactorPlanPrefs.setEntries(this, ordered.map { FactorPlanEntry(it, assign[it]) })
    }

    /** 長按已選卡：指派它專屬的腳本（或跟隨主頁所選）。 */
    private fun pickScript(name: String) {
        val labels = ArrayList<String>()
        labels += "預設（跟隨：${selectedScriptName.ifBlank { "所選腳本" }}）"
        factorScripts.forEach { labels += it.displayName }
        val cur = assign[name]
        val checked = if (cur == null) 0
            else factorScripts.indexOfFirst { it.id == cur }.let { if (it < 0) 0 else it + 1 }
        val items = Array<CharSequence>(labels.size) { labels[it] }
        AlertDialog.Builder(this)
            .setTitle("指派「${name.substringBeforeLast('.')}」的腳本")
            .setSingleChoiceItems(items, checked) { d, which ->
                assign[name] = if (which == 0) null else factorScripts[which - 1].id
                rebuild(); d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- helpers ----
    private fun titleTv(t: String) = TextView(this).apply {
        text = t; setTextColor(cInk); textSize = 18f; setTypeface(typeface, Typeface.BOLD)
    }
    private fun hintTv(t: String) = TextView(this).apply {
        text = t; setTextColor(cSub); textSize = 12f; setPadding(0, dp(4), 0, dp(2))
    }
    private fun rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = dp(radius).toFloat()
    }
    private fun oval(fill: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
    private fun btn(text: String, bg: Int, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false; setTextColor(cInk); textSize = 13f
        background = rounded(bg, 9); setPadding(dp(8), dp(8), dp(8), dp(8)); minWidth = 0; minHeight = 0
        setOnClickListener { onClick() }
    }
    private fun lpW(weight: Float, leftMargin: Int = 0) =
        LinearLayout.LayoutParams(0, WRAP, weight).apply { this.leftMargin = leftMargin }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        thumbs.values.forEach { it.recycle() }
        thumbs.clear()
    }
}
