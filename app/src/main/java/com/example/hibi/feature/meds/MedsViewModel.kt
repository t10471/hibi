package com.example.hibi.feature.meds

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hibi.data.HibiRepository
import com.example.hibi.data.Medication
import com.example.hibi.notify.MedScheduler
import com.example.hibi.util.DateUtils
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class MedsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = HibiRepository(app)
    private val context = app.applicationContext
    private var firestoreListener: ListenerRegistration? = null

    val meds: StateFlow<List<Medication>> = repo.medications
        .map { list -> list.sortedBy { it.minutesOfDay } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The day the list reflects; can go back up to a week. Defaults to today. */
    private var lastKnownToday = DateUtils.todayIso()
    private val _selectedDate = MutableStateFlow(lastKnownToday)
    val selectedDate: StateFlow<String> = _selectedDate.asStateFlow()

    init {
        startSyncingFirestore()
    }

    private fun nowIso(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    private fun startSyncingFirestore() {
        firestoreListener = Firebase.firestore.collection("medications")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("MedsViewModel", "Firestore listen failed", error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val remoteList = snapshot.documents.mapNotNull { doc ->
                        runCatching {
                            val data = doc.data ?: return@mapNotNull null
                            val name = data["name"] as? String ?: return@mapNotNull null
                            val hour = (data["hour"] as? Number)?.toInt() ?: 8
                            val minute = (data["minute"] as? Number)?.toInt() ?: 0
                            val rawTaken = data["takenDates"] as? List<*> ?: emptyList<Any>()
                            val takenDates = rawTaken.filterIsInstance<String>()
                            Medication(
                                id = doc.id,
                                name = name,
                                hour = hour,
                                minute = minute,
                                takenDates = takenDates,
                            )
                        }.getOrNull()
                    }
                    viewModelScope.launch {
                        repo.updateMedications { localList ->
                            // スケジューラの通知更新
                            remoteList.forEach { med ->
                                MedScheduler.schedule(context, med)
                            }
                            localList.filterNot { local -> remoteList.any { it.id == local.id } }.forEach { removed ->
                                MedScheduler.cancel(context, removed)
                            }
                            remoteList
                        }
                    }
                }
            }
    }

    override fun onCleared() {
        super.onCleared()
        firestoreListener?.remove()
    }

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
        val id = "med_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}"
        val med = Medication(
            id = id,
            name = trimmed,
            hour = hour,
            minute = minute,
        )
        viewModelScope.launch {
            repo.updateMedications { it + med }
            MedScheduler.schedule(context, med)

            val docMap = mapOf(
                "id" to id,
                "name" to trimmed,
                "hour" to hour,
                "minute" to minute,
                "takenDates" to emptyList<String>(),
                "createdAt" to nowIso(),
                "updatedAt" to nowIso(),
            )
            Firebase.firestore.collection("medications").document(id).set(docMap)
        }
    }

    fun deleteMed(med: Medication) {
        viewModelScope.launch {
            MedScheduler.cancel(context, med)
            repo.updateMedications { list -> list.filterNot { it.id == med.id } }
            Firebase.firestore.collection("medications").document(med.id).delete()
        }
    }

    /** Toggle whether [med] was taken on [date]. Old entries are pruned after 30 days. */
    fun toggleTaken(med: Medication, date: String) {
        viewModelScope.launch {
            val cutoff = DateUtils.isoDaysAgo(30)
            val dates = med.takenDates.toMutableSet()
            if (!dates.add(date)) dates.remove(date)
            val updatedDates = dates.filter { d -> d >= cutoff }.sorted()

            repo.updateMedications { list ->
                list.map {
                    if (it.id == med.id) {
                        it.copy(takenDates = updatedDates)
                    } else it
                }
            }

            Firebase.firestore.collection("medications").document(med.id).update(
                mapOf(
                    "takenDates" to updatedDates,
                    "updatedAt" to nowIso(),
                )
            )
        }
    }
}
