package com.example.hibi.feature.stock

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hibi.data.BuyStep
import com.example.hibi.data.HibiRepository
import com.example.hibi.data.StockScenario
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StockViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = HibiRepository(app)
    private var firestoreListener: ListenerRegistration? = null

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

    init {
        startSyncingFirestore()
    }

    private fun startSyncingFirestore() {
        firestoreListener = Firebase.firestore.collection("stock_scenarios")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("StockViewModel", "Listen failed.", error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val remoteList = snapshot.documents.mapNotNull { doc ->
                        runCatching {
                            val data = doc.data ?: return@mapNotNull null
                            val symbol = data["symbol"] as? String ?: ""
                            val market = data["market"] as? String ?: "TYO"
                            val name = data["name"] as? String ?: ""
                            val targetHorizon = data["targetHorizon"] as? String ?: ""
                            val summary = data["summary"] as? String ?: ""
                            val geminiShareUrl = data["geminiShareUrl"] as? String
                            val fullText = data["fullText"] as? String ?: ""
                            val createdAt = data["createdAt"] as? String ?: ""

                            val rawSteps = data["steps"] as? List<Map<String, Any>> ?: emptyList()
                            val steps = rawSteps.map { s ->
                                BuyStep(
                                    step = (s["step"] as? Number)?.toInt() ?: 1,
                                    period = s["period"] as? String ?: "",
                                    notifyDate = s["notifyDate"] as? String ?: "",
                                    ratio = s["ratio"] as? String ?: "",
                                    aim = s["aim"] as? String ?: "",
                                    condition = s["condition"] as? String ?: "",
                                    isNotified = s["isNotified"] as? Boolean ?: false,
                                    isDone = s["isDone"] as? Boolean ?: false,
                                )
                            }

                            StockScenario(
                                id = doc.id,
                                symbol = symbol,
                                market = market,
                                name = name,
                                targetHorizon = targetHorizon,
                                summary = summary,
                                geminiShareUrl = geminiShareUrl,
                                steps = steps,
                                fullText = fullText,
                                createdAt = createdAt,
                            )
                        }.getOrNull()
                    }

                    viewModelScope.launch {
                        repo.updateScenarios { localList ->
                            val localDoneMap = localList.associate { it.id to it.steps.associate { s -> s.step to s.isDone } }
                            remoteList.map { remote ->
                                val stepDoneMap = localDoneMap[remote.id]
                                if (stepDoneMap != null) {
                                    val mergedSteps = remote.steps.map { s ->
                                        val isDone = stepDoneMap[s.step] ?: s.isDone
                                        s.copy(isDone = isDone)
                                    }
                                    remote.copy(steps = mergedSteps)
                                } else {
                                    remote
                                }
                            }
                        }
                    }
                }
            }
    }

    override fun onCleared() {
        super.onCleared()
        firestoreListener?.remove()
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
}
