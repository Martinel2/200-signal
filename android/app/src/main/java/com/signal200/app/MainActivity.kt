package com.signal200.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.signal200.app.data.SignalNotificationScheduler
import com.signal200.app.ui.Signal200App
import com.signal200.app.ui.SignalTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SignalNotificationScheduler.ensureScheduled(this)
        setContent {
            SignalTheme {
                Signal200App()
            }
        }
    }
}
