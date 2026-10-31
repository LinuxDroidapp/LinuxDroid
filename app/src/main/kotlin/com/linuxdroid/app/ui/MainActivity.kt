package com.linuxdroid.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import com.linuxdroid.app.ui.navigation.LinuxDroidNavGraph
import com.linuxdroid.app.ui.theme.LinuxDroidTheme
import com.linuxdroid.core.model.AntigravityAuthState
import com.linuxdroid.core.model.AppThemeMode
import com.linuxdroid.core.storage.ThemePreferences
import com.linuxdroid.linux.bootstrap.AntigravityManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single Activity for LinuxDroid.
 *
 * This activity hosts the Compose navigation graph.
 * It does NOT directly manipulate Linux processes.
 * All Linux operations go through the ViewModel → Manager → RuntimeBackend chain.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themePreferences: ThemePreferences

    @Inject
    lateinit var antigravityManager: AntigravityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAuthCallbackIntent(intent)
        enableEdgeToEdge()
        setContent {
            val themeMode by themePreferences.themeMode.collectAsState()
            val isSystemDark = isSystemInDarkTheme()
            val isDarkTheme = when (themeMode) {
                AppThemeMode.SYSTEM -> isSystemDark
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }

            LinuxDroidTheme(darkTheme = isDarkTheme) {
                LinuxDroidNavGraph()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCallbackIntent(intent)
    }

    private fun handleAuthCallbackIntent(intent: Intent?) {
        val data = intent?.data ?: return
        antigravityManager.handleOAuthCallback(data)
    }
}

@Preview(showBackground = true)
@Composable
fun MainPreview() {
    LinuxDroidTheme {
        LinuxDroidNavGraph()
    }
}
