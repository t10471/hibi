package com.example.hibi.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Invoked by AlarmManager at a medication's reminder time. */
class MedReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(MedScheduler.EXTRA_ID) ?: return
        val name = intent.getStringExtra(MedScheduler.EXTRA_NAME) ?: return
        postMedNotification(context, id, name)
    }
}
