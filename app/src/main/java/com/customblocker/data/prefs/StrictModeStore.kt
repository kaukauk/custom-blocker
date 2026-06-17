package com.customblocker.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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

/**
 * How the user must authenticate to lower/disable Strict Mode (PLAN.md §11).
 *  - [COUNTDOWN]: each unlock, wait out a countdown (kept on-screen) — friction, but re-lockable.
 *  - [TIMER]: a fixed-deadline commitment set at activation; can't unlock early, auto-lifts at the end.
 */
enum class UnlockGate { NONE, PIN, COUNTDOWN, TIMER, CHARGER }

/**
 * How the Level-3 guard treats the device Settings app while Strict Mode is locked.
 *  - [SAFE_ONLY]: allow-list. Opening Settings shows our curated launcher; only the few safe
 *    shortcuts (Wi-Fi etc.) are reachable, no free roaming.
 *  - [BLOCK_RISKY]: block-list. Settings opens normally and is freely usable; only screens that
 *    could be used to disable the blocker are ejected.
 * Either way the dangerous-surface list is what protects app integrity.
 */
enum class SettingsGuardMode { SAFE_ONLY, BLOCK_RISKY }

/**
 * Strict Mode is app-global (not per-profile). [level]: 1 = lock config, 2 = + block uninstall,
 * 3 = + block device Settings. While [enabled], protected actions require passing the [gate].
 *
 * For the [UnlockGate.TIMER] gate, [timerMinutes] is a *commitment duration*: enabling Strict Mode
 * stamps [lockedUntilMillis] = now + duration, and it stays locked (no early unlock) until that
 * wall-clock instant — the countdown keeps running across app close / process death / reboot.
 */
data class StrictModeConfig(
    val enabled: Boolean = false,
    val level: Int = 1,
    val gate: UnlockGate = UnlockGate.PIN,
    val timerMinutes: Int = 5,
    val hasPin: Boolean = false,
    /** Wall-clock instant the timed lock expires (0 = no timed lock). */
    val lockedUntilMillis: Long = 0L,
    /** Earned-unlock escape hatch (TIMER gate only): sit in a window to bank temporary unlock time. */
    val earnedUnlockEnabled: Boolean = false,
    /** Mandatory wait (minutes) on the earn window before any bank accrues. */
    val earnWaitMinutes: Int = 5,
    /** Max unlock time (minutes) the bank can reach after the wait. */
    val earnMaxMinutes: Int = 5,
    /** Wall-clock instant a granted earned-unlock expires (0 = blocking active). */
    val unlockUntilMillis: Long = 0L,
    /** PIN gate escape hatch: how long (hours) to wait to unlock if the PIN is forgotten. */
    val pinEmergencyHours: Int = 24,
    /** Wall-clock instant a started "forgot PIN" emergency wait completes (0 = not started). */
    val pinEmergencyUntilMillis: Long = 0L,
    /** L3 only: whether Settings is allow-listed (safe screens only) or block-listed (risky only). */
    val settingsGuardMode: SettingsGuardMode = SettingsGuardMode.SAFE_ONLY,
) {
    /** True while a TIMER-gate commitment is still running at wall-clock [now]. */
    fun timedLockActive(now: Long): Boolean =
        enabled && gate == UnlockGate.TIMER && lockedUntilMillis > now

    /** True while a granted earned-unlock is still bypassing blocking at wall-clock [now]. */
    fun earnedUnlockActive(now: Long): Boolean = unlockUntilMillis > now

    /** A "forgot PIN" wait has been started but hasn't elapsed yet. */
    fun pinEmergencyPending(now: Long): Boolean = pinEmergencyUntilMillis > now
    /** A "forgot PIN" wait has been started and has now elapsed → emergency unlock is available. */
    fun pinEmergencyReady(now: Long): Boolean = pinEmergencyUntilMillis in 1..now
}

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
        val LOCKED_UNTIL = longPreferencesKey("strict_locked_until")
        val EARN_ENABLED = booleanPreferencesKey("strict_earn_enabled")
        val EARN_WAIT = intPreferencesKey("strict_earn_wait_min")
        val EARN_MAX = intPreferencesKey("strict_earn_max_min")
        val UNLOCK_UNTIL = longPreferencesKey("strict_unlock_until")
        val PIN_EMERGENCY_HOURS = intPreferencesKey("strict_pin_emergency_hours")
        val PIN_EMERGENCY_UNTIL = longPreferencesKey("strict_pin_emergency_until")
        val SETTINGS_GUARD_MODE = stringPreferencesKey("strict_settings_guard_mode")
    }

    val config: Flow<StrictModeConfig> = context.dataStore.data.map { p ->
        StrictModeConfig(
            enabled = p[Keys.ENABLED] ?: false,
            level = p[Keys.LEVEL] ?: 1,
            gate = runCatching { UnlockGate.valueOf(p[Keys.GATE] ?: "PIN") }.getOrDefault(UnlockGate.PIN),
            timerMinutes = p[Keys.TIMER] ?: 5,
            hasPin = !p[Keys.PIN_HASH].isNullOrEmpty(),
            lockedUntilMillis = p[Keys.LOCKED_UNTIL] ?: 0L,
            earnedUnlockEnabled = p[Keys.EARN_ENABLED] ?: false,
            earnWaitMinutes = (p[Keys.EARN_WAIT] ?: 5).coerceIn(EARN_WAIT_MIN, EARN_WAIT_MAX),
            earnMaxMinutes = (p[Keys.EARN_MAX] ?: 5).coerceIn(EARN_MAX_MIN, EARN_MAX_CAP),
            unlockUntilMillis = p[Keys.UNLOCK_UNTIL] ?: 0L,
            pinEmergencyHours = (p[Keys.PIN_EMERGENCY_HOURS] ?: 24).coerceIn(PIN_EMERGENCY_FLOOR, PIN_EMERGENCY_MAX),
            pinEmergencyUntilMillis = p[Keys.PIN_EMERGENCY_UNTIL] ?: 0L,
            settingsGuardMode = runCatching {
                SettingsGuardMode.valueOf(p[Keys.SETTINGS_GUARD_MODE] ?: SettingsGuardMode.SAFE_ONLY.name)
            }.getOrDefault(SettingsGuardMode.SAFE_ONLY),
        )
    }

    /** Cap a duration to [1 min, 90 days] — enough for any realistic commitment. */
    private fun clampMinutes(minutes: Int) = minutes.coerceIn(1, 90 * 24 * 60)

    suspend fun setEnabled(enabled: Boolean) = context.dataStore.edit {
        it[Keys.ENABLED] = enabled
        if (!enabled) {
            it.remove(Keys.LOCKED_UNTIL)            // turning off ends any timed commitment
            it.remove(Keys.PIN_EMERGENCY_UNTIL)     // ...and any pending forgot-PIN wait
        }
    }
    suspend fun setLevel(level: Int) = context.dataStore.edit { it[Keys.LEVEL] = level.coerceIn(1, 3) }
    suspend fun setGate(gate: UnlockGate) = context.dataStore.edit { it[Keys.GATE] = gate.name }
    suspend fun setSettingsGuardMode(mode: SettingsGuardMode) =
        context.dataStore.edit { it[Keys.SETTINGS_GUARD_MODE] = mode.name }
    suspend fun setTimerMinutes(minutes: Int) =
        context.dataStore.edit { it[Keys.TIMER] = clampMinutes(minutes) }

    /**
     * Enable Strict Mode as a TIMER commitment: locked until now + the stored/here-given duration.
     * The deadline is wall-clock so it survives reboot (a determined user could still move the system
     * clock — block Settings > Date & time under L3 to close that hole).
     */
    suspend fun startTimedLock(durationMinutes: Int, nowMillis: Long) = context.dataStore.edit {
        val mins = clampMinutes(durationMinutes)
        it[Keys.ENABLED] = true
        it[Keys.GATE] = UnlockGate.TIMER.name
        it[Keys.TIMER] = mins
        it[Keys.LOCKED_UNTIL] = nowMillis + mins.toLong() * 60_000L
    }

    suspend fun setEarnedUnlockEnabled(enabled: Boolean) =
        context.dataStore.edit { it[Keys.EARN_ENABLED] = enabled }
    suspend fun setEarnWaitMinutes(minutes: Int) =
        context.dataStore.edit { it[Keys.EARN_WAIT] = minutes.coerceIn(EARN_WAIT_MIN, EARN_WAIT_MAX) }
    suspend fun setEarnMaxMinutes(minutes: Int) =
        context.dataStore.edit { it[Keys.EARN_MAX] = minutes.coerceIn(EARN_MAX_MIN, EARN_MAX_CAP) }

    companion object {
        /**
         * Earn-unlock wait is configurable 1–10 min; the max banked unlock is fixed-cap 5, down to 1.
         * Debug builds allow a 0-min wait so testing doesn't require sitting through the countdown.
         */
        val EARN_WAIT_MIN = if (com.customblocker.BuildConfig.DEBUG) 0 else 1
        const val EARN_WAIT_MAX = 10
        const val EARN_MAX_MIN = 1
        const val EARN_MAX_CAP = 5

        /**
         * Forgot-PIN emergency wait, in hours: 1–72 (up to 3 days). Debug builds allow 0 (instant)
         * so the escape hatch can be tested without waiting.
         */
        val PIN_EMERGENCY_FLOOR = if (com.customblocker.BuildConfig.DEBUG) 0 else 1
        const val PIN_EMERGENCY_MAX = 72
    }

    /** Grant a one-off earned unlock: bypass all blocking until now + [durationSeconds]. */
    suspend fun startEarnedUnlock(durationSeconds: Int, nowMillis: Long) =
        context.dataStore.edit { it[Keys.UNLOCK_UNTIL] = nowMillis + durationSeconds.coerceAtLeast(0).toLong() * 1000L }

    suspend fun setPinEmergencyHours(hours: Int) =
        context.dataStore.edit { it[Keys.PIN_EMERGENCY_HOURS] = hours.coerceIn(PIN_EMERGENCY_FLOOR, PIN_EMERGENCY_MAX) }

    /**
     * Start the "forgot PIN" escape hatch: stamp a wall-clock deadline [pinEmergencyHours] from now.
     * Once it elapses the user can unlock without the PIN — strict (a long wait), but never permanently
     * stuck. The deadline persists across app close / reboot so it can't be reset by relaunching.
     */
    suspend fun startPinEmergency(nowMillis: Long) = context.dataStore.edit {
        val hours = (it[Keys.PIN_EMERGENCY_HOURS] ?: 24).coerceIn(PIN_EMERGENCY_FLOOR, PIN_EMERGENCY_MAX)
        it[Keys.PIN_EMERGENCY_UNTIL] = nowMillis + hours.toLong() * 3_600_000L
    }

    suspend fun clearPinEmergency() = context.dataStore.edit { it.remove(Keys.PIN_EMERGENCY_UNTIL) }

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
