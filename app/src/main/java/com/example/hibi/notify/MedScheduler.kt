package com.example.hibi.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.hibi.data.Medication
import java.util.Calendar

/**
 * Schedules a daily, inexact repeating alarm for each medication. Inexact timing
 * avoids the SCHEDULE_EXACT_ALARM permission and is fine for daily reminders.
 */
object MedScheduler {

    const val EXTRA_ID = "med_id"
    const val EXTRA_NAME = "med_name"

    fun rescheduleAll(context: Context, meds: List<Medication>) {
        meds.forEach { schedule(context, it) }
    }

    fun schedule(context: Context, med: Medication) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context, med, PendingIntent.FLAG_UPDATE_CURRENT)

        val triggerAt = nextTriggerMillis(med.hour, med.minute)
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            AlarmManager.INTERVAL_DAY,
            pending,
        )
    }

    fun cancel(context: Context, med: Medication) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context, med, PendingIntent.FLAG_UPDATE_CURRENT)
        alarmManager.cancel(pending)
    }

    private fun pendingIntent(context: Context, med: Medication, extraFlags: Int): PendingIntent {
        val intent = Intent(context, MedReminderReceiver::class.java).apply {
            putExtra(EXTRA_ID, med.id)
            putExtra(EXTRA_NAME, med.name)
        }
        val flags = extraFlags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, med.id.hashCode(), intent, flags)
    }

    private fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }
}
