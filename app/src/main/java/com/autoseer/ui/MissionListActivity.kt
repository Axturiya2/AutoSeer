package com.autoseer.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.autoseer.R
import com.autoseer.core.SeerScript
import com.autoseer.databinding.ActivityMissionListBinding

/**
 * 周回腳本: the two mission blocks. 精靈因子 opens its script list; 每日任務 is a
 * placeholder page for now.
 */
class MissionListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMissionListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMissionListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = getString(R.string.missions_title)

        binding.btnSeerFactor.setOnClickListener {
            startActivity(
                ScriptListActivity.intent(
                    this,
                    SeerScript.CATEGORY_SEER_FACTOR,
                    getString(R.string.mission_seer_factor),
                )
            )
        }
        binding.btnDaily.setOnClickListener {
            startActivity(Intent(this, DailyTasksActivity::class.java))
        }
    }
}
