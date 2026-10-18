package com.embasic.eqr.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val historyEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val soundEnabled: Boolean = false,
    val autoZoomEnabled: Boolean = true,
    val amoledEnabled: Boolean = false,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setHistoryEnabled(enabled: Boolean)
    suspend fun setHapticsEnabled(enabled: Boolean)
    suspend fun setSoundEnabled(enabled: Boolean)
    suspend fun setAutoZoomEnabled(enabled: Boolean)
    suspend fun setAmoledEnabled(enabled: Boolean)
}

private val Context.settingsDataStore by preferencesDataStore(name = "eqr_settings")

class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {
    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            historyEnabled = prefs[Keys.HISTORY_ENABLED] ?: true,
            hapticsEnabled = prefs[Keys.HAPTICS_ENABLED] ?: true,
            soundEnabled = prefs[Keys.SOUND_ENABLED] ?: false,
            autoZoomEnabled = prefs[Keys.AUTO_ZOOM_ENABLED] ?: true,
            amoledEnabled = prefs[Keys.AMOLED_ENABLED] ?: false,
        )
    }

    override suspend fun setThemeMode(mode: ThemeMode) = update(Keys.THEME_MODE, mode.name)
    override suspend fun setHistoryEnabled(enabled: Boolean) = update(Keys.HISTORY_ENABLED, enabled)
    override suspend fun setHapticsEnabled(enabled: Boolean) = update(Keys.HAPTICS_ENABLED, enabled)
    override suspend fun setSoundEnabled(enabled: Boolean) = update(Keys.SOUND_ENABLED, enabled)
    override suspend fun setAutoZoomEnabled(enabled: Boolean) = update(Keys.AUTO_ZOOM_ENABLED, enabled)
    override suspend fun setAmoledEnabled(enabled: Boolean) = update(Keys.AMOLED_ENABLED, enabled)

    private suspend fun <T> update(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val AUTO_ZOOM_ENABLED = booleanPreferencesKey("auto_zoom_enabled")
        val AMOLED_ENABLED = booleanPreferencesKey("amoled_enabled")
    }
}
