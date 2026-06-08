package com.customblocker.service.conditions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.customblocker.domain.engine.BlockingEngine
import com.customblocker.domain.engine.ConditionTypes
import com.customblocker.util.L
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules a wake-up at the next TIME-window boundary (PLAN.md §8/§12) so a block starts/ends
 * promptly even if the device was idle, rather than only on the next foreground event or 3s ticker.
 * Uses setAndAllowWhileIdle (no exact-alarm permission needed); the ticker remains the fine-grained
 * backup while interactive. On fire, [ACTION_REEVAL] is broadcast — the accessibility service
 * re-evaluates the foreground and reschedules the next boundary.
 */
@Singleton
class TimeBoundaryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: BlockingEngine,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun reschedule() {
        val next = nextBoundaryMillis() ?: run {
            alarmManager.cancel(pendingIntent())
            return
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pendingIntent())
        L.d(L.COND, "time boundary alarm set for +${(next - System.currentTimeMillis()) / 1000}s")
    }

    /** Wall-clock millis of the next window edge across enabled TIME profiles, or null if none. */
    private fun nextBoundaryMillis(): Long? {
        val boundaries = sortedSetOf<Int>()
        engine.profiles.forEach { rule ->
            if (rule.enabled && rule.conditionsMask and ConditionTypes.TIME != 0) {
                rule.timeWindows.forEach { w ->
                    boundaries.add(w.fromMinuteOfDay % 1440)
                    boundaries.add(w.toMinuteOfDay % 1440)
                }
            }
        }
        if (boundaries.isEmpty()) return null

        val now = Calendar.getInstance()
        val nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        // first boundary strictly after now today, else the earliest boundary tomorrow
        val nextToday = boundaries.firstOrNull { it > nowMinute }
        val targetMinute = nextToday ?: boundaries.first()
        val cal = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, targetMinute / 60)
            set(Calendar.MINUTE, targetMinute % 60)
            if (nextToday == null) add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(ACTION_REEVAL).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REEVAL = "com.customblocker.action.REEVAL"
    }
}
