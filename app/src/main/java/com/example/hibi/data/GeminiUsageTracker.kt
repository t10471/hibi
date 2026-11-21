package com.example.hibi.data

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

data class GeminiMonthlyUsage(
    val month: String = GeminiUsageTracker.currentMonthKeyJst(),
    val requestCount: Long = 0L,
    val promptTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val thinkingTokens: Long = 0L,
    val cachedTokens: Long = 0L,
    val estimatedCostUsd: Double = 0.0,
    val workoutRequests: Long = 0L,
    val workoutCostUsd: Double = 0.0,
    val stockRequests: Long = 0L,
    val stockCostUsd: Double = 0.0,
    val appRequests: Long = 0L,
    val webRequests: Long = 0L,
    val lastModel: String = "",
)

/**
 * Tracks Gemini API token usage and estimated USD/JPY cost per month (YYYY-MM JST)
 * in Firestore `gemini_usage/{YYYY-MM}`, shared between Android and Web and automatically
 * resetting on the 1st of each month.
 */
object GeminiUsageTracker {

    private const val TAG = "GeminiUsageTracker"
    private const val COLLECTION = "gemini_usage"
    const val USD_TO_JPY = 150.0
    private val jstZone = ZoneId.of("Asia/Tokyo")

    private val currentMonthState = MutableStateFlow(currentMonthKeyJst())

    fun currentMonthKeyJst(): String = YearMonth.now(jstZone).toString()

    /** Re-evaluates current JST month (call on screen resume so month rollovers take effect immediately). */
    fun refreshMonth() {
        val nowMonth = currentMonthKeyJst()
        if (currentMonthState.value != nowMonth) {
            currentMonthState.value = nowMonth
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCurrentMonth(): Flow<GeminiMonthlyUsage> {
        refreshMonth()
        return currentMonthState.flatMapLatest { month ->
            observeMonth(month)
        }
    }

    private fun observeMonth(month: String): Flow<GeminiMonthlyUsage> = callbackFlow {
        val listener = Firebase.firestore.collection(COLLECTION).document(month)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Failed to listen gemini_usage/$month", error)
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) {
                    trySend(GeminiMonthlyUsage(month = month))
                } else {
                    val data = snapshot.data.orEmpty()
                    trySend(
                        GeminiMonthlyUsage(
                            month = (data["month"] as? String) ?: month,
                            requestCount = (data["requestCount"] as? Number)?.toLong() ?: 0L,
                            promptTokens = (data["promptTokens"] as? Number)?.toLong() ?: 0L,
                            outputTokens = (data["outputTokens"] as? Number)?.toLong() ?: 0L,
                            thinkingTokens = (data["thinkingTokens"] as? Number)?.toLong() ?: 0L,
                            cachedTokens = (data["cachedTokens"] as? Number)?.toLong() ?: 0L,
                            estimatedCostUsd = (data["estimatedCostUsd"] as? Number)?.toDouble() ?: 0.0,
                            workoutRequests = (data["workoutRequests"] as? Number)?.toLong() ?: 0L,
                            workoutCostUsd = (data["workoutCostUsd"] as? Number)?.toDouble() ?: 0.0,
                            stockRequests = (data["stockRequests"] as? Number)?.toLong() ?: 0L,
                            stockCostUsd = (data["stockCostUsd"] as? Number)?.toDouble() ?: 0.0,
                            appRequests = (data["appRequests"] as? Number)?.toLong() ?: 0L,
                            webRequests = (data["webRequests"] as? Number)?.toLong() ?: 0L,
                            lastModel = (data["lastModel"] as? String).orEmpty(),
                        )
                    )
                }
            }
        awaitClose { listener.remove() }
    }

    fun calculateCostUsd(
        model: String,
        promptTokens: Long,
        candidatesTokens: Long,
        thinkingTokens: Long,
        cachedTokens: Long = 0L,
    ): Double {
        val m = model.lowercase(Locale.ROOT)
        val (inputRate, cachedRate, outputRate) = when {
            m.contains("hydrogen") -> Triple(1.50, 0.075, 7.50)
            m.contains("pro") -> Triple(1.25, 0.3125, 10.0)
            m.contains("2.0-flash") || m.contains("flash-lite") -> Triple(0.10, 0.025, 0.40)
            else -> Triple(0.30, 0.03, 2.50) // gemini-2.5-flash / gemini-flash-latest
        }
        val nonCachedInput = max(0L, promptTokens - cachedTokens)
        val totalOutput = max(0L, candidatesTokens) + max(0L, thinkingTokens)
        return (nonCachedInput * inputRate + max(0L, cachedTokens) * cachedRate + totalOutput * outputRate) / 1_000_000.0
    }

    fun recordFromJson(
        responseJson: JsonObject,
        category: String,
        fallbackModel: String,
    ) {
        val usage = responseJson["usageMetadata"]?.jsonObject ?: return
        val modelVersion = responseJson["modelVersion"]?.jsonPrimitive?.content ?: fallbackModel
        val promptTokens = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0L
        val candidatesTokens = usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0L
        val thinkingTokens = usage["thoughtsTokenCount"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0L
        val cachedTokens = usage["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0L
        val outputTokens = candidatesTokens + thinkingTokens
        val costUsd = calculateCostUsd(modelVersion, promptTokens, candidatesTokens, thinkingTokens, cachedTokens)

        val month = currentMonthKeyJst()
        refreshMonth()

        val updates = hashMapOf<String, Any>(
            "month" to month,
            "requestCount" to FieldValue.increment(1L),
            "promptTokens" to FieldValue.increment(promptTokens),
            "outputTokens" to FieldValue.increment(outputTokens),
            "thinkingTokens" to FieldValue.increment(thinkingTokens),
            "cachedTokens" to FieldValue.increment(cachedTokens),
            "estimatedCostUsd" to FieldValue.increment(costUsd),
            "workoutRequests" to FieldValue.increment(if (category == "workout") 1L else 0L),
            "workoutCostUsd" to FieldValue.increment(if (category == "workout") costUsd else 0.0),
            "stockRequests" to FieldValue.increment(if (category == "stock") 1L else 0L),
            "stockCostUsd" to FieldValue.increment(if (category == "stock") costUsd else 0.0),
            "appRequests" to FieldValue.increment(1L),
            "lastModel" to modelVersion,
            "updatedAt" to Instant.now().toString(),
        )

        Firebase.firestore.collection(COLLECTION).document(month)
            .set(updates, SetOptions.merge())
            .addOnFailureListener { e -> Log.w(TAG, "Failed to record Gemini usage", e) }
    }

    fun formatCostJpy(costUsd: Double): String {
        val jpy = costUsd * USD_TO_JPY
        return when {
            jpy == 0.0 -> "¥0"
            jpy < 1.0 -> String.format(Locale.US, "¥%.2f", jpy)
            jpy < 10.0 -> String.format(Locale.US, "¥%.1f", jpy)
            else -> "¥%,d".format(Locale.US, jpy.roundToLong())
        }
    }

    fun formatCostUsd(costUsd: Double): String {
        return when {
            costUsd == 0.0 -> "$0.00"
            costUsd < 0.01 -> String.format(Locale.US, "$%.4f", costUsd)
            else -> String.format(Locale.US, "$%.3f", costUsd)
        }
    }
}
