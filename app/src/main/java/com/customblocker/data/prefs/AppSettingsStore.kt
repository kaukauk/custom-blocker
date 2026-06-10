package com.customblocker.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/** App appearance preference. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** General app-wide settings (not per-profile, not Strict Mode). */
data class AppSettings(
    /** Hour (0–23, local) at which the "day" rolls over for per-day usage/launch limits. */
    val dayStartHour: Int = 0,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Whether newly-created profiles default to allow-list (vs block-list). */
    val defaultAllowlist: Boolean = false,
)

private val Context.appDataStore by preferencesDataStore(name = "app_settings")

@Singleton
class AppSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val DAY_START_HOUR = intPreferencesKey("day_start_hour")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEFAULT_ALLOWLIST = booleanPreferencesKey("default_allowlist")
    }

    val settings: Flow<AppSettings> = context.appDataStore.data.map { p ->
        AppSettings(
            dayStartHour = (p[Keys.DAY_START_HOUR] ?: 0).coerceIn(0, 23),
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME_MODE] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            defaultAllowlist = p[Keys.DEFAULT_ALLOWLIST] ?: false,
        )
    }

    // Cached snapshot so hot, synchronous paths (usage-period reset, notification timing) can read
    // the day-start without suspending.
    @Volatile var dayStartHour: Int = 0
        private set
    @Volatile var defaultAllowlist: Boolean = false
        private set

    init {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings.onEach {
            dayStartHour = it.dayStartHour
            defaultAllowlist = it.defaultAllowlist
        }.launchIn(scope)
    }

    suspend fun setDayStartHour(hour: Int) = context.appDataStore.edit {
        it[Keys.DAY_START_HOUR] = hour.coerceIn(0, 23)
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.appDataStore.edit {
        it[Keys.THEME_MODE] = mode.name
    }

    suspend fun setDefaultAllowlist(enabled: Boolean) = context.appDataStore.edit {
        it[Keys.DEFAULT_ALLOWLIST] = enabled
    }
}
