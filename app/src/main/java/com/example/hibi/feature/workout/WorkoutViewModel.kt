package com.example.hibi.feature.workout

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hibi.data.ExerciseLogItem
import com.example.hibi.data.HibiRepository
import com.example.hibi.data.WorkoutExercise
import com.example.hibi.data.WorkoutLog
import com.example.hibi.data.WorkoutMenu
import com.example.hibi.data.WorkoutSetResult
import com.google.firebase.Firebase
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WorkoutViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = HibiRepository(application)
    val geminiApiKey = repo.geminiApiKey.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null
    )

    private val _menus = MutableStateFlow<List<WorkoutMenu>>(emptyList())
    val menus: StateFlow<List<WorkoutMenu>> = _menus.asStateFlow()

    private val _logs = MutableStateFlow<List<WorkoutLog>>(emptyList())
    val logs: StateFlow<List<WorkoutLog>> = _logs.asStateFlow()

    private val _selectedMenu = MutableStateFlow<WorkoutMenu?>(null)
    val selectedMenu: StateFlow<WorkoutMenu?> = _selectedMenu.asStateFlow()

    private val _exerciseLogs = MutableStateFlow<List<ExerciseLogItem>>(emptyList())
    val exerciseLogs: StateFlow<List<ExerciseLogItem>> = _exerciseLogs.asStateFlow()

    private val _userNotes = MutableStateFlow("")
    val userNotes: StateFlow<String> = _userNotes.asStateFlow()

    private val _isConsulting = MutableStateFlow(false)
    val isConsulting: StateFlow<Boolean> = _isConsulting.asStateFlow()

    private val _consultationResult = MutableStateFlow<WorkoutConsultationResult?>(null)
    val consultationResult: StateFlow<WorkoutConsultationResult?> = _consultationResult.asStateFlow()

    private val _chatHistory = MutableStateFlow<List<WorkoutChatMessage>>(emptyList())
    val chatHistory: StateFlow<List<WorkoutChatMessage>> = _chatHistory.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var menuListener: ListenerRegistration? = null
    private var logsListener: ListenerRegistration? = null

    init {
        listenToMenus()
        listenToLogs()
    }

    private fun listenToMenus() {
        menuListener = Firebase.firestore.collection("workout_menus")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                val parsed = snapshot.documents.mapNotNull { doc ->
                    try {
                        val title = doc.getString("title").orEmpty()
                        val targetMuscle = doc.getString("targetMuscle").orEmpty()
                        val sourceUrl = doc.getString("sourceUrl")
                        val createdAt = doc.getString("createdAt")
                        val exercisesRaw = doc.get("exercises") as? List<Map<String, Any>> ?: emptyList()
                        val exercises = exercisesRaw.map { m ->
                            WorkoutExercise(
                                id = m["id"] as? String ?: "",
                                name = m["name"] as? String ?: "",
                                targetMuscle = m["targetMuscle"] as? String ?: "",
                                targetWeight = (m["targetWeight"] as? Number)?.toDouble() ?: 0.0,
                                targetReps = m["targetReps"]?.toString() ?: "10",
                                targetSets = (m["targetSets"] as? Number)?.toInt() ?: 3,
                                restSeconds = (m["restSeconds"] as? Number)?.toInt() ?: 90,
                                notes = m["notes"] as? String ?: "",
                            )
                        }
                        WorkoutMenu(
                            id = doc.id,
                            title = title,
                            targetMuscle = targetMuscle,
                            exercises = exercises,
                            sourceUrl = sourceUrl,
                            createdAt = createdAt,
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
                _menus.value = parsed
                if (_selectedMenu.value == null && parsed.isNotEmpty()) {
                    selectMenu(parsed.first())
                }
            }
    }

    private fun listenToLogs() {
        logsListener = Firebase.firestore.collection("workout_logs")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                val parsed = snapshot.documents.mapNotNull { doc ->
                    try {
                        val menuId = doc.getString("menuId").orEmpty()
                        val menuTitle = doc.getString("menuTitle").orEmpty()
                        val date = doc.getString("date").orEmpty()
                        val userNotes = doc.getString("userNotes").orEmpty()
                        val geminiFeedback = doc.getString("geminiFeedback")
                        val createdAt = doc.getString("createdAt")
                        val exercisesRaw = doc.get("exercises") as? List<Map<String, Any>> ?: emptyList()
                        val exercises = exercisesRaw.map { m ->
                            val setsRaw = m["sets"] as? List<Map<String, Any>> ?: emptyList()
                            val sets = setsRaw.map { sm ->
                                WorkoutSetResult(
                                    setNum = (sm["setNum"] as? Number)?.toInt() ?: 1,
                                    weight = (sm["weight"] as? Number)?.toDouble() ?: 0.0,
                                    reps = (sm["reps"] as? Number)?.toInt() ?: 0,
                                    rpe = (sm["rpe"] as? Number)?.toDouble() ?: 8.0,
                                    isCompleted = sm["isCompleted"] as? Boolean ?: true,
                                )
                            }
                            ExerciseLogItem(
                                exerciseName = m["exerciseName"] as? String ?: "",
                                targetMuscle = m["targetMuscle"] as? String ?: "",
                                targetWeight = (m["targetWeight"] as? Number)?.toDouble() ?: 0.0,
                                targetReps = m["targetReps"]?.toString() ?: "10",
                                targetSets = (m["targetSets"] as? Number)?.toInt() ?: 3,
                                sets = sets,
                                notes = m["notes"] as? String ?: "",
                            )
                        }
                        WorkoutLog(
                            id = doc.id,
                            menuId = menuId,
                            menuTitle = menuTitle,
                            date = date,
                            exercises = exercises,
                            userNotes = userNotes,
                            geminiFeedback = geminiFeedback,
                            createdAt = createdAt,
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
                _logs.value = parsed
            }
    }

    fun selectMenu(menu: WorkoutMenu) {
        _selectedMenu.value = menu
        val initialLogs = menu.exercises.map { ex ->
            val count = ex.targetSets.coerceAtLeast(1)
            val repsNum = Regex("""\d+""").findAll(ex.targetReps)
                .mapNotNull { it.value.toIntOrNull() }
                .lastOrNull() ?: 10
            val sets = (1..count).map { sNum ->
                WorkoutSetResult(
                    setNum = sNum,
                    weight = ex.targetWeight,
                    reps = repsNum,
                    rpe = 8.0,
                    isCompleted = true,
                )
            }
            ExerciseLogItem(
                exerciseName = ex.name,
                targetMuscle = ex.targetMuscle,
                targetWeight = ex.targetWeight,
                targetReps = ex.targetReps,
                targetSets = ex.targetSets,
                sets = sets,
                notes = ex.notes,
            )
        }
        _exerciseLogs.value = initialLogs
        _consultationResult.value = null
        _chatHistory.value = emptyList()
    }

    fun updateSet(exIdx: Int, setIdx: Int, weight: Double, reps: Int, rpe: Double, completed: Boolean) {
        val current = _exerciseLogs.value.toMutableList()
        if (exIdx !in current.indices) return
        val ex = current[exIdx]
        val currentSets = ex.sets.toMutableList()
        if (setIdx !in currentSets.indices) return
        currentSets[setIdx] = currentSets[setIdx].copy(
            weight = weight,
            reps = reps,
            rpe = rpe,
            isCompleted = completed,
        )
        current[exIdx] = ex.copy(sets = currentSets)
        _exerciseLogs.value = current
    }

    fun addSet(exIdx: Int) {
        val current = _exerciseLogs.value.toMutableList()
        if (exIdx !in current.indices) return
        val ex = current[exIdx]
        val currentSets = ex.sets.toMutableList()
        val last = currentSets.lastOrNull()
        currentSets.add(
            WorkoutSetResult(
                setNum = currentSets.size + 1,
                weight = last?.weight ?: ex.targetWeight,
                reps = last?.reps ?: 10,
                rpe = last?.rpe ?: 8.0,
                isCompleted = true,
            )
        )
        current[exIdx] = ex.copy(sets = currentSets)
        _exerciseLogs.value = current
    }

    fun removeSet(exIdx: Int, setIdx: Int) {
        val current = _exerciseLogs.value.toMutableList()
        if (exIdx !in current.indices) return
        val ex = current[exIdx]
        val currentSets = ex.sets.toMutableList()
        if (setIdx !in currentSets.indices || currentSets.size <= 1) return
        currentSets.removeAt(setIdx)
        val reindexed = currentSets.mapIndexed { idx, s -> s.copy(setNum = idx + 1) }
        current[exIdx] = ex.copy(sets = reindexed)
        _exerciseLogs.value = current
    }

    fun updateUserNotes(notes: String) {
        _userNotes.value = notes
    }

    fun saveGeminiApiKey(apiKey: String) {
        viewModelScope.launch {
            repo.setGeminiApiKey(apiKey.trim())
        }
    }

    fun consultGemini(userQuestion: String? = null) {
        val key = geminiApiKey.value
        if (key.isNullOrBlank()) {
            _statusMessage.value = "Gemini APIキーを設定してください"
            return
        }
        val menu = _selectedMenu.value ?: return

        _isConsulting.value = true
        _statusMessage.value = null
        viewModelScope.launch {
            try {
                val consultant = GeminiWorkoutConsultant(key)
                val result = consultant.askWorkoutAdvice(
                    menuTitle = menu.title,
                    targetMuscle = menu.targetMuscle,
                    exerciseLogs = _exerciseLogs.value,
                    userNotes = _userNotes.value,
                    userQuestion = userQuestion,
                    history = _chatHistory.value,
                )
                _consultationResult.value = result

                val updatedHistory = _chatHistory.value.toMutableList()
                if (!userQuestion.isNullOrBlank()) {
                    updatedHistory.add(WorkoutChatMessage(WorkoutChatMessage.Role.USER, userQuestion))
                } else {
                    updatedHistory.add(WorkoutChatMessage(WorkoutChatMessage.Role.USER, "本日の「${menu.title}」実績の分析を依頼"))
                }
                updatedHistory.add(WorkoutChatMessage(WorkoutChatMessage.Role.GEMINI, result.answer))
                _chatHistory.value = updatedHistory
            } catch (e: Exception) {
                _statusMessage.value = "Gemini相談エラー: ${e.message}"
            } finally {
                _isConsulting.value = false
            }
        }
    }

    fun saveLog() {
        val menu = _selectedMenu.value ?: return
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).format(Date())
        viewModelScope.launch {
            try {
                val exercisesData = _exerciseLogs.value.map { ex ->
                    mapOf(
                        "exerciseName" to ex.exerciseName,
                        "targetMuscle" to ex.targetMuscle,
                        "targetWeight" to ex.targetWeight,
                        "targetReps" to ex.targetReps,
                        "targetSets" to ex.targetSets,
                        "notes" to ex.notes,
                        "sets" to ex.sets.map { s ->
                            mapOf(
                                "setNum" to s.setNum,
                                "weight" to s.weight,
                                "reps" to s.reps,
                                "rpe" to s.rpe,
                                "isCompleted" to s.isCompleted,
                            )
                        }
                    )
                }
                val logMap = mutableMapOf<String, Any>(
                    "menuId" to (menu.id),
                    "menuTitle" to menu.title,
                    "date" to todayStr,
                    "exercises" to exercisesData,
                    "userNotes" to _userNotes.value,
                    "createdAt" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date()),
                )
                val feedback = _consultationResult.value?.answer
                if (!feedback.isNullOrBlank()) {
                    logMap["geminiFeedback"] = feedback
                }
                Firebase.firestore.collection("workout_logs").add(logMap)
                _statusMessage.value = "✓ 実績をFirestoreに保存しました"
            } catch (e: Exception) {
                android.util.Log.e("WorkoutViewModel", "Save log error", e)
                _statusMessage.value = "保存エラー: ${e.message}"
            }
        }
    }

    fun addSampleMenu() {
        viewModelScope.launch {
            try {
                val sampleMenu = mapOf(
                    "title" to "胸・三頭の日 (Day A)",
                    "targetMuscle" to "胸・三頭",
                    "createdAt" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date()),
                    "updatedAt" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date()),
                    "exercises" to listOf(
                        mapOf(
                            "id" to "ex_1",
                            "name" to "ベンチプレス",
                            "targetMuscle" to "胸",
                            "targetWeight" to 60.0,
                            "targetReps" to "8-10",
                            "targetSets" to 3,
                            "restSeconds" to 90,
                            "notes" to "肩甲骨寄せてブリッジ維持"
                        ),
                        mapOf(
                            "id" to "ex_2",
                            "name" to "インクラインダンベルプレス",
                            "targetMuscle" to "胸",
                            "targetWeight" to 20.0,
                            "targetReps" to "10",
                            "targetSets" to 3,
                            "restSeconds" to 60,
                            "notes" to "トップで大胸筋上部を収縮"
                        ),
                        mapOf(
                            "id" to "ex_3",
                            "name" to "ディップス",
                            "targetMuscle" to "三頭",
                            "targetWeight" to 0.0,
                            "targetReps" to "12",
                            "targetSets" to 3,
                            "restSeconds" to 60,
                            "notes" to "自重、前傾深め"
                        )
                    )
                )
                Firebase.firestore.collection("workout_menus").add(sampleMenu)
                _statusMessage.value = "✓ サンプルメニューを追加しました"
            } catch (e: Exception) {
                _statusMessage.value = "追加エラー: ${e.message}"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        menuListener?.remove()
        logsListener?.remove()
    }
}
