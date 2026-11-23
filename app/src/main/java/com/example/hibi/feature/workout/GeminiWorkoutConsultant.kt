package com.example.hibi.feature.workout

import com.example.hibi.data.ExerciseLogItem
import com.example.hibi.data.GeminiApiClient
import com.example.hibi.data.WorkoutLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class WorkoutChatMessage(
    val role: Role,
    val text: String,
) {
    enum class Role { USER, GEMINI }
}

data class WorkoutConsultationResult(
    val answer: String,
    val suggestions: List<String> = emptyList(),
)

data class HealthSessionData(
    val durationMinutes: Int = 70,
    val caloriesBurned: Int = 400,
    val avgHeartRate: Int = 125,
    val maxHeartRate: Int = 155,
)

class GeminiWorkoutConsultant(
    private val apiKey: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun askWorkoutAdvice(
        menuTitle: String,
        targetMuscle: String,
        exerciseLogs: List<ExerciseLogItem>,
        userNotes: String,
        healthData: HealthSessionData? = null,
        recentLogs: List<WorkoutLog> = emptyList(),
        focusMode: String = "前回比・ボリューム分析",
        userQuestion: String? = null,
        history: List<WorkoutChatMessage> = emptyList(),
    ): WorkoutConsultationResult = withContext(Dispatchers.IO) {
        val jstFormat = SimpleDateFormat("yyyy年M月d日 (E) HH:mm 'JST'", Locale.JAPAN).apply {
            timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        }
        val currentDateStr = jstFormat.format(Date())

        val todayVolume = computeTotalVolume(exerciseLogs)
        val exercisesText = exerciseLogs.joinToString("\n\n") { ex ->
            val target = "【計画】 ${ex.targetWeight}kg × ${ex.targetReps}回 × ${ex.targetSets}セット"
            val exVol = ex.sets.filter { it.isCompleted }.sumOf { it.weight * it.reps }.roundToInt()
            val lastSet = ex.sets.lastOrNull()
            val baseTarget = ex.baseTargetReps
            val targetComparison = when {
                lastSet == null -> "記録なし"
                !lastSet.isCompleted -> "未達（最終セット ${lastSet.reps}回で限界）"
                lastSet.reps > baseTarget -> "規定超え (+${lastSet.reps - baseTarget}回：規定${baseTarget}回 → 最終${lastSet.reps}回)"
                lastSet.reps < baseTarget -> "規定より少なめ (${lastSet.reps - baseTarget}回)"
                else -> "規定どおり達成 (${baseTarget}回完遂)"
            }
            val sets = ex.sets.joinToString("\n") { s ->
                "  セット${s.setNum}: ${s.weight}kg × ${s.reps}回${if (s.isCompleted) " [完遂]" else " [未達]"}"
            }
            "${ex.exerciseName} (${ex.targetMuscle}) [本日ボリューム: ${exVol}kg / 規定比: $targetComparison]\n$target\n$sets"
        }

        val historyComparisonText = buildHistorySection(todayVolume, recentLogs)

        val systemInstruction = """
            あなたはユーザー専属のストレングス＆コンディショニングコーチです。
            現在の日時: $currentDateStr

            【ユーザーのトレーニング前提（毎回オウム返しせず内部前提として扱うこと）】
            - **最終セットは毎回ほぼ限界まで出し切るのがデフォルト前提**（余裕度RPEの一般論は不要）。
            - 最も重要な評価指標は **「規定回数に対してどれだけ上回れたか（+1回、+2回など）または未達だったか」**。
            - 重量を無理に追うとスジを痛めやすいため、急激な重量増より「まず規定回数を超えて余力が出てきたら小刻みに増量 or レップ積み増し」を重視する。
            - メインの6レップ前後が重要セット。1回あたり約70分・400kcal消費を目安にしている。

            【絶対に守るべき回答ルール（脱マンネリ・脱テンプレ）】
            1. 前回と同じ定型見出し・一般論（「70分400kcalを目指しましょう」「スジを痛めないよう回数重視で…」等の決まり文句）を毎回繰り返さないこと。
            2. **前回履歴との具体的な差分（総ボリューム・規定超えレップ数の変化）** を冒頭で明確に指摘すること。
               - もし本日と前回が全く同じ重量・回数（規定どおり据え置き）だった場合は、同じ回答を繰り返さず「どの種目で次+1回を狙うべきか / テンポや刺激をどう変えるか」など具体的な次の一手だけを絞って提案すること。
            3. ユーザーが今回選んだ分析テーマ（**$focusMode**）と体感タグを最優先の軸として掘り下げること。
            4. スマホでパッと読めるよう、長文解説を避けて簡潔な箇条書き・表・太字でまとめること。
            5. 回答の最末尾には区切り線 `---SUGGESTIONS---` を置き、本日の規定超え結果と選択テーマを踏まえた**具体的で毎回異なる深掘り質問**を2〜3個だけ提示すること（定型文の使い回し禁止）。
        """.trimIndent()

        val combinedPrompt = buildString {
            append(systemInstruction)
            append("\n\n")
            append("【今回の相談フォーカス】$focusMode\n\n")
            append("【本日のトレーニング実績】\n")
            append("メニュー: $menuTitle ($targetMuscle) / 本日総ボリューム: ${todayVolume}kg\n")
            append(exercisesText)
            append("\n\n")
            if (historyComparisonText.isNotBlank()) {
                append(historyComparisonText)
                append("\n\n")
            }
            if (healthData != null) {
                append("【Google ヘルスケア (Fitbit) 運動データ】\n")
                append("・運動時間: ${healthData.durationMinutes} 分 / 総消費: ${healthData.caloriesBurned} kcal / 平均心拍: ${healthData.avgHeartRate} bpm / 最大心拍: ${healthData.maxHeartRate} bpm\n\n")
            }
            if (userNotes.isNotBlank()) {
                append("【ユーザーの選択タグ・体感メモ】\n$userNotes\n\n")
            }
            if (history.isNotEmpty()) {
                append("【このセッション内の直前会話（重複回答を避けること）】\n")
                history.takeLast(4).forEach { msg ->
                    val who = if (msg.role == WorkoutChatMessage.Role.USER) "ユーザー" else "コーチ"
                    append("$who: ${msg.text.take(400)}\n")
                }
                append("\n")
            }
            if (!userQuestion.isNullOrBlank()) {
                append("ユーザーからの質問: $userQuestion")
            } else {
                append("「$focusMode」の視点を中心に、前回との違いを踏まえて今日ならではの具体的なフィードバックをお願いします。")
            }
        }

        val fullText = GeminiApiClient.generateContentText(
            client = client,
            apiKey = apiKey,
            prompt = combinedPrompt,
            category = "workout",
            enableGoogleSearch = false,
        )

        parseConsultationText(fullText)
    }

    private fun computeTotalVolume(items: List<ExerciseLogItem>): Int {
        return items.sumOf { ex ->
            ex.sets.filter { it.isCompleted }.sumOf { it.weight * it.reps }
        }.roundToInt()
    }

    private fun buildHistorySection(todayVolume: Int, recentLogs: List<WorkoutLog>): String {
        if (recentLogs.isEmpty()) {
            return "【過去ログ比較】\nこのメニューの過去記録はまだありません（初回ベースラインとして評価してください）。"
        }
        val latest = recentLogs.first()
        val prevVolume = computeTotalVolume(latest.exercises)
        val diff = todayVolume - prevVolume
        val diffLabel = when {
            diff > 0 -> "+${diff}kg (前回より増加)"
            diff < 0 -> "${diff}kg (前回より減少)"
            else -> "±0kg (前回と完全同ボリューム)"
        }

        return buildString {
            append("【過去セッション履歴（直近${recentLogs.size}回）と前回比】\n")
            append("・前回(${latest.date})比の総ボリューム変化: $diffLabel\n")
            recentLogs.forEachIndexed { idx, log ->
                val vol = computeTotalVolume(log.exercises)
                append("\n▼ 過去${idx + 1}: ${log.date} [${log.menuTitle}] 総ボリューム=${vol}kg\n")
                log.exercises.forEach { ex ->
                    val summary = ex.sets.joinToString(", ") { s ->
                        "${s.weight}kg×${s.reps}(RPE${s.rpe}${if (s.isCompleted) "" else "/未達"})"
                    }
                    append("  - ${ex.exerciseName}: $summary\n")
                }
                if (log.userNotes.isNotBlank()) {
                    append("  - 当時のメモ: ${log.userNotes}\n")
                }
                if (!log.geminiFeedback.isNullOrBlank()) {
                    append("  - 前回コーチ回答の要旨(同じ内容の繰り返し厳禁): ${log.geminiFeedback.replace("\n", " ").take(200)}...\n")
                }
            }
        }.trimEnd()
    }

    private fun parseConsultationText(fullText: String): WorkoutConsultationResult {
        return if (fullText.isBlank()) {
            WorkoutConsultationResult("Geminiからの回答を受信できませんでした。")
        } else {
            val separator = "---SUGGESTIONS---"
            if (fullText.contains(separator)) {
                val partsSplit = fullText.split(separator)
                val mainText = partsSplit[0].trim()
                val suggestionsText = partsSplit.getOrNull(1).orEmpty()
                val suggestions = suggestionsText
                    .lines()
                    .map { it.trim().trimStart('-', '*', '•', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ' ') }
                    .filter { it.isNotBlank() }
                    .take(3)
                WorkoutConsultationResult(mainText, suggestions)
            } else {
                WorkoutConsultationResult(fullText.trim())
            }
        }
    }
}
