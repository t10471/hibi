package com.example.hibi.feature.stock

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hibi.data.HibiRepository
import com.example.hibi.data.StockKnowledge
import com.example.hibi.data.StockScenario
import com.example.hibi.data.StockScenarioMapper
import com.example.hibi.notify.StockScheduler
import com.google.firebase.firestore.ListenerRegistration
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

    init {
        StockScheduler.ensureDailySchedule(context)
        startSyncingFirestore()
        startSyncingKnowledge()
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

    override fun onCleared() {
        super.onCleared()
        firestoreListener?.remove()
        knowledgeListener?.remove()
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
            repo.updateScenarios { list ->
                list.map { scenario ->
                    if (scenario.id == scenarioId) {
                        val updatedSteps = scenario.steps.map { s ->
                            if (s.step == stepNumber) s.copy(isDone = !s.isDone) else s
                        }
                        scenario.copy(steps = updatedSteps)
                    } else {
                        scenario
                    }
                }
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
}
