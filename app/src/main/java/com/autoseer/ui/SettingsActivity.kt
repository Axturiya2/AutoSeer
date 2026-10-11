package com.autoseer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autoseer.R
import com.autoseer.core.RunPrefs
import com.autoseer.databinding.ActivitySettingsBinding

/**
 * 更多設定: global execution options ([RunPrefs] — 起始關卡／預設技能格／戰前恢復／每關重試上限)
 * plus a link into the global delay settings ([DelaySettingsActivity]).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = getString(R.string.settings_title)

        load()
        binding.btnDelays.setOnClickListener {
            startActivity(Intent(this, DelaySettingsActivity::class.java))
        }
        binding.btnSaveSettings.setOnClickListener { save(); finish() }
    }

    private fun load() {
        binding.editStartStage.setText(RunPrefs.startStage(this).toString())
        binding.editDefaultSlot.setText(RunPrefs.defaultSlot(this).toString())
        binding.healSwitch.isChecked = RunPrefs.healBeforeBattle(this)
        binding.lobbyOnExhaustSwitch.isChecked = RunPrefs.backToLobbyOnRetryExhausted(this)
        binding.editMaxRetries.setText(RunPrefs.maxRetriesPerStage(this).toString())
    }

    private fun save() {
        RunPrefs.save(
            ctx = this,
            startStage = binding.editStartStage.text.toString().toIntOrNull() ?: 1,
            defaultSlot = binding.editDefaultSlot.text.toString().toIntOrNull() ?: 2,
            healBeforeBattle = binding.healSwitch.isChecked,
            backToLobbyOnRetryExhausted = binding.lobbyOnExhaustSwitch.isChecked,
            maxRetriesPerStage = binding.editMaxRetries.text.toString().toIntOrNull()
                ?: RunPrefs.maxRetriesPerStage(this),
        )
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }
}
