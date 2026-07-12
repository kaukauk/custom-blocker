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
    /**
     * "Nothing-new pause": when you unlock and NO new notification arrived while the screen was off,
     * hold the screen for [nothingNewSeconds] with a countdown — so a reflex check costs something.
     */
    val nothingNewEnabled: Boolean = false,
    /** How long the pause lasts, in seconds. */
    val nothingNewSeconds: Int = 30,
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
        val NOTHING_NEW_ENABLED = booleanPreferencesKey("nothing_new_enabled")
        val NOTHING_NEW_SECONDS = intPreferencesKey("nothing_new_seconds")
    }

    val settings: Flow<AppSettings> = context.appDataStore.data.map { p ->
        AppSettings(
            dayStartHour = (p[Keys.DAY_START_HOUR] ?: 0).coerceIn(0, 23),
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME_MODE] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            defaultAllowlist = p[Keys.DEFAULT_ALLOWLIST] ?: false,
            nothingNewEnabled = p[Keys.NOTHING_NEW_ENABLED] ?: false,
            nothingNewSeconds = (p[Keys.NOTHING_NEW_SECONDS] ?: 30).coerceIn(NOTHING_NEW_MIN, NOTHING_NEW_MAX),
        )
    }

    // Cached snapshot so hot, synchronous paths (usage-period reset, notification timing, the unlock
    // guard on the accessibility thread) can read these without suspending.
    @Volatile var dayStartHour: Int = 0
        private set
    @Volatile var defaultAllowlist: Boolean = false
        private set
    @Volatile var nothingNewEnabled: Boolean = false
        private set
    @Volatile var nothingNewSeconds: Int = 30
        private set

    init {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings.onEach {
            dayStartHour = it.dayStartHour
            defaultAllowlist = it.defaultAllowlist
            nothingNewEnabled = it.nothingNewEnabled
            nothingNewSeconds = it.nothingNewSeconds
        }.launchIn(scope)
    }

    companion object {
        /** Bounds for the nothing-new pause (seconds): long enough to break the reflex, never a lockout. */
        const val NOTHING_NEW_MIN = 5
        const val NOTHING_NEW_MAX = 300
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

    suspend fun setNothingNewEnabled(enabled: Boolean) = context.appDataStore.edit {
        it[Keys.NOTHING_NEW_ENABLED] = enabled
    }

    suspend fun setNothingNewSeconds(seconds: Int) = context.appDataStore.edit {
        it[Keys.NOTHING_NEW_SECONDS] = seconds.coerceIn(NOTHING_NEW_MIN, NOTHING_NEW_MAX)
    }
}
