package com.autoseer.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.autoseer.R

/** 每日任務: placeholder page, feature not built yet. */
class DailyTasksActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_daily_tasks)
        supportActionBar?.title = getString(R.string.daily_title)
    }
}
