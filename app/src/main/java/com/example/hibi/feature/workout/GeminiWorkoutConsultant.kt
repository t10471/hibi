package com.example.hibi.feature.workout

import com.example.hibi.data.ExerciseLogItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

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

class GeminiWorkoutConsultant(
    private val apiKey: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun askWorkoutAdvice(
        menuTitle: String,
        targetMuscle: String,
        exerciseLogs: List<ExerciseLogItem>,
        userNotes: String,
        userQuestion: String? = null,
        history: List<WorkoutChatMessage> = emptyList(),
    ): WorkoutConsultationResult = withContext(Dispatchers.IO) {
        val jstFormat = SimpleDateFormat("yyyy年M月d日 (E) HH:mm 'JST'", Locale.JAPAN).apply {
            timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        }
        val currentDateStr = jstFormat.format(Date())

        val exercisesText = exerciseLogs.joinToString("\n\n") { ex ->
            val target = "【目標】 ${ex.targetWeight}kg × ${ex.targetReps}回 × ${ex.targetSets}セット"
            val sets = ex.sets.joinToString("\n") { s ->
                "  セット${s.setNum}: ${s.weight}kg × ${s.reps}回 (RPE: ${s.rpe})${if (s.isCompleted) " [完遂]" else " [未達]"}"
            }
            "${ex.exerciseName} (${ex.targetMuscle})\n$target\n$sets"
        }

        val systemInstruction = """
            あなたはプロの筋トレコーチ兼スポーツ科学エキスパートです。
            ユーザーがスマホアプリから入力した本日の筋トレ実績データを計画メニューと比較分析し、
            効果的なフィードバックとアドバイスを提供してください。
            現在の日時: $currentDateStr

            【出力構成】
            1. **本日の総評・達成度評価**:
               - 総ボリューム（重量×回数の合計）と計画に対する達成度をポジティブかつ客観的に評価。
            2. **漸進性過負荷（プログレッシブ・オーバーロード）診断**:
               - RPE（主観的疲労度・余力）と完遂状況を踏まえ、次回トレーニング時の推奨重量・レップ数を種目ごとに具体的に提示。
               - 例: 「ベンチプレスはRPE 8で3セット完遂できているため、次回は+2.5kg（62.5kg）で挑戦推奨」
            3. **疲労度・フォーム・リカバリーアドバイス**:
               - 次回同じ部位を鍛えるまでの推奨休息日数、タンパク質等の栄養補給の目安、怪我予防のストレッチ。

            【必須ルール】
            - スマホ画面で見やすいよう、要点を箇条書きや強調（**太字**）を交えて簡潔にまとめてください。
            - 回答の最末尾には必ず区切り線 `---SUGGESTIONS---` を配置し、ユーザーが次に深掘り・相談したい質問候補を2〜3個提示してください。
            例:
            ---SUGGESTIONS---
            次回重量を上げる場合のウォーミングアップの組み方を教えて
            今回の筋肉痛を早く回復させるストレッチや食事メニューは？
            ベンチプレスで肩に負担をかけないフォームの注意点は？
        """.trimIndent()

        val combinedPrompt = buildString {
            append(systemInstruction)
            append("\n\n")
            append("【本日のトレーニング実績】\n")
            append("メニュー: $menuTitle ($targetMuscle)\n")
            append(exercisesText)
            append("\n\n")
            if (userNotes.isNotBlank()) {
                append("【ユーザーの体感メモ】\n$userNotes\n\n")
            }
            if (history.isNotEmpty()) {
                append("【直前の会話ログ】\n")
                history.forEach { msg ->
                    val who = if (msg.role == WorkoutChatMessage.Role.USER) "ユーザー" else "Geminiコーチ"
                    append("$who: ${msg.text}\n")
                }
                append("\n")
            }
            if (!userQuestion.isNullOrBlank()) {
                append("ユーザーからの質問: $userQuestion")
            } else {
                append("上記の実績をもとに、達成度評価、次回への推奨重量・レップ数、リカバリーのアドバイスをお願いします。")
            }
        }

        val payload = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", combinedPrompt)
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", 3000)
                put("temperature", 0.3)
            }
        }

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-goog-api-key", apiKey.trim())
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            error("Gemini APIエラー (${response.code}): $responseBody")
        }

        parseConsultationResponse(responseBody)
    }

    private fun parseConsultationResponse(responseJson: String): WorkoutConsultationResult {
        return try {
            val element = json.parseToJsonElement(responseJson)
            val candidates = element.jsonObject["candidates"]?.jsonArray
            val firstCandidate = candidates?.firstOrNull()?.jsonObject
            val content = firstCandidate?.get("content")?.jsonObject
            val parts = content?.get("parts")?.jsonArray
            val fullText = parts?.joinToString("") {
                it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
            }.orEmpty()

            if (fullText.isBlank()) {
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
                    WorkoutConsultationResult(answer = mainText, suggestions = suggestions)
                } else {
                    WorkoutConsultationResult(answer = fullText.trim())
                }
            }
        } catch (e: Exception) {
            WorkoutConsultationResult("レスポンス解析エラー: ${e.message}")
        }
    }
}
