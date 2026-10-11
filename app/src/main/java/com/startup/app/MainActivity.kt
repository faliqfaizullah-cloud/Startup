package com.startup.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getIntExtra("meeting", -1).takeIf { it >= 0 }
        val tid = intent.getLongExtra("transcript", -1L).takeIf { it >= 0L }
        Reminders.scheduleAll(this)
        setContent { StartupRoot(id, tid) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}
