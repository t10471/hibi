package com.example.hibi.feature.stock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hibi.data.HibiRepository
import com.example.hibi.data.NewWatchCandidate
import com.example.hibi.data.StockAutomationSettings
import com.example.hibi.data.StockFirestoreDataSource
import com.example.hibi.data.StockKnowledge
import com.example.hibi.data.StockReport
import com.example.hibi.data.StockScenario
import com.example.hibi.data.StockScenarioMapper
import com.example.hibi.notify.StockScheduler
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StockViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = HibiRepository(app)
    private val remoteStore = StockFirestoreDataSource()
    private val context = app.applicationContext

    private var scenariosListener: ListenerRegistration? = null
    private var knowledgeListener: ListenerRegistration? = null
    private var reportsListener: ListenerRegistration? = null
    private var automationListener: ListenerRegistration? = null

    val scenarios: StateFlow<List<StockScenario>> =
        repo.scenarios.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    val geminiApiKey: StateFlow<String?> =
        repo.geminiApiKey.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null,
        )

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isChatLoading = MutableStateFlow(false)
    val isChatLoading: StateFlow<Boolean> = _isChatLoading.asStateFlow()

    private val _dynamicSuggestions = MutableStateFlow<List<String>>(emptyList())
    val dynamicSuggestions: StateFlow<List<String>> = _dynamicSuggestions.asStateFlow()

    private val _knowledgeList = MutableStateFlow<List<StockKnowledge>>(emptyList())
    val knowledgeList: StateFlow<List<StockKnowledge>> = _knowledgeList.asStateFlow()

    private val _reports = MutableStateFlow<List<StockReport>>(emptyList())
    val reports: StateFlow<List<StockReport>> = _reports.asStateFlow()

    private val _automationSettings = MutableStateFlow(StockAutomationSettings())
    val automationSettings: StateFlow<StockAutomationSettings> = _automationSettings.asStateFlow()

    init {
        StockScheduler.ensureDailySchedule(context)
        scenariosListener = remoteStore.observeScenarios { remoteList ->
            viewModelScope.launch {
                repo.updateScenarios { current ->
                    StockScenarioMapper.mergeWithLocal(remoteList, current)
                }
                StockScheduler.checkAndNotifyDueSteps(context, remoteList)
            }
        }
        knowledgeListener = remoteStore.observeKnowledge { _knowledgeList.value = it }
        reportsListener = remoteStore.observeReports { _reports.value = it }
        automationListener = remoteStore.observeAutomationSettings { _automationSettings.value = it }
    }

    override fun onCleared() {
        super.onCleared()
        scenariosListener?.remove()
        knowledgeListener?.remove()
        reportsListener?.remove()
        automationListener?.remove()
    }

    fun saveApiKey(key: String) {
        viewModelScope.launch { repo.setGeminiApiKey(key) }
    }

    fun clearChat() {
        _chatMessages.value = emptyList()
        _dynamicSuggestions.value = emptyList()
    }

    fun sendConsultation(scenario: StockScenario, question: String) {
        val apiKey = geminiApiKey.value
        if (apiKey.isNullOrBlank()) return

        val userMsg = ChatMessage(ChatMessage.Role.USER, question)
        _chatMessages.value = _chatMessages.value + userMsg
        _isChatLoading.value = true

        val scenarioContext = buildString {
            append("銘柄: ${scenario.name} (${scenario.symbol}:${scenario.market})\n")
            append("目標・期間: ${scenario.targetHorizon}\n")
            append("要約: ${scenario.summary}\n\n")
            append("【購入ステップ】\n")
            scenario.steps.forEach { s ->
                val status = if (s.isDone) "【購入済】" else "【未購入】"
                append("- 第${s.step}回 (${s.ratio}): ${s.period} / 狙い: ${s.aim} / 条件: ${s.condition} $status\n")
            }
            if (scenario.fullText.isNotBlank()) {
                append("\n【背景・分析全文】\n${scenario.fullText}\n")
            }
        }

        viewModelScope.launch {
            val consultant = GeminiStockConsultant(apiKey)
            val result = consultant.askScenarioAdvice(
                scenarioContext = scenarioContext,
                userQuestion = question,
                history = _chatMessages.value.dropLast(1),
            )
            result.onSuccess { res ->
                _chatMessages.value = _chatMessages.value + ChatMessage(ChatMessage.Role.GEMINI, res.answer)
                if (res.suggestions.isNotEmpty()) {
                    _dynamicSuggestions.value = res.suggestions
                }
            }.onFailure { err ->
                _chatMessages.value = _chatMessages.value + ChatMessage(ChatMessage.Role.GEMINI, "エラーが発生しました: ${err.message}")
            }
            _isChatLoading.value = false
        }
    }

    fun toggleStepDone(scenarioId: String, stepNumber: Int) {
        viewModelScope.launch {
            var syncedSteps: List<Map<String, Any>>? = null
            var updatedStatus: String? = null
            repo.updateScenarios { list ->
                list.map { scenario ->
                    if (scenario.id == scenarioId) {
                        val updatedSteps = scenario.steps.map { s ->
                            if (s.step == stepNumber) s.copy(isDone = !s.isDone) else s
                        }
                        val anyDone = updatedSteps.any { it.isDone }
                        val curStatus = scenario.effectiveStatus
                        val nextStatus = when {
                            anyDone && curStatus == "WATCH" -> {
                                updatedStatus = "HOLDING"
                                "HOLDING"
                            }
                            !anyDone && curStatus == "HOLDING" &&
                                scenario.holdingQuantity.isBlank() && scenario.averageCost.isBlank() -> {
                                updatedStatus = "WATCH"
                                "WATCH"
                            }
                            else -> scenario.status
                        }
                        syncedSteps = updatedSteps.map { s ->
                            mapOf(
                                "step" to s.step,
                                "period" to s.period,
                                "notifyDate" to s.notifyDate,
                                "ratio" to s.ratio,
                                "aim" to s.aim,
                                "condition" to s.condition,
                                "isNotified" to s.isNotified,
                                "isDone" to s.isDone,
                            )
                        }
                        scenario.copy(steps = updatedSteps, status = nextStatus)
                    } else {
                        scenario
                    }
                }
            }
            val stepsPayload = syncedSteps ?: return@launch
            val firestoreUpdates = hashMapOf<String, Any>("steps" to stepsPayload)
            updatedStatus?.let { firestoreUpdates["status"] = it }
            remoteStore.updateScenarioFields(scenarioId, firestoreUpdates)
        }
    }

    fun updateScenarioStatus(scenarioId: String, newStatus: String) {
        val normalized = newStatus.uppercase()
        viewModelScope.launch {
            repo.updateScenarios { list ->
                list.map { if (it.id == scenarioId) it.copy(status = normalized) else it }
            }
            remoteStore.updateScenarioFields(scenarioId, mapOf("status" to normalized))
        }
    }

    fun updateHoldingDetails(scenarioId: String, quantity: String, averageCost: String, note: String) {
        val trimmedQty = quantity.trim()
        val trimmedCost = averageCost.trim()
        val trimmedNote = note.trim()
        viewModelScope.launch {
            repo.updateScenarios { list ->
                list.map {
                    if (it.id == scenarioId) {
                        it.copy(
                            holdingQuantity = trimmedQty,
                            averageCost = trimmedCost,
                            holdingNote = trimmedNote,
                        )
                    } else {
                        it
                    }
                }
            }
            remoteStore.updateScenarioFields(
                scenarioId,
                mapOf(
                    "holdingQuantity" to trimmedQty,
                    "averageCost" to trimmedCost,
                    "holdingNote" to trimmedNote,
                ),
            )
        }
    }

    fun addWatchCandidate(candidate: NewWatchCandidate) {
        remoteStore.addWatchCandidate(candidate)
    }

    fun archiveBySymbol(symbol: String) {
        val target = scenarios.value.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) } ?: return
        updateScenarioStatus(target.id, "ARCHIVED")
    }

    fun deleteScenario(scenarioId: String) {
        viewModelScope.launch {
            repo.updateScenarios { list -> list.filterNot { it.id == scenarioId } }
            remoteStore.deleteScenario(scenarioId)
        }
    }

    fun toggleWatchCandidate(candidate: NewWatchCandidate, checked: Boolean) {
        val sym = candidate.symbol.trim()
        if (sym.isBlank()) return
        if (checked) {
            val alreadyActive = scenarios.value.any {
                it.symbol.equals(sym, ignoreCase = true) && it.effectiveStatus != "ARCHIVED"
            }
            if (!alreadyActive) {
                addWatchCandidate(candidate)
            }
            return
        }
        val unownedMatches = scenarios.value.filter {
            it.symbol.equals(sym, ignoreCase = true) && it.effectiveStatus != "HOLDING"
        }
        if (unownedMatches.isEmpty()) return
        val matchIds = unownedMatches.map { it.id }.toSet()
        viewModelScope.launch {
            repo.updateScenarios { list -> list.filterNot { it.id in matchIds } }
            unownedMatches.forEach { remoteStore.deleteScenario(it.id) }
        }
    }

    fun deleteBySymbol(symbol: String) {
        val matches = scenarios.value.filter {
            it.symbol.equals(symbol, ignoreCase = true) && it.effectiveStatus != "HOLDING"
        }
        if (matches.isEmpty()) return
        val matchIds = matches.map { it.id }.toSet()
        viewModelScope.launch {
            repo.updateScenarios { list -> list.filterNot { it.id in matchIds } }
            matches.forEach { remoteStore.deleteScenario(it.id) }
        }
    }

    fun saveKnowledge(
        editingId: String?,
        title: String,
        category: String,
        tags: List<String>,
        content: String,
        images: List<String>,
        isPinned: Boolean,
    ) {
        remoteStore.saveKnowledge(editingId, title, category, tags, content, images, isPinned)
    }

    fun toggleKnowledgePin(item: StockKnowledge) {
        remoteStore.toggleKnowledgePin(item)
    }

    fun deleteKnowledge(item: StockKnowledge) {
        remoteStore.deleteKnowledge(item.id)
    }

    fun deleteReport(report: StockReport) {
        remoteStore.deleteReport(report.id)
    }

    fun setAutomationEnabled(enabled: Boolean) {
        remoteStore.updateAutomationSetting(mapOf("enabled" to enabled))
    }

    fun setMonthlyBudgetJpy(budgetJpy: Long) {
        remoteStore.updateAutomationSetting(mapOf("monthlyBudgetJpy" to budgetJpy))
    }
}
