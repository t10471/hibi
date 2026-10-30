package com.example.hibi.data

import kotlinx.serialization.Serializable

@Serializable
data class WorkoutExercise(
    val id: String = "",
    val name: String = "",
    val targetMuscle: String = "",
    val targetWeight: Double = 0.0,
    val targetReps: String = "10",
    val targetSets: Int = 3,
    val restSeconds: Int = 90,
    val notes: String = "",
)

@Serializable
data class WorkoutMenu(
    val id: String = "",
    val title: String = "",
    val targetMuscle: String = "",
    val dayOfWeek: String = "",
    val dayOrder: Int = 0,
    val exercises: List<WorkoutExercise> = emptyList(),
    val sourceUrl: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class WorkoutSetResult(
    val setNum: Int = 1,
    val weight: Double = 0.0,
    val reps: Int = 10,
    val rpe: Double = 8.0,
    val isCompleted: Boolean = true,
)

@Serializable
data class ExerciseLogItem(
    val exerciseName: String = "",
    val targetMuscle: String = "",
    val targetWeight: Double = 0.0,
    val targetReps: String = "10",
    val targetSets: Int = 3,
    val sets: List<WorkoutSetResult> = emptyList(),
    val notes: String = "",
)

@Serializable
data class WorkoutLog(
    val id: String = "",
    val menuId: String = "",
    val menuTitle: String = "",
    val date: String = "",
    val exercises: List<ExerciseLogItem> = emptyList(),
    val durationMinutes: Int? = null,
    val caloriesBurned: Int? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val userNotes: String = "",
    val geminiFeedback: String? = null,
    val createdAt: String? = null,
)
