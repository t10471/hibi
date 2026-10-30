package com.example.hibi.feature.workout

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class HealthWorkoutData(
    val durationMinutes: Int,
    val caloriesBurned: Int,
    val avgHeartRate: Int,
    val maxHeartRate: Int,
    val exerciseTitle: String,
    val isStrengthTraining: Boolean,
)

class HealthConnectManager(private val context: Context) {
    companion object {
        private const val TAG = "HealthConnect"
    }

    private val client by lazy {
        if (isAvailable()) HealthConnectClient.getOrCreate(context) else null
    }

    fun isAvailable(): Boolean {
        val status = HealthConnectClient.getSdkStatus(context)
        Log.d(TAG, "isAvailable: sdkStatus=$status")
        return status == HealthConnectClient.SDK_AVAILABLE
    }

    fun openHealthConnectSettings() {
        val actions = listOf(
            HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS,
            "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS",
            "android.health.connect.action.HEALTH_CONNECT_SETTINGS",
        )
        for (action in actions) {
            try {
                val intent = android.content.Intent(action).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        try {
            val intent = android.content.Intent().apply {
                component = android.content.ComponentName(
                    "com.google.android.apps.healthdata",
                    "com.google.android.apps.healthdata.SettingsActivity1"
                )
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    )

    suspend fun hasAllPermissions(): Boolean {
        val c = client ?: return false
        val granted = c.permissionController.getGrantedPermissions()
        val hasAll = granted.containsAll(permissions)
        Log.d(TAG, "hasAllPermissions: $hasAll (granted: $granted)")
        return hasAll
    }

    suspend fun readTodayWorkout(): HealthWorkoutData? {
        val c = client ?: run {
            Log.w(TAG, "client is null")
            return null
        }
        if (!hasAllPermissions()) {
            Log.w(TAG, "missing permissions")
            return null
        }

        val now = Instant.now()
        val startOfToday = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        // 直近3日間のセッションを検索（当日記録がない場合、前日夜の記録も救済）
        val threeDaysAgo = now.minus(3, ChronoUnit.DAYS)

        Log.d(TAG, "Querying ExerciseSessionRecord from $threeDaysAgo to $now")
        val sessionsResponse = try {
            c.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(threeDaysAgo, now.plus(1, ChronoUnit.HOURS)),
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read ExerciseSessionRecord", e)
            return null
        }

        val allRecords = sessionsResponse.records
        Log.d(TAG, "Found ${allRecords.size} sessions in the last 3 days")
        allRecords.forEachIndexed { i, s ->
            Log.d(TAG, "Session[$i]: title='${s.title}', type=${s.exerciseType}, start=${s.startTime}, end=${s.endTime}")
        }

        // 当日のセッションを優先、なければ直近3日の中で最新のセッション
        val todayRecords = allRecords.filter { it.startTime >= startOfToday }
        val candidateRecords = if (todayRecords.isNotEmpty()) todayRecords else allRecords

        if (candidateRecords.isEmpty()) {
            Log.w(TAG, "No exercise session found in Health Connect")
            return null
        }

        // 筋トレ（STRENGTH_TRAINING / WEIGHTLIFTING）セッションを最優先
        val chosenSession = candidateRecords.find {
            it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING ||
            it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING
        } ?: candidateRecords.maxByOrNull { it.startTime } ?: return null

        val startTime = chosenSession.startTime
        val endTime = chosenSession.endTime
        val durationMins = ChronoUnit.MINUTES.between(startTime, endTime).toInt().coerceAtLeast(1)

        val isStrength = chosenSession.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING ||
                         chosenSession.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING

        val timeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.systemDefault())
        val formattedStart = timeFormatter.format(startTime)

        val baseTitle = chosenSession.title?.takeIf { it.isNotBlank() }
            ?: if (isStrength) "筋力トレーニング" else "ワークアウト"
        val title = "$baseTitle ($formattedStart)"

        Log.d(TAG, "Selected session: $title, duration=$durationMins min, isStrength=$isStrength")

        // 2. セッション期間の消費カロリー取得（アクティブおよび合計から大きい方を採用）
        var calories = 0
        try {
            val calResponse = c.readRecords(
                ReadRecordsRequest(
                    recordType = ActiveCaloriesBurnedRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                )
            )
            calResponse.records.forEach {
                Log.d(TAG, "ActiveCalRecord: energy=${it.energy.inKilocalories} kcal (raw=${it.energy}), ${it.startTime} to ${it.endTime}")
            }
            val activeKcal = calResponse.records.sumOf { it.energy.inKilocalories }.toInt()
            Log.d(TAG, "ActiveCaloriesBurnedRecord sum=$activeKcal kcal")

            val totalCalResponse = c.readRecords(
                ReadRecordsRequest(
                    recordType = TotalCaloriesBurnedRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                )
            )
            totalCalResponse.records.forEach {
                Log.d(TAG, "TotalCalRecord: energy=${it.energy.inKilocalories} kcal (raw=${it.energy}), ${it.startTime} to ${it.endTime}")
            }
            val totalKcal = totalCalResponse.records.sumOf { it.energy.inKilocalories }.toInt()
            Log.d(TAG, "TotalCaloriesBurnedRecord sum=$totalKcal kcal")

            calories = maxOf(activeKcal, totalKcal)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading calories", e)
        }

        // 3. セッション期間の心拍数（平均・最大）取得
        var avgHr = 125
        var maxHr = 155
        try {
            val hrResponse = c.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                )
            )
            val allSamples = hrResponse.records.flatMap { it.samples }
            Log.d(TAG, "HeartRateRecord records: ${hrResponse.records.size}, total samples: ${allSamples.size}")
            if (allSamples.isNotEmpty()) {
                avgHr = allSamples.map { it.beatsPerMinute }.average().toInt()
                maxHr = allSamples.maxOf { it.beatsPerMinute }.toInt()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading heart rate", e)
        }

        Log.d(TAG, "Result: duration=$durationMins, calories=$calories, avgHr=$avgHr, maxHr=$maxHr")
        return HealthWorkoutData(
            durationMinutes = durationMins,
            caloriesBurned = calories,
            avgHeartRate = avgHr,
            maxHeartRate = maxHr,
            exerciseTitle = title,
            isStrengthTraining = isStrength,
        )
    }
}
