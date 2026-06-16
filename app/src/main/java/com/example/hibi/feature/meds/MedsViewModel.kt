package com.example.hibi.feature.meds

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hibi.data.HibiRepository
import com.example.hibi.data.Medication
import com.example.hibi.notify.MedScheduler
import com.example.hibi.util.DateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class MedsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = HibiRepository(app)
    private val context = app.applicationContext

    val meds: StateFlow<List<Medication>> = repo.medications
        .map { list -> list.sortedBy { it.minutesOfDay } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The day the list reflects; can go back up to a week. Defaults to today. */
    private var lastKnownToday = DateUtils.todayIso()
    private val _selectedDate = MutableStateFlow(lastKnownToday)
    val selectedDate: StateFlow<String> = _selectedDate.asStateFlow()

    fun selectDate(iso: String) {
        _selectedDate.value = iso
    }

    /**
     * Re-evaluate "today" after the date may have rolled over (e.g. the app was left
     * open past midnight). Only snaps the selection forward if it was still on the old
     * "today" — an intentionally chosen past day is preserved.
     */
    fun refreshToday() {
        val today = DateUtils.todayIso()
        if (today != lastKnownToday) {
            if (_selectedDate.value == lastKnownToday) _selectedDate.value = today
            lastKnownToday = today
        }
    }

    fun addMed(name: String, hour: Int, minute: Int) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val med = Medication(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            hour = hour,
            minute = minute,
        )
        viewModelScope.launch {
            repo.updateMedications { it + med }
            MedScheduler.schedule(context, med)
        }
    }

    fun deleteMed(med: Medication) {
        viewModelScope.launch {
            MedScheduler.cancel(context, med)
            repo.updateMedications { list -> list.filterNot { it.id == med.id } }
        }
    }

    /** Toggle whether [med] was taken on [date]. Old entries are pruned after 30 days. */
    fun toggleTaken(med: Medication, date: String) {
        viewModelScope.launch {
            val cutoff = DateUtils.isoDaysAgo(30)
            repo.updateMedications { list ->
                list.map {
                    if (it.id == med.id) {
                        val dates = it.takenDates.toMutableSet()
                        if (!dates.add(date)) dates.remove(date)
                        it.copy(takenDates = dates.filter { d -> d >= cutoff }.sorted())
                    } else it
                }
            }
        }
    }
}
