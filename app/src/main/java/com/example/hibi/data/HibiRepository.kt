package com.example.hibi.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "hibi")

/**
 * Single source of truth, backed by Preferences DataStore. Each feature's data is
 * stored as a JSON string under its own key.
 */
class HibiRepository(context: Context) {

    private val dataStore = context.applicationContext.dataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val medications: Flow<List<Medication>> =
        dataStore.data.map { prefs -> decodeList(prefs[MEDS_KEY]) }

    val events: Flow<List<TrackedEvent>> =
        dataStore.data.map { prefs -> decodeEvents(prefs[EVENTS_KEY]) }

    val timerSettings: Flow<TimerSettings> =
        dataStore.data.map { prefs ->
            prefs[TIMER_KEY]?.let { runCatching { json.decodeFromString<TimerSettings>(it) }.getOrNull() }
                ?: TimerSettings()
        }

    /**
     * Atomic read-modify-write: decode → [transform] → encode runs inside a single
     * DataStore transaction, so concurrent edits can't lose updates (last-writer-wins).
     */
    suspend fun updateMedications(transform: (List<Medication>) -> List<Medication>) {
        dataStore.edit { prefs ->
            val current = decodeOrPreserve(prefs, MEDS_KEY) { json.decodeFromString<List<Medication>>(it) }
            prefs[MEDS_KEY] = json.encodeToString(transform(current))
        }
    }

    suspend fun updateEvents(transform: (List<TrackedEvent>) -> List<TrackedEvent>) {
        dataStore.edit { prefs ->
            val current = decodeOrPreserve(prefs, EVENTS_KEY) { json.decodeFromString<List<TrackedEvent>>(it) }
            prefs[EVENTS_KEY] = json.encodeToString(transform(current))
        }
    }

    suspend fun setTimerSettings(settings: TimerSettings) {
        dataStore.edit { it[TIMER_KEY] = json.encodeToString(settings) }
    }

    /**
     * Decodes the value at [key]. If it is present but unparseable, the raw JSON is
     * copied to a "<key>_corrupt" entry before returning empty — so the following
     * write can't silently overwrite (and lose) recoverable data.
     */
    private fun <T> decodeOrPreserve(
        prefs: MutablePreferences,
        key: Preferences.Key<String>,
        decode: (String) -> List<T>,
    ): List<T> {
        val raw = prefs[key] ?: return emptyList()
        return runCatching { decode(raw) }.getOrElse {
            prefs[stringPreferencesKey("${key.name}_corrupt")] = raw
            emptyList()
        }
    }

    private fun decodeList(raw: String?): List<Medication> =
        raw?.let { runCatching { json.decodeFromString<List<Medication>>(it) }.getOrNull() } ?: emptyList()

    private fun decodeEvents(raw: String?): List<TrackedEvent> =
        raw?.let { runCatching { json.decodeFromString<List<TrackedEvent>>(it) }.getOrNull() } ?: emptyList()

    private companion object {
        val MEDS_KEY = stringPreferencesKey("medications")
        val EVENTS_KEY = stringPreferencesKey("events")
        val TIMER_KEY = stringPreferencesKey("timer_settings")
    }
}
