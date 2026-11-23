package com.example.hibi.data

import com.google.firebase.firestore.DocumentSnapshot

/** Shared Firestore mapping and local-state merge logic for [StockScenario]. */
object StockScenarioMapper {

    fun fromDocument(doc: DocumentSnapshot): StockScenario? = runCatching {
        val data = doc.data ?: return null
        val symbol = data["symbol"] as? String ?: ""
        val market = data["market"] as? String ?: "TYO"
        val name = data["name"] as? String ?: ""
        val targetHorizon = data["targetHorizon"] as? String ?: ""
        val summary = data["summary"] as? String ?: ""
        val geminiShareUrl = data["geminiShareUrl"] as? String
        val fullText = data["fullText"] as? String ?: ""
        val createdAt = data["createdAt"] as? String ?: ""

        val rawSteps = data["steps"] as? List<*> ?: emptyList<Any>()
        val steps = rawSteps.mapNotNull { entry ->
            val s = entry as? Map<*, *> ?: return@mapNotNull null
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
            latestVerdict = data["latestVerdict"] as? String,
            latestVerdictReason = data["latestVerdictReason"] as? String,
            latestCheckedAt = data["latestCheckedAt"] as? String,
            createdAt = createdAt,
        )
    }.getOrNull()

    /**
     * Merges Firestore scenarios with local persisted [isDone] and [isNotified] flags,
     * since the Android app reads scenarios anonymously and stores notification/done flags locally.
     */
    fun mergeWithLocal(
        remoteList: List<StockScenario>,
        localList: List<StockScenario>,
    ): List<StockScenario> {
        val localMap = localList.associateBy { it.id }
        return remoteList.map { remote ->
            val local = localMap[remote.id] ?: return@map remote
            val localStepMap = local.steps.associateBy { it.step }
            val mergedSteps = remote.steps.map { s ->
                val localStep = localStepMap[s.step]
                if (localStep != null) {
                    s.copy(
                        isDone = localStep.isDone || s.isDone,
                        isNotified = localStep.isNotified || s.isNotified,
                    )
                } else {
                    s
                }
            }
            remote.copy(steps = mergedSteps)
        }
    }
}
