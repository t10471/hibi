package com.example.hibi.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.hibi.data.BuyStep
import com.example.hibi.data.HibiRepository
import com.example.hibi.data.StockScenario
import com.example.hibi.data.StockScenarioMapper
import com.example.hibi.util.DateUtils
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import kotlin.coroutines.resume

/**
 * Schedules a daily 08:30 AM local check for stock buy-timing reminders and posts
 * notifications for any unnotified, uncompleted step whose [BuyStep.notifyDate] <= today.
 *
 * Uses [AlarmManager.setAndAllowWhileIdle] (rescheduled daily on trigger and on boot)
 * so reminders arrive reliably without requiring Cloud Functions billing or exact-alarm permission.
 */
object StockScheduler {

    private const val TAG = "StockScheduler"
    private const val REQUEST_CODE = 100830
    private const val CHECK_HOUR = 8
    private const val CHECK_MINUTE = 30

    private val checkMutex = Mutex()

    fun ensureDailySchedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        val triggerAt = nextTriggerMillis(CHECK_HOUR, CHECK_MINUTE)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        Log.d(TAG, "Scheduled daily stock reminder check at $triggerAt")
    }

    /**
     * Checks due stock steps (optionally merging [remoteOverride] from Firestore) and posts
     * notifications for any step not yet notified or marked done.
     *
     * @return the number of newly sent notifications.
     */
    suspend fun checkAndNotifyDueSteps(
        context: Context,
        remoteOverride: List<StockScenario>? = null,
    ): Int = checkMutex.withLock {
        val appContext = context.applicationContext
        val repo = HibiRepository(appContext)
        val remoteList = remoteOverride ?: fetchScenariosFromFirestore()
        val todayStr = DateUtils.todayIso()
        val toNotify = mutableListOf<Pair<StockScenario, BuyStep>>()

        repo.updateScenarios { current ->
            toNotify.clear()
            val base = if (remoteList != null) {
                StockScenarioMapper.mergeWithLocal(remoteList, current)
            } else {
                current
            }
            base.map { scenario ->
                var changed = false
                val updatedSteps = scenario.steps.map { step ->
                    if (!step.isDone &&
                        !step.isNotified &&
                        step.notifyDate.isNotBlank() &&
                        step.notifyDate <= todayStr
                    ) {
                        toNotify.add(scenario to step)
                        changed = true
                        step.copy(isNotified = true)
                    } else {
                        step
                    }
                }
                if (changed) scenario.copy(steps = updatedSteps) else scenario
            }
        }

        toNotify.forEach { (scenario, step) ->
            val title = "【${scenario.name}】第${step.step}回 買い場のお知らせ (${step.ratio})"
            val body = "${step.period} / 狙い: ${step.aim}"
            val notificationId = "${scenario.id}_step_${step.step}".hashCode()
            postStockNotification(
                context = appContext,
                notificationId = notificationId,
                title = title,
                body = body,
            )
            Log.d(TAG, "Posted stock notification: $title")
        }

        toNotify.size
    }

    private suspend fun fetchScenariosFromFirestore(): List<StockScenario>? {
        return withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { cont ->
                Firebase.firestore.collection("stock_scenarios").get()
                    .addOnSuccessListener { snapshot ->
                        val parsed = snapshot.documents.mapNotNull { StockScenarioMapper.fromDocument(it) }
                        if (cont.isActive) cont.resume(parsed)
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed to fetch stock_scenarios in background", e)
                        if (cont.isActive) cont.resume(null)
                    }
            }
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, StockReminderReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
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
