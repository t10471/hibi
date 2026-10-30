package com.example.hibi.feature.workout

import android.content.Context
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
    private val client by lazy {
        if (isAvailable()) HealthConnectClient.getOrCreate(context) else null
    }

    fun isAvailable(): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
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
        return granted.containsAll(permissions)
    }

    suspend fun readTodayWorkout(): HealthWorkoutData? {
        val c = client ?: return null
        if (!hasAllPermissions()) return null

        val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        val now = Instant.now()

        // 1. 本日の運動セッションを取得
        val sessionsResponse = c.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
            )
        )

        val records = sessionsResponse.records
        if (records.isEmpty()) return null

        // 筋トレ（STRENGTH_TRAINING / WEIGHTLIFTING）セッションを最優先
        val strengthSession = records.find {
            it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING ||
            it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING
        } ?: records.maxByOrNull { it.startTime } ?: return null

        val startTime = strengthSession.startTime
        val endTime = strengthSession.endTime
        val durationMins = ChronoUnit.MINUTES.between(startTime, endTime).toInt().coerceAtLeast(1)

        val isStrength = strengthSession.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING ||
                         strengthSession.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING

        val title = strengthSession.title?.takeIf { it.isNotBlank() }
            ?: if (isStrength) "筋力トレーニング" else "ワークアウト"

        // 2. セッション期間のアクティブ消費カロリー取得
        var calories = 0
        try {
            val calResponse = c.readRecords(
                ReadRecordsRequest(
                    recordType = ActiveCaloriesBurnedRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                )
            )
            calories = calResponse.records.sumOf { it.energy.inKilocalories }.toInt()
            if (calories == 0) {
                val totalCalResponse = c.readRecords(
                    ReadRecordsRequest(
                        recordType = TotalCaloriesBurnedRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                    )
                )
                calories = totalCalResponse.records.sumOf { it.energy.inKilocalories }.toInt()
            }
        } catch (_: Exception) {}

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
            if (allSamples.isNotEmpty()) {
                avgHr = allSamples.map { it.beatsPerMinute }.average().toInt()
                maxHr = allSamples.maxOf { it.beatsPerMinute }.toInt()
            }
        } catch (_: Exception) {}

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
