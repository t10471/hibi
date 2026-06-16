package com.example.hibi.feature.since

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hibi.data.HibiRepository
import com.example.hibi.data.TrackedEvent
import com.example.hibi.util.DateUtils
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class SinceViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = HibiRepository(app)

    val events: StateFlow<List<TrackedEvent>> = repo.events
        .map { list ->
            list.sortedByDescending { e -> e.latest?.let { DateUtils.daysAgo(it) } ?: Long.MAX_VALUE }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addEvent(name: String, iso: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val event = TrackedEvent(id = UUID.randomUUID().toString(), name = trimmed, dates = listOf(iso))
        viewModelScope.launch {
            repo.updateEvents { it + event }
        }
    }

    fun deleteEvent(event: TrackedEvent) {
        viewModelScope.launch {
            repo.updateEvents { list -> list.filterNot { it.id == event.id } }
        }
    }

    /** Add an occurrence on [iso] to the event's history (no duplicates). */
    fun recordOccurrence(event: TrackedEvent, iso: String) {
        viewModelScope.launch {
            repo.updateEvents { list ->
                list.map {
                    if (it.id == event.id) it.copy(dates = (it.history + iso).distinct(), lastDoneDate = null)
                    else it
                }
            }
        }
    }

    /** Remove a single mistaken occurrence from the history. */
    fun removeOccurrence(event: TrackedEvent, iso: String) {
        viewModelScope.launch {
            repo.updateEvents { list ->
                list.map {
                    if (it.id == event.id) it.copy(dates = it.history.filterNot { d -> d == iso }, lastDoneDate = null)
                    else it
                }
            }
        }
    }
}
