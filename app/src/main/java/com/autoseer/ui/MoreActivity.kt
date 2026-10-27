package com.autoseer.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.autoseer.R

/** 更多選項 / Debug: merged page — language/about extras plus the debug-tool notes. */
class MoreActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_more)
        supportActionBar?.title = getString(R.string.more_debug_title)
    }
}
