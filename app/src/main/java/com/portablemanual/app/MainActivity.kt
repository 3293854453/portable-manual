package com.portablemanual.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portablemanual.app.data.prefs.SettingsRepository
import com.portablemanual.app.ui.AppNav
import com.portablemanual.app.ui.theme.PortableManualTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as PortableManualApp).container

        setContent {
            val themeMode by container.settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = SettingsRepository.THEME_SYSTEM)

            PortableManualTheme(themeMode = themeMode) {
                AppNav(container = container)
            }
        }
    }
}
