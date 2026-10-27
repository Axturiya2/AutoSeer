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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.autoseer.R
import com.autoseer.core.ScriptStore
import com.autoseer.core.SeerScript
import com.autoseer.databinding.ActivityScriptListBinding
import com.autoseer.scripts.MultiSelection
import com.autoseer.scripts.ScriptBatch
import com.google.android.material.button.MaterialButton

/**
 * Script list for one mission category (精靈因子…): 匯入／匯出／＋新增腳本 plus a
 * tile per saved script (active one marked ●). Tapping a tile opens the editor.
 *
 * Long-pressing a tile enters selection mode (FR-10): taps then toggle ☑/☐ and the
 * top bar becomes 全選／匯出／刪除／取消, so one or several scripts can be exported
 * (same JSON array as 匯出腳本) or deleted after a confirm dialog.
 */
class ScriptListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScriptListBinding
    private lateinit var category: String
    private var scripts: List<SeerScript> = emptyList()
    private val selection = MultiSelection()

    /** 選取模式中按返回＝離開選取模式，不關頁。 */
    private val exitSelectionOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = endSelection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        category = intent.getStringExtra(EXTRA_CATEGORY) ?: SeerScript.CATEGORY_SEER_FACTOR
        supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.script_list_title)

        binding.btnAdd.setOnClickListener { openEditor(null) }
        binding.btnShots.setOnClickListener { startActivity(MacroShotsActivity.intent(this)) }
        binding.btnExport.setOnClickListener { exportToClipboard() }
        binding.btnImport.setOnClickListener { importFromClipboard() }

        binding.btnSelectAll.setOnClickListener {
            selection.toggleAll(scripts.map { it.id }); refreshSelectionUi()
        }
        binding.btnExportSelected.setOnClickListener { exportSelected() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        binding.btnCancelSelect.setOnClickListener { endSelection() }
        onBackPressedDispatcher.addCallback(this, exitSelectionOnBack)

        savedInstanceState?.getStringArrayList(STATE_SELECTED)?.let { selection.restore(it) }
    }

    override fun onResume() {
        super.onResume()
        rebuildList()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SELECTED, ArrayList(selection.selected))
    }

    private fun rebuildList() {
        scripts = ScriptStore.list(this, category)
        selection.retain(scripts.map { it.id })
        binding.listContainer.removeAllViews()
        binding.emptyHint.visibility = if (scripts.isEmpty()) View.VISIBLE else View.GONE

        for (s in scripts) {
            val btn = MaterialButton(
                this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener {
                    if (selection.active) {
                        selection.toggle(s.id); refreshSelectionUi()
                    } else {
                        openEditor(s.id)
                    }
                }
                setOnLongClickListener {
                    selection.start(s.id); refreshSelectionUi(); true
                }
            }
            binding.listContainer.addView(btn)
        }
        refreshSelectionUi()
    }

    /** Swap the top bar and re-label tiles (☑/☐ only while selecting). */
    private fun refreshSelectionUi() {
        val selecting = selection.active
        val normal = if (selecting) View.GONE else View.VISIBLE
        binding.normalBar.visibility = normal
        binding.btnAdd.visibility = normal
        binding.btnShots.visibility = if (isCustom) normal else View.GONE
        binding.selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        binding.multiSelectHint.visibility =
            if (!selecting && scripts.isNotEmpty()) View.VISIBLE else View.GONE
        binding.selectionCount.text = getString(R.string.script_selected_count, selection.size)
        exitSelectionOnBack.isEnabled = selecting

        val activeId = ScriptStore.selectedId(this)
        scripts.forEachIndexed { i, s ->
            (binding.listContainer.getChildAt(i) as? MaterialButton)?.text = tileLabel(s, activeId, selecting)
        }
    }

    private fun tileLabel(s: SeerScript, activeId: String?, selecting: Boolean): String {
        val check = when {
            !selecting -> ""
            s.id in selection -> "☑ "
            else -> "☐ "
        }
        val marker = if (s.id == activeId) "● " else ""
        return check + marker + s.displayName
    }

    private val isCustom: Boolean get() = category == SeerScript.CATEGORY_CUSTOM

    /** [id] = null 為新增。自製腳本的編輯器是 FR-12 第 3 階段，先提示。 */
    private fun openEditor(id: String?) {
        if (isCustom) {
            Toast.makeText(this, R.string.macro_editor_pending, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(ScriptEditActivity.intent(this, id, category))
    }

    private fun endSelection() {
        selection.clear()
        refreshSelectionUi()
    }

    private fun exportToClipboard() {
        val all = ScriptStore.list(this, category)
        if (all.isEmpty()) {
            Toast.makeText(this, R.string.no_scripts, Toast.LENGTH_SHORT).show()
            return
        }
        copyJson(all)
    }

    private fun exportSelected() {
        val picked = ScriptBatch.pick(scripts, selection.selected) { it.id }
        if (picked.isEmpty()) return
        copyJson(picked)
        endSelection()
    }

    private fun copyJson(list: List<SeerScript>) {
        val json = ScriptStore.exportAllJson(this, list)
        if (json.length > MAX_CLIP_CHARS) {
            // 自製腳本的圖片嵌在 JSON 裡，太大塞不進剪貼簿（Binder 約 1MB 上限）
            Toast.makeText(this, R.string.export_too_large, Toast.LENGTH_LONG).show()
            return
        }
        clipboard().setPrimaryClip(ClipData.newPlainText("AutoSeer scripts", json))
        Toast.makeText(this, getString(R.string.export_copied, list.size), Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteSelected() {
        val picked = ScriptBatch.pick(scripts, selection.selected) { it.id }
        if (picked.isEmpty()) return
        val names = picked.joinToString("\n") { "・" + it.displayName }
        AlertDialog.Builder(this)
            .setTitle(R.string.script_delete_confirm_title)
            .setMessage(getString(R.string.script_delete_confirm_msg, picked.size, names))
            .setPositiveButton(R.string.script_delete_confirm_ok) { _, _ ->
                val n = ScriptStore.deleteMany(this, picked.map { it.id }.toSet())
                Toast.makeText(this, getString(R.string.script_deleted_n, n), Toast.LENGTH_SHORT).show()
                selection.clear()
                rebuildList()
            }
            .setNegativeButton(R.string.script_cancel_select, null)
            .show()
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
        private const val STATE_SELECTED = "state_selected"

        /** 剪貼簿文字上限（UTF-16 每字 2 bytes，留在 Binder 1MB 以內）。 */
        private const val MAX_CLIP_CHARS = 400_000

        fun intent(ctx: Context, category: String, title: String): Intent =
            Intent(ctx, ScriptListActivity::class.java).apply {
                putExtra(EXTRA_CATEGORY, category)
                putExtra(EXTRA_TITLE, title)
            }
    }
}
