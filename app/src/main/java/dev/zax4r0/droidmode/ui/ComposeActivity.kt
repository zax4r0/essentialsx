package dev.zax4r0.droidmode.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable

abstract class ComposeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }

    fun essentialsContent(content: @Composable () -> Unit) {
        setContent {
            DroidModeTheme {
                content()
            }
        }
    }
}
