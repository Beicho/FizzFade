package io.github.qishui.automix.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.qishui.automix.ModuleActions
import io.github.qishui.automix.ModuleStatus
import io.github.qishui.automix.SettingsSchema

/**
 * The settings as Compose state, written through to the module's preferences (which the host
 * reads over the provider). The provider runs in this process, so its status writes arrive
 * through the preference listener; a 2 s tick only ages the "connected" window while visible.
 */
class SettingsStore(private val context: Context) {
    private val prefs: SharedPreferences = ModuleActions.prefs(context)
    private val toggles = mutableStateMapOf<String, Boolean>()
    private val handler = Handler(Looper.getMainLooper())
    private val keys = SettingsSchema.TOGGLES.map { it.key }.toSet()

    var duration by mutableIntStateOf(ModuleActions.duration(context))
        private set
    var status: ModuleStatus by mutableStateOf(ModuleStatus.read(context))
        private set
    var logChars by mutableIntStateOf(ModuleActions.logChars(context))
        private set
    var monet by mutableStateOf(prefs.getBoolean(KEY_MONET, false))
        private set
    /** Real blur needs Android 12; below that the bars only get the tint anyway. */
    var blur by mutableStateOf(prefs.getBoolean(KEY_BLUR, android.os.Build.VERSION.SDK_INT >= 31))
        private set

    // Held in a field: SharedPreferences keeps its listeners only weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == null -> reload()
            key == SettingsSchema.DURATION -> duration = ModuleActions.duration(context)
            else -> {
                if (key in keys) toggles[key] = prefs.getBoolean(key, SettingsSchema.find(key)?.fallback ?: true)
                // Status and log lines arrive in bursts (up to 20 per host report), and a switch
                // changes what the restart hint says: coalesce them into one status read.
                handler.removeCallbacks(coalesced)
                handler.postDelayed(coalesced, 150)
            }
        }
    }
    private val coalesced = Runnable { refresh() }
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    init {
        reload()
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun reload() {
        for (toggle in SettingsSchema.TOGGLES) toggles[toggle.key] = prefs.getBoolean(toggle.key, toggle.fallback)
        duration = ModuleActions.duration(context)
        refresh()
    }

    fun refresh() {
        status = ModuleStatus.read(context)
        logChars = ModuleActions.logChars(context)
    }

    fun isOn(key: String): Boolean = toggles[key] ?: true

    fun set(key: String, value: Boolean) {
        toggles[key] = value
        ModuleActions.setBoolean(context, key, value)
    }

    /** Folded away while the switch it depends on (and, for the mix, the master switch) is off. */
    fun available(toggle: SettingsSchema.Toggle): Boolean {
        val parent = toggle.parent ?: return true
        return (toggle.ui() || isOn("enabled")) && isOn(parent)
    }

    fun previewDuration(millis: Int) {
        duration = SettingsSchema.clampDuration(millis)
    }

    fun commitDuration() = ModuleActions.setDuration(context, duration)

    fun updateMonet(value: Boolean) {
        monet = value
        prefs.edit().putBoolean(KEY_MONET, value).apply()
    }

    fun updateBlur(value: Boolean) {
        blur = value
        prefs.edit().putBoolean(KEY_BLUR, value).apply()
    }

    fun resume() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun pause() = handler.removeCallbacks(tick)

    fun close() {
        pause()
        handler.removeCallbacks(coalesced)
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        /** App appearance only; not part of the host's settings. */
        const val KEY_MONET = "app_monet"
        const val KEY_BLUR = "app_blur"
    }
}
