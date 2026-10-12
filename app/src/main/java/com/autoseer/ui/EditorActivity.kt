package com.autoseer.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.autoseer.R
import com.autoseer.databinding.ActivityEditorBinding
import com.autoseer.scripts.BattlePlanParser
import com.autoseer.scripts.SeerLayout
import com.autoseer.scripts.Step

/**
 * Visual skill-plan editor ("Skill Maker"): tap the skill regions (1..5) on a
 * mock battle screen to build each stage's per-turn cast order, switch stages,
 * and save. Reads/returns a plan string in the [BattlePlanParser] format.
 *
 * The caller passes the current plan via [EXTRA_PLAN] and optional per-slot
 * display names via [EXTRA_SKILL_NAMES]; the edited plan comes back as
 * [RESULT_PLAN]. Casts can be marked `*N`（狂點至陣亡）with the [switchUntil]
 * toggle, and the marker round-trips through the plan string.
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding

    // stages[stageIndex] = ordered list of steps, one per turn (carries *N)
    private val stages = mutableListOf<MutableList<Step>>()
    private var current = 0
    private var skillNames: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        skillNames = intent.getStringArrayListExtra(EXTRA_SKILL_NAMES) ?: emptyList()
        binding.editorView.skillNames = skillNames

        loadFromIntent()

        binding.editorView.onTap = { code ->
            // *N only means anything for casts; pet switches never repeat.
            val until = binding.switchUntil.isChecked && !SeerLayout.isPetCode(code)
            stages[current].add(Step(code, untilDefeat = until))
            render()
        }
        binding.btnMode.setOnClickListener { toggleMode() }
        binding.btnPrevStage.setOnClickListener {
            if (current > 0) { current--; render() }
        }
        binding.btnNextStage.setOnClickListener {
            current++
            if (current >= stages.size) stages.add(mutableListOf())
            render()
        }
        binding.btnUndo.setOnClickListener {
            stages[current].removeLastOrNull(); render()
        }
        binding.btnClear.setOnClickListener {
            stages[current].clear(); render()
        }
        binding.btnDone.setOnClickListener { finishWithResult() }

        render()
    }

    private fun toggleMode() {
        val toPet = binding.editorView.mode == BattleEditorView.Mode.SKILL
        binding.editorView.mode = if (toPet) BattleEditorView.Mode.PET else BattleEditorView.Mode.SKILL
        binding.btnMode.setText(if (toPet) R.string.editor_to_skill else R.string.editor_to_pet)
        binding.editorHint.setText(if (toPet) R.string.editor_hint_pet else R.string.editor_hint)
    }

    private fun loadFromIntent() {
        val loaded = BattlePlanParser.toStepStages(intent.getStringExtra(EXTRA_PLAN) ?: "")
        stages.clear()
        loaded.forEach { stages.add(it.toMutableList()) }
        if (stages.isEmpty()) stages.add(mutableListOf())
        current = 0
    }

    private fun label(code: Int): String {
        if (code in 1..SeerLayout.SKILL_COUNT) {
            skillNames.getOrNull(code - 1)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return SeerLayout.labelFor(code)
    }

    private fun render() {
        binding.stageLabel.text = "關卡 ${current + 1}／${stages.size}"
        val turns = stages[current]
        binding.sequenceLabel.text = if (turns.isEmpty()) {
            getString(R.string.seq_empty)
        } else {
            turns.mapIndexed { i, s ->
                val mark = if (s.untilDefeat) "*" else ""
                "回合${i + 1}:${label(s.code)}$mark"
            }.joinToString("  →  ")
        }
    }

    private fun finishWithResult() {
        val trimmed = stages.dropLastWhile { it.isEmpty() }
        val text = BattlePlanParser.serializeSteps(trimmed)
        setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_PLAN, text))
        finish()
    }

    companion object {
        const val EXTRA_PLAN = "extra_plan"
        const val EXTRA_SKILL_NAMES = "extra_skill_names"
        const val RESULT_PLAN = "result_plan"

        fun intent(ctx: Context, plan: String, skillNames: List<String>): Intent =
            Intent(ctx, EditorActivity::class.java).apply {
                putExtra(EXTRA_PLAN, plan)
                putStringArrayListExtra(EXTRA_SKILL_NAMES, ArrayList(skillNames))
            }
    }
}
