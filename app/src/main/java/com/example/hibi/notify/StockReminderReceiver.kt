package com.example.hibi.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Invoked daily at 08:30 by AlarmManager to check due stock buy-timing steps. */
class StockReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        StockScheduler.ensureDailySchedule(appContext)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                StockScheduler.checkAndNotifyDueSteps(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
