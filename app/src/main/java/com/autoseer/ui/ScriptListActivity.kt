package com.autoseer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autoseer.R
import com.autoseer.core.ScriptStore
import com.autoseer.core.SeerScript
import com.autoseer.databinding.ActivityScriptListBinding
import com.google.android.material.button.MaterialButton

/**
 * Script list for one mission category (精靈因子…): 匯入／匯出／＋新增腳本 plus a
 * tile per saved script (active one marked ●). Tapping a tile opens the editor.
 */
class ScriptListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScriptListBinding
    private lateinit var category: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        category = intent.getStringExtra(EXTRA_CATEGORY) ?: SeerScript.CATEGORY_SEER_FACTOR
        supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.script_list_title)

        binding.btnAdd.setOnClickListener {
            startActivity(ScriptEditActivity.intent(this, null, category))
        }
        binding.btnExport.setOnClickListener { exportToClipboard() }
        binding.btnImport.setOnClickListener { importFromClipboard() }
    }

    override fun onResume() {
        super.onResume()
        rebuildList()
    }

    private fun rebuildList() {
        val scripts = ScriptStore.list(this, category)
        val selectedId = ScriptStore.selectedId(this)
        binding.listContainer.removeAllViews()
        binding.emptyHint.visibility = if (scripts.isEmpty()) View.VISIBLE else View.GONE

        for (s in scripts) {
            val marker = if (s.id == selectedId) "● " else ""
            val btn = MaterialButton(
                this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                text = marker + s.displayName
                setOnClickListener { startActivity(ScriptEditActivity.intent(this@ScriptListActivity, s.id, category)) }
            }
            binding.listContainer.addView(btn)
        }
    }

    private fun exportToClipboard() {
        val scripts = ScriptStore.list(this, category)
        if (scripts.isEmpty()) {
            Toast.makeText(this, R.string.no_scripts, Toast.LENGTH_SHORT).show()
            return
        }
        val json = ScriptStore.exportAllJson(scripts)
        clipboard().setPrimaryClip(ClipData.newPlainText("AutoSeer scripts", json))
        Toast.makeText(this, getString(R.string.export_copied, scripts.size), Toast.LENGTH_SHORT).show()
    }

    private fun importFromClipboard() {
        val clip = clipboard().primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        if (text.isBlank()) {
            Toast.makeText(this, R.string.import_empty, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { ScriptStore.importAndSave(this, text) }
            .onSuccess { count ->
                Toast.makeText(this, getString(R.string.import_ok, count), Toast.LENGTH_SHORT).show()
                rebuildList()
            }
            .onFailure {
                Toast.makeText(this, R.string.import_fail, Toast.LENGTH_LONG).show()
            }
    }

    private fun clipboard(): ClipboardManager =
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    companion object {
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_TITLE = "extra_title"

        fun intent(ctx: Context, category: String, title: String): Intent =
            Intent(ctx, ScriptListActivity::class.java).apply {
                putExtra(EXTRA_CATEGORY, category)
                putExtra(EXTRA_TITLE, title)
            }
    }
}
