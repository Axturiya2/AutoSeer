package com.autoseer.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.autoseer.R
import com.autoseer.core.MacroStorage
import com.autoseer.databinding.ActivityMacroShotsBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自製腳本（FR-12）截圖庫：懸浮窗「截圖製作」截的圖和從相簿匯入的圖都在這裡（新的在前），
 * 長按可刪除。之後的編輯器從這裡挑截圖來框按鈕、點座標。
 */
class MacroShotsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMacroShotsBinding
    /** 剛截／剛匯入的那張，標「（新）」。 */
    private var highlight: String? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(::importShot)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMacroShotsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = getString(R.string.macro_shots_title)
        highlight = intent.getStringExtra(EXTRA_HIGHLIGHT)
        binding.btnImportShot.setOnClickListener { pickImage.launch("image/*") }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        highlight = intent.getStringExtra(EXTRA_HIGHLIGHT) ?: highlight
    }

    override fun onResume() {
        super.onResume()
        rebuildList()
    }

    private fun rebuildList() {
        val shots = MacroStorage.listShots(this)
        binding.listContainer.removeAllViews()
        binding.emptyHint.visibility = if (shots.isEmpty()) View.VISIBLE else View.GONE
        shots.forEach { binding.listContainer.addView(row(it)) }
    }

    private fun row(file: File): View {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val thumb = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 })

        val image = ImageView(this).apply {
            setImageBitmap(thumb)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val time = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))
        val isNew = file.name == highlight
        val label = TextView(this).apply {
            text = getString(
                if (isNew) R.string.macro_shot_label_new else R.string.macro_shot_label,
                time, bounds.outWidth, bounds.outHeight,
            )
            setTextColor(ContextCompat.getColor(context, if (isNew) R.color.seer_accent else R.color.white))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.card))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(image, LinearLayout.LayoutParams(dp(160), dp(90)))
            addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            setOnLongClickListener { confirmDelete(file, time); true }
        }
    }

    private fun confirmDelete(file: File, time: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.macro_shot_delete_title)
            .setMessage(getString(R.string.macro_shot_delete_msg, time))
            .setPositiveButton(R.string.script_delete_confirm_ok) { _, _ ->
                file.delete()
                rebuildList()
            }
            .setNegativeButton(R.string.script_cancel_select, null)
            .show()
    }

    /** 讀圖、縮放、寫檔放背景，回主執行緒刷新清單。 */
    private fun importShot(uri: Uri) {
        Thread {
            val result = runCatching { MacroStorage.importShot(this, uri) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { f ->
                    highlight = f.name
                    Toast.makeText(this, R.string.macro_shot_imported, Toast.LENGTH_SHORT).show()
                    rebuildList()
                }.onFailure {
                    Toast.makeText(this, getString(R.string.macro_shot_import_fail, it.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        const val EXTRA_HIGHLIGHT = "extra_highlight"

        fun intent(ctx: Context, highlight: String? = null): Intent =
            Intent(ctx, MacroShotsActivity::class.java).apply {
                if (highlight != null) putExtra(EXTRA_HIGHLIGHT, highlight)
            }
    }
}
