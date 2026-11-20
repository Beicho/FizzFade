package io.github.qishui.automix.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import io.github.qishui.automix.ModuleActions
import io.github.qishui.automix.SettingsActivity

class MainActivity : ComponentActivity() {
    private var store: SettingsStore? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = ModuleActions.prefs(this)
        // After a crash of this UI the switches stay reachable on the plain platform screen.
        if (prefs.getBoolean(ModuleActions.UI_FAILED, false)) {
            ModuleActions.recordOwnExits(this)
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }
        ModuleActions.installCrashRecorder(this)
        ModuleActions.logOpen(this, "miuix")
        val settings = SettingsStore(applicationContext)
        store = settings
        setContent {
            val dark = isSystemInDarkTheme()
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
                onDispose { }
            }
            AppTheme(monet = settings.monet) {
                App(settings)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Lets the host read the provider; this process now publishes it.
        ModuleActions.grantHost(this)
        store?.resume()
    }

    override fun onPause() {
        store?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        store?.close()
        super.onDestroy()
    }
}
