package com.example.hibi.data

import kotlinx.serialization.Serializable

/** A medicine the user takes daily; taken days are recorded so the past week is reviewable. */
@Serializable
data class Medication(
    val id: String,
    val name: String,
    val hour: Int,
    val minute: Int,
    /** ISO dates (yyyy-MM-dd) on which it was marked as taken. */
    val takenDates: List<String> = emptyList(),
) {
    val minutesOfDay: Int get() = hour * 60 + minute
    fun takenOn(date: String): Boolean = date in takenDates
}

/** Something the user does occasionally; keeps a history of when it was done. */
@Serializable
data class TrackedEvent(
    val id: String,
    val name: String,
    /** History of occurrence dates (ISO yyyy-MM-dd). */
    val dates: List<String> = emptyList(),
    /** Legacy single-date field; merged into [history] for backward compatibility. */
    val lastDoneDate: String? = null,
) {
    /** All occurrences, newest first, deduplicated (merges the legacy field). */
    val history: List<String>
        get() = (dates + listOfNotNull(lastDoneDate)).distinct().sortedDescending()

    /** Most recent occurrence date, or null if there is no history. */
    val latest: String? get() = history.firstOrNull()
}

/** Persisted defaults for the HIIT timer. */
@Serializable
data class TimerSettings(
    val workSec: Int = 20,
    val restSec: Int = 10,
    val sets: Int = 8,
)
