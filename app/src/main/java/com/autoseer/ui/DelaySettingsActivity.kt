package com.autoseer.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autoseer.R
import com.autoseer.core.DelayPrefs
import com.autoseer.databinding.ActivityDelaySettingsBinding

/**
 * Global pacing-delay settings (shared by all scripts): after 恢復／進戰／技能／
 * 換精靈／結算／撤退各步. Persisted via [DelayPrefs] and applied when a run starts.
 */
class DelaySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDelaySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDelaySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = getString(R.string.delay_title)

        load()
        binding.btnSaveDelays.setOnClickListener { save(); finish() }
        binding.btnResetDelays.setOnClickListener { resetToDefault() }
    }

    private fun load() {
        binding.editHeal.setText(DelayPrefs.afterHeal(this).toString())
        binding.editEnter.setText(DelayPrefs.afterEnter(this).toString())
        binding.editSkill.setText(DelayPrefs.afterSkill(this).toString())
        binding.editSwitch.setText(DelayPrefs.afterSwitch(this).toString())
        binding.editResult.setText(DelayPrefs.afterResult(this).toString())
        binding.editRetreat.setText(DelayPrefs.afterRetreat(this).toString())
    }

    private fun field(text: CharSequence?): Int =
        (text?.toString()?.trim()?.toIntOrNull() ?: DelayPrefs.DEFAULT_MS).coerceAtLeast(0)

    private fun save() {
        DelayPrefs.save(
            ctx = this,
            afterHeal = field(binding.editHeal.text),
            afterEnter = field(binding.editEnter.text),
            afterSkill = field(binding.editSkill.text),
            afterSwitch = field(binding.editSwitch.text),
            afterResult = field(binding.editResult.text),
            afterRetreat = field(binding.editRetreat.text),
        )
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }

    private fun resetToDefault() {
        val d = DelayPrefs.DEFAULT_MS.toString()
        binding.editHeal.setText(d)
        binding.editEnter.setText(d)
        binding.editSkill.setText(d)
        binding.editSwitch.setText(d)
        binding.editResult.setText(d)
        binding.editRetreat.setText(d)
    }
}
