package com.example.hibi.data

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import java.time.Instant
import java.time.LocalDate

/**
 * Encapsulates Firestore snapshot listeners and mutations for all stock-related collections
 * so [com.example.hibi.feature.stock.StockViewModel] stays decoupled from raw document mapping.
 */
class StockFirestoreDataSource {

    fun observeScenarios(onUpdate: (List<StockScenario>) -> Unit): ListenerRegistration =
        Firebase.firestore.collection("stock_scenarios")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Scenarios listen failed.", error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    onUpdate(snapshot.documents.mapNotNull { StockScenarioMapper.fromDocument(it) })
                }
            }

    fun observeKnowledge(onUpdate: (List<StockKnowledge>) -> Unit): ListenerRegistration =
        Firebase.firestore.collection("stock_knowledge")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Knowledge listen failed.", error)
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
                    onUpdate(list)
                }
            }

    fun observeReports(onUpdate: (List<StockReport>) -> Unit): ListenerRegistration =
        Firebase.firestore.collection("stock_reports")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Reports listen failed.", error)
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
                                val rawSteps = m["steps"] as? List<*> ?: emptyList<Any>()
                                val parsedSteps = rawSteps.mapIndexedNotNull { idx, stepObj ->
                                    val sm = stepObj as? Map<*, *> ?: return@mapIndexedNotNull null
                                    BuyStep(
                                        step = (sm["step"] as? Number)?.toInt() ?: (idx + 1),
                                        period = sm["period"] as? String ?: "押し目・条件合致時",
                                        notifyDate = sm["notifyDate"] as? String ?: "",
                                        ratio = sm["ratio"] as? String ?: "33%",
                                        aim = sm["aim"] as? String ?: "",
                                        condition = sm["condition"] as? String ?: "",
                                        isNotified = false,
                                        isDone = false,
                                    )
                                }
                                NewWatchCandidate(
                                    symbol = m["symbol"] as? String ?: "",
                                    market = m["market"] as? String ?: "NASDAQ",
                                    name = m["name"] as? String ?: "",
                                    targetHorizon = m["targetHorizon"] as? String ?: "",
                                    reason = m["reason"] as? String ?: "",
                                    steps = parsedSteps,
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
                    onUpdate(list)
                }
            }

    fun observeAutomationSettings(onUpdate: (StockAutomationSettings) -> Unit): ListenerRegistration =
        Firebase.firestore.collection("stock_settings").document("automation")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Automation settings listen failed.", error)
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val data = snapshot.data.orEmpty()
                    onUpdate(
                        StockAutomationSettings(
                            enabled = data["enabled"] as? Boolean ?: true,
                            monthlyBudgetJpy = (data["monthlyBudgetJpy"] as? Number)?.toLong() ?: 1000L,
                            updatedAt = data["updatedAt"] as? String ?: "",
                        )
                    )
                } else {
                    onUpdate(StockAutomationSettings())
                }
            }

    fun updateScenarioFields(scenarioId: String, updates: Map<String, Any>) {
        Firebase.firestore.collection("stock_scenarios").document(scenarioId)
            .update(updates)
            .addOnFailureListener { e -> Log.w(TAG, "Failed updating scenario $scenarioId", e) }
    }

    fun addWatchCandidate(candidate: NewWatchCandidate) {
        val sym = candidate.symbol.trim().uppercase()
        if (sym.isBlank()) return
        val now = Instant.now().toString()
        val todayDate = LocalDate.now()
        val dateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        val stepsPayload = if (candidate.steps.isNotEmpty()) {
            candidate.steps.mapIndexed { idx, s ->
                val resolvedDate = if (dateRegex.matches(s.notifyDate.trim())) {
                    s.notifyDate.trim()
                } else {
                    todayDate.plusDays(((idx + 1) * 14 - 7).toLong()).toString()
                }
                mapOf(
                    "step" to (idx + 1),
                    "period" to s.period.ifBlank { "${resolvedDate} 頃チェック" },
                    "notifyDate" to resolvedDate,
                    "ratio" to s.ratio.ifBlank { "33%" },
                    "aim" to s.aim.ifBlank { candidate.reason },
                    "condition" to s.condition.ifBlank { candidate.targetHorizon },
                    "isNotified" to false,
                    "isDone" to false,
                )
            }
        } else {
            val cond = candidate.targetHorizon.ifBlank { "支持帯での下げ止まり確認" }
            val d1 = todayDate.plusDays(7).toString()
            val d2 = todayDate.plusDays(30).toString()
            listOf(
                mapOf(
                    "step" to 1,
                    "period" to "1週間後チェック（初回打診）",
                    "notifyDate" to d1,
                    "ratio" to "40%",
                    "aim" to candidate.reason,
                    "condition" to cond,
                    "isNotified" to false,
                    "isDone" to false,
                ),
                mapOf(
                    "step" to 2,
                    "period" to "1ヶ月後チェック（本玉追加）",
                    "notifyDate" to d2,
                    "ratio" to "60%",
                    "aim" to "業績モメンタム確認後の本玉追加",
                    "condition" to cond,
                    "isNotified" to false,
                    "isDone" to false,
                ),
            )
        }
        val payload = hashMapOf<String, Any>(
            "symbol" to sym,
            "market" to candidate.market.ifBlank { "NASDAQ" },
            "name" to candidate.name.ifBlank { sym },
            "status" to "WATCH",
            "holdingNote" to "",
            "targetHorizon" to candidate.targetHorizon.ifBlank { "中期監視" },
            "summary" to candidate.reason,
            "steps" to stepsPayload,
            "fullText" to "【AI地合い連動スクリーニング提案】\n${candidate.reason}\n\n目安・想定期間: ${candidate.targetHorizon}",
            "createdAt" to now,
        )
        Firebase.firestore.collection("stock_scenarios").add(payload)
    }

    fun deleteScenario(scenarioId: String) {
        Firebase.firestore.collection("stock_scenarios").document(scenarioId).delete()
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

    fun deleteKnowledge(knowledgeId: String) {
        Firebase.firestore.collection("stock_knowledge").document(knowledgeId).delete()
    }

    fun deleteReport(reportId: String) {
        Firebase.firestore.collection("stock_reports").document(reportId).delete()
    }

    fun updateAutomationSetting(patch: Map<String, Any>) {
        Firebase.firestore.collection("stock_settings").document("automation")
            .set(
                patch + ("updatedAt" to Instant.now().toString()),
                SetOptions.merge(),
            )
    }

    private companion object {
        const val TAG = "StockFirestoreSource"
    }
}
