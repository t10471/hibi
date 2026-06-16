package com.example.hibi.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.hibi.data.HibiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Alarms are cleared on reboot, so re-register every medication reminder. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val meds = HibiRepository(appContext).medications.first()
                MedScheduler.rescheduleAll(appContext, meds)
            } finally {
                pending.finish()
            }
        }
    }
}
