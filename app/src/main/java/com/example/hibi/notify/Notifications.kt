package com.example.hibi.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.hibi.MainActivity
import com.example.hibi.R

const val MED_CHANNEL_ID = "med_reminders"

/** Intent extra carrying which tab MainActivity should open. */
const val EXTRA_NAV_TAB = "nav_tab"

/** Value for [EXTRA_NAV_TAB] requesting the medication ("くすり") tab. */
const val NAV_TAB_MEDS = "meds"

/** Creates the medication reminder channel. Safe to call repeatedly. */
fun ensureMedChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            MED_CHANNEL_ID,
            "服薬リマインダー",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "薬を飲む時間をお知らせします" }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }
}

/**
 * Posts a reminder. Returns silently if the POST_NOTIFICATIONS permission is
 * missing (NotificationManagerCompat handles the SecurityException-free check).
 */
fun postMedNotification(context: Context, medId: String, medName: String) {
    ensureMedChannel(context)

    val tapIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(EXTRA_NAV_TAB, NAV_TAB_MEDS)
    }
    val contentIntent = PendingIntent.getActivity(
        context,
        medId.hashCode(),
        tapIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val notification = NotificationCompat.Builder(context, MED_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_pill)
        .setContentTitle("お薬の時間です")
        .setContentText("「$medName」を飲む時間です")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .build()

    val manager = NotificationManagerCompat.from(context)
    if (manager.areNotificationsEnabled()) {
        runCatching { manager.notify(medId.hashCode(), notification) }
    }
}
