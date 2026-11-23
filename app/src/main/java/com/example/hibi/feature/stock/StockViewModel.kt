package com.example.hibi.feature.stock

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hibi.data.HibiRepository
import com.example.hibi.data.NewWatchCandidate
import com.example.hibi.data.StockAutomationSettings
import com.example.hibi.data.StockKnowledge
import com.example.hibi.data.StockReport
import com.example.hibi.data.StockReportVerdict
import com.example.hibi.data.StockScenario
import com.example.hibi.data.StockScenarioMapper
import com.example.hibi.notify.StockScheduler
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

class StockViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = HibiRepository(app)
    private val context = app.applicationContext
    private var firestoreListener: ListenerRegistration? = null
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

    // ワンタイム相談チャット状態
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isChatLoading = MutableStateFlow(false)
    val isChatLoading: StateFlow<Boolean> = _isChatLoading.asStateFlow()

    // Geminiが推論した「次に質問すべきサジェスト」
    private val _dynamicSuggestions = MutableStateFlow<List<String>>(emptyList())
    val dynamicSuggestions: StateFlow<List<String>> = _dynamicSuggestions.asStateFlow()

    // 投資ナレッジ一覧
    private val _knowledgeList = MutableStateFlow<List<StockKnowledge>>(emptyList())
    val knowledgeList: StateFlow<List<StockKnowledge>> = _knowledgeList.asStateFlow()

    // AI自動分析レポート一覧
    private val _reports = MutableStateFlow<List<StockReport>>(emptyList())
    val reports: StateFlow<List<StockReport>> = _reports.asStateFlow()

    // 自動実行設定（即停止スイッチ・月額上限）
    private val _automationSettings = MutableStateFlow(StockAutomationSettings())
    val automationSettings: StateFlow<StockAutomationSettings> = _automationSettings.asStateFlow()

    init {
        StockScheduler.ensureDailySchedule(context)
        startSyncingFirestore()
        startSyncingKnowledge()
        startSyncingReports()
        startSyncingAutomationSettings()
    }

    private fun startSyncingFirestore() {
        firestoreListener = Firebase.firestore.collection("stock_scenarios")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("StockViewModel", "Listen failed.", error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val remoteList = snapshot.documents.mapNotNull { StockScenarioMapper.fromDocument(it) }
                    viewModelScope.launch {
                        StockScheduler.checkAndNotifyDueSteps(context, remoteList)
                    }
                }
            }
    }

    private fun startSyncingKnowledge() {
        knowledgeListener = Firebase.firestore.collection("stock_knowledge")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("StockViewModel", "Knowledge listen failed.", error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { doc ->
                        runCatching {
                            val data = doc.data ?: return@mapNotNull null
                            StockKnowledge(
                                id = doc.id,
                                title = data["title"] as? String ?: "",
                                category = data["category"] as? String ?: "その他",
                                tags = (data["tags"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                                content = data["content"] as? String ?: "",
                                images = (data["images"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                                isPinned = data["isPinned"] as? Boolean ?: false,
                                createdAt = data["createdAt"] as? String ?: "",
                                updatedAt = data["updatedAt"] as? String ?: "",
                            )
                        }.getOrNull()
                    }.sortedWith(
                        compareByDescending<StockKnowledge> { it.isPinned }
                            .thenByDescending { it.updatedAt.ifBlank { it.createdAt } }
                    )
                    _knowledgeList.value = list
                }
            }
    }

    private fun startSyncingReports() {
        reportsListener = Firebase.firestore.collection("stock_reports")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("StockViewModel", "Reports listen failed.", error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { doc ->
                        runCatching {
                            val data = doc.data ?: return@mapNotNull null
                            val rawVerdicts = data["verdicts"] as? List<*> ?: emptyList<Any>()
                            val verdicts = rawVerdicts.mapNotNull { entry ->
                                val m = entry as? Map<*, *> ?: return@mapNotNull null
                                StockReportVerdict(
                                    symbol = m["symbol"] as? String ?: "",
                                    status = m["status"] as? String ?: "",
                                    verdict = m["verdict"] as? String ?: "WAIT",
                                    oneLiner = m["oneLiner"] as? String ?: "",
                                )
                            }
                            val rawCandidates = data["newWatchCandidates"] as? List<*> ?: emptyList<Any>()
                            val candidates = rawCandidates.mapNotNull { entry ->
                                val m = entry as? Map<*, *> ?: return@mapNotNull null
                                NewWatchCandidate(
                                    symbol = m["symbol"] as? String ?: "",
                                    market = m["market"] as? String ?: "NASDAQ",
                                    name = m["name"] as? String ?: "",
                                    targetHorizon = m["targetHorizon"] as? String ?: "",
                                    reason = m["reason"] as? String ?: "",
                                )
                            }
                            StockReport(
                                id = doc.id,
                                type = data["type"] as? String ?: "daily_market",
                                date = data["date"] as? String ?: "",
                                title = data["title"] as? String ?: doc.id,
                                content = data["content"] as? String ?: "",
                                verdicts = verdicts,
                                newWatchCandidates = candidates,
                                goCount = (data["goCount"] as? Number)?.toInt() ?: 0,
                                model = data["model"] as? String ?: "",
                                createdAt = data["createdAt"] as? String ?: "",
                                updatedAt = data["updatedAt"] as? String ?: "",
                            )
                        }.getOrNull()
                    }.sortedWith(
                        compareByDescending<StockReport> { it.date }
                            .thenByDescending { it.updatedAt.ifBlank { it.createdAt } }
                    )
                    _reports.value = list
                }
            }
    }

    private fun startSyncingAutomationSettings() {
        automationListener = Firebase.firestore.collection("stock_settings").document("automation")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("StockViewModel", "Automation settings listen failed.", error)
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val data = snapshot.data.orEmpty()
                    _automationSettings.value = StockAutomationSettings(
                        enabled = data["enabled"] as? Boolean ?: true,
                        monthlyBudgetJpy = (data["monthlyBudgetJpy"] as? Number)?.toLong() ?: 1000L,
                        updatedAt = data["updatedAt"] as? String ?: "",
                    )
                } else {
                    _automationSettings.value = StockAutomationSettings()
                }
            }
    }

    override fun onCleared() {
        super.onCleared()
        firestoreListener?.remove()
        knowledgeListener?.remove()
        reportsListener?.remove()
        automationListener?.remove()
    }

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            repo.setGeminiApiKey(key)
        }
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
            var promotedToHolding = false
            repo.updateScenarios { list ->
                list.map { scenario ->
                    if (scenario.id == scenarioId) {
                        val updatedSteps = scenario.steps.map { s ->
                            if (s.step == stepNumber) s.copy(isDone = !s.isDone) else s
                        }
                        val nextStatus = if (updatedSteps.any { it.isDone } && scenario.effectiveStatus == "WATCH") {
                            promotedToHolding = true
                            "HOLDING"
                        } else {
                            scenario.status
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
            if (promotedToHolding) {
                firestoreUpdates["status"] = "HOLDING"
            }
            Firebase.firestore.collection("stock_scenarios").document(scenarioId)
                .update(firestoreUpdates)
                .addOnFailureListener { e -> Log.w("StockViewModel", "Failed to sync step done", e) }
        }
    }

    fun updateScenarioStatus(scenarioId: String, newStatus: String) {
        val normalized = newStatus.uppercase()
        viewModelScope.launch {
            repo.updateScenarios { list ->
                list.map { if (it.id == scenarioId) it.copy(status = normalized) else it }
            }
            Firebase.firestore.collection("stock_scenarios").document(scenarioId)
                .update("status", normalized)
                .addOnFailureListener { e -> Log.w("StockViewModel", "Failed to update status", e) }
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
            Firebase.firestore.collection("stock_scenarios").document(scenarioId)
                .update(
                    mapOf(
                        "holdingQuantity" to trimmedQty,
                        "averageCost" to trimmedCost,
                        "holdingNote" to trimmedNote,
                    )
                )
                .addOnFailureListener { e -> Log.w("StockViewModel", "Failed to update holding details", e) }
        }
    }

    fun addWatchCandidate(candidate: NewWatchCandidate) {
        val sym = candidate.symbol.trim().uppercase()
        if (sym.isBlank()) return
        val now = Instant.now().toString()
        val payload = hashMapOf<String, Any>(
            "symbol" to sym,
            "market" to candidate.market.ifBlank { "NASDAQ" },
            "name" to candidate.name.ifBlank { sym },
            "status" to "WATCH",
            "holdingNote" to "",
            "targetHorizon" to candidate.targetHorizon.ifBlank { "中期監視" },
            "summary" to candidate.reason,
            "steps" to listOf(
                mapOf(
                    "step" to 1,
                    "period" to "押し目・条件合致時",
                    "notifyDate" to "",
                    "ratio" to "50%",
                    "aim" to candidate.reason,
                    "condition" to candidate.targetHorizon,
                    "isNotified" to false,
                    "isDone" to false,
                )
            ),
            "fullText" to "【AI地合い連動スクリーニング提案】\n${candidate.reason}\n\n目安・想定期間: ${candidate.targetHorizon}",
            "createdAt" to now,
        )
        Firebase.firestore.collection("stock_scenarios").add(payload)
    }

    fun archiveBySymbol(symbol: String) {
        val target = scenarios.value.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) } ?: return
        updateScenarioStatus(target.id, "ARCHIVED")
    }

    fun deleteBySymbol(symbol: String) {
        val matches = scenarios.value.filter { it.symbol.equals(symbol, ignoreCase = true) }
        if (matches.isEmpty()) return
        val matchIds = matches.map { it.id }.toSet()
        viewModelScope.launch {
            repo.updateScenarios { list -> list.filterNot { it.id in matchIds } }
            matches.forEach { target ->
                Firebase.firestore.collection("stock_scenarios").document(target.id).delete()
            }
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
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return
        val now = Instant.now().toString()
        val data = hashMapOf<String, Any>(
            "title" to trimmedTitle,
            "category" to category.ifBlank { "その他" },
            "tags" to tags,
            "content" to content.trim(),
            "images" to images,
            "isPinned" to isPinned,
            "updatedAt" to now,
        )
        val col = Firebase.firestore.collection("stock_knowledge")
        if (editingId != null) {
            col.document(editingId).update(data)
        } else {
            data["createdAt"] = now
            col.add(data)
        }
    }

    fun toggleKnowledgePin(item: StockKnowledge) {
        Firebase.firestore.collection("stock_knowledge").document(item.id)
            .update(
                mapOf(
                    "isPinned" to !item.isPinned,
                    "updatedAt" to Instant.now().toString(),
                )
            )
    }

    fun deleteKnowledge(item: StockKnowledge) {
        Firebase.firestore.collection("stock_knowledge").document(item.id).delete()
    }

    fun deleteReport(report: StockReport) {
        Firebase.firestore.collection("stock_reports").document(report.id).delete()
    }

    fun setAutomationEnabled(enabled: Boolean) {
        Firebase.firestore.collection("stock_settings").document("automation")
            .set(
                mapOf(
                    "enabled" to enabled,
                    "updatedAt" to Instant.now().toString(),
                ),
                SetOptions.merge(),
            )
    }

    fun setMonthlyBudgetJpy(budgetJpy: Long) {
        Firebase.firestore.collection("stock_settings").document("automation")
            .set(
                mapOf(
                    "monthlyBudgetJpy" to budgetJpy,
                    "updatedAt" to Instant.now().toString(),
                ),
                SetOptions.merge(),
            )
    }
}
