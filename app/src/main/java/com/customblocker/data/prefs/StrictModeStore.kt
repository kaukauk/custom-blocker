package com.customblocker.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** How the user must authenticate to lower/disable Strict Mode (PLAN.md §11). */
enum class UnlockGate { NONE, PIN, TIMER, CHARGER }

/**
 * Strict Mode is app-global (not per-profile). [level]: 1 = lock config, 2 = + block uninstall,
 * 3 = + block device Settings. While [enabled], protected actions require passing the [gate].
 */
data class StrictModeConfig(
    val enabled: Boolean = false,
    val level: Int = 1,
    val gate: UnlockGate = UnlockGate.NONE,
    val timerMinutes: Int = 5,
    val hasPin: Boolean = false,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Persists Strict Mode config in DataStore. The PIN is stored only as a salted SHA-256 hash (never
 * in plaintext); [verifyPin] re-hashes the candidate. (EncryptedSharedPreferences would add a
 * dependency for marginal gain here since we never store the PIN itself.)
 */
@Singleton
class StrictModeStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("strict_enabled")
        val LEVEL = intPreferencesKey("strict_level")
        val GATE = stringPreferencesKey("strict_gate")
        val TIMER = intPreferencesKey("strict_timer_minutes")
        val PIN_HASH = stringPreferencesKey("strict_pin_hash")
        val PIN_SALT = stringPreferencesKey("strict_pin_salt")
    }

    val config: Flow<StrictModeConfig> = context.dataStore.data.map { p ->
        StrictModeConfig(
            enabled = p[Keys.ENABLED] ?: false,
            level = p[Keys.LEVEL] ?: 1,
            gate = runCatching { UnlockGate.valueOf(p[Keys.GATE] ?: "NONE") }.getOrDefault(UnlockGate.NONE),
            timerMinutes = p[Keys.TIMER] ?: 5,
            hasPin = !p[Keys.PIN_HASH].isNullOrEmpty(),
        )
    }

    suspend fun setEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.ENABLED] = enabled }
    suspend fun setLevel(level: Int) = context.dataStore.edit { it[Keys.LEVEL] = level.coerceIn(1, 3) }
    suspend fun setGate(gate: UnlockGate) = context.dataStore.edit { it[Keys.GATE] = gate.name }
    suspend fun setTimerMinutes(minutes: Int) =
        context.dataStore.edit { it[Keys.TIMER] = minutes.coerceIn(1, 1440) }

    suspend fun setPin(pin: String) = context.dataStore.edit { p ->
        val salt = newSalt()
        p[Keys.PIN_SALT] = salt
        p[Keys.PIN_HASH] = hash(pin, salt)
    }

    suspend fun verifyPin(pin: String): Boolean {
        val prefs = context.dataStore.data.first()
        val storedHash = prefs[Keys.PIN_HASH]
        val salt = prefs[Keys.PIN_SALT]
        return storedHash != null && salt != null && hash(pin, salt) == storedHash
    }

    private fun newSalt(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hash(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
