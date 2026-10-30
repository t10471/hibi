package com.example.hibi.feature.since

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hibi.data.HibiRepository
import com.example.hibi.data.TrackedEvent
import com.example.hibi.util.DateUtils
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class SinceViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = HibiRepository(app)
    private var firestoreListener: ListenerRegistration? = null

    val events: StateFlow<List<TrackedEvent>> = repo.events
        .map { list ->
            list.sortedByDescending { e -> e.latest?.let { DateUtils.daysAgo(it) } ?: Long.MAX_VALUE }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        startSyncingFirestore()
    }

    private fun startSyncingFirestore() {
        firestoreListener = Firebase.firestore.collection("tracked_events")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("SinceViewModel", "Firestore listen failed", error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val remoteList = snapshot.documents.mapNotNull { doc ->
                        runCatching {
                            val data = doc.data ?: return@mapNotNull null
                            val name = data["name"] as? String ?: return@mapNotNull null
                            val rawDates = data["dates"] as? List<*> ?: emptyList<Any>()
                            val dates = rawDates.filterIsInstance<String>()
                            val legacyLastDone = data["lastDoneDate"] as? String
                            TrackedEvent(
                                id = doc.id,
                                name = name,
                                dates = dates,
                                lastDoneDate = legacyLastDone,
                            )
                        }.getOrNull()
                    }
                    viewModelScope.launch {
                        repo.updateEvents { remoteList }
                    }
                }
            }
    }

    override fun onCleared() {
        super.onCleared()
        firestoreListener?.remove()
    }

    private fun nowIso(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    fun addEvent(name: String, iso: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val id = "event_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}"
        val event = TrackedEvent(id = id, name = trimmed, dates = listOf(iso))
        viewModelScope.launch {
            repo.updateEvents { it + event }

            val docMap = mapOf(
                "id" to id,
                "name" to trimmed,
                "dates" to listOf(iso),
                "createdAt" to nowIso(),
                "updatedAt" to nowIso(),
            )
            Firebase.firestore.collection("tracked_events").document(id).set(docMap)
        }
    }

    fun deleteEvent(event: TrackedEvent) {
        viewModelScope.launch {
            repo.updateEvents { list -> list.filterNot { it.id == event.id } }
            Firebase.firestore.collection("tracked_events").document(event.id).delete()
        }
    }

    /** Add an occurrence on [iso] to the event's history (no duplicates). */
    fun recordOccurrence(event: TrackedEvent, iso: String) {
        viewModelScope.launch {
            val newDates = (event.history + iso).distinct().sortedDescending()
            repo.updateEvents { list ->
                list.map {
                    if (it.id == event.id) it.copy(dates = newDates, lastDoneDate = null)
                    else it
                }
            }
            Firebase.firestore.collection("tracked_events").document(event.id).update(
                mapOf(
                    "dates" to newDates,
                    "updatedAt" to nowIso(),
                )
            )
        }
    }

    /** Remove a single mistaken occurrence from the history. */
    fun removeOccurrence(event: TrackedEvent, iso: String) {
        viewModelScope.launch {
            val newDates = event.history.filterNot { d -> d == iso }
            repo.updateEvents { list ->
                list.map {
                    if (it.id == event.id) it.copy(dates = newDates, lastDoneDate = null)
                    else it
                }
            }
            Firebase.firestore.collection("tracked_events").document(event.id).update(
                mapOf(
                    "dates" to newDates,
                    "updatedAt" to nowIso(),
                )
            )
        }
    }
}
