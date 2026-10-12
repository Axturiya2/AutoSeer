package com.autoseer.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.autoseer.R
import com.autoseer.core.ScriptStore
import com.autoseer.core.SeerScript
import com.autoseer.databinding.ActivityScriptEditBinding
import com.autoseer.scripts.BattlePlanParser
import com.autoseer.scripts.SeerLayout

/**
 * Edits a single [SeerScript]: 名稱／備註／技能順序（開視覺化編輯器）. Run options
 * and delays are global now (更多設定), so they're no longer on this page.
 */
class ScriptEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScriptEditBinding
    private lateinit var script: SeerScript
    private var planText: String = ""

    private val editorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra(EditorActivity.RESULT_PLAN)?.let {
                    planText = it
                    refreshSummary()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = getString(R.string.script_edit_title)

        val id = intent.getStringExtra(EXTRA_ID)
        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: SeerScript.CATEGORY_SEER_FACTOR
        script = id?.let { ScriptStore.get(this, it) }
            ?: SeerScript.new(getString(R.string.script_new_name), category)
        planText = script.planText

        binding.editName.setText(script.name)
        binding.editNote.setText(script.note)
        refreshSummary()

        binding.btnOpenEditor.setOnClickListener {
            editorLauncher.launch(EditorActivity.intent(this, planText, emptyList()))
        }
        binding.btnSaveScript.setOnClickListener {
            persist(); Toast.makeText(this, R.string.script_saved, Toast.LENGTH_SHORT).show(); finish()
        }
        binding.btnSetActive.setOnClickListener {
            val saved = persist()
            ScriptStore.setSelected(this, saved.id)
            Toast.makeText(this, R.string.script_activated, Toast.LENGTH_SHORT).show()
            finish()
        }
        binding.btnDeleteScript.setOnClickListener {
            ScriptStore.delete(this, script.id)
            Toast.makeText(this, R.string.script_deleted, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun refreshSummary() {
        val stages = BattlePlanParser.toStepStages(planText)
        binding.skillOrderSummary.text = if (stages.isEmpty()) {
            getString(R.string.skill_not_recorded)
        } else {
            stages.mapIndexed { i, steps ->
                val body = steps.joinToString(" ") { st ->
                    SeerLayout.labelFor(st.code) + if (st.untilDefeat) "*" else ""
                }
                "關${i + 1}: $body"
            }.joinToString("   |   ")
        }
    }

    private fun persist(): SeerScript {
        val updated = script.copy(
            name = binding.editName.text.toString().trim(),
            note = binding.editNote.text.toString().trim(),
            planText = planText,
        )
        ScriptStore.upsert(this, updated)
        script = updated
        return updated
    }

    companion object {
        const val EXTRA_ID = "extra_id"
        const val EXTRA_CATEGORY = "extra_category"

        fun intent(ctx: Context, id: String?, category: String = SeerScript.CATEGORY_SEER_FACTOR): Intent =
            Intent(ctx, ScriptEditActivity::class.java).apply {
                if (id != null) putExtra(EXTRA_ID, id)
                putExtra(EXTRA_CATEGORY, category)
            }
    }
}
