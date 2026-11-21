package com.example.hibi.feature.stock

import com.example.hibi.data.GeminiApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class ChatMessage(
    val role: Role,
    val text: String,
) {
    enum class Role { USER, GEMINI }
}

data class ConsultationResult(
    val answer: String,
    val suggestions: List<String> = emptyList(),
)

class GeminiStockConsultant(
    private val apiKey: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun askScenarioAdvice(
        scenarioContext: String,
        userQuestion: String,
        history: List<ChatMessage> = emptyList(),
    ): Result<ConsultationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val sdf = SimpleDateFormat("yyyy年M月d日 (E) HH:mm 'JST'", Locale.JAPAN).apply {
                timeZone = TimeZone.getTimeZone("Asia/Tokyo")
            }
            val todayStr = sdf.format(Date())

            val systemInstruction = """
                あなたは、株式投資の【リスク管理責任者・冷静沈着な門番（アドバイザー）】です。
                ユーザーは事前に綿密に策定した以下の「投資シナリオ」をもとに、現在の状況や買い時について相談しています。

                【本日の日時】
                $todayStr

                【対象銘柄および登録された投資シナリオ・計画】
                $scenarioContext

                【あなたの最も重要な行動規範・ルール】
                1. **安易に買いを肯定しない（迎合・イエスマン厳禁）**:
                   ユーザーが「今日買ってもいい？」「急落したからチャンス？」と急いて尋ねてきても、決して感情に流されず、ブレーキをかけるのがあなたの第一の使命です。「落ちるナイフ」を掴ませてはいけません。
                2. **リアルタイム株価・ニュースの照合**:
                   Google検索ツールを用いて、必ず対象銘柄の【最新の現在株価 / 前日比 / 直近の決算やニュース】を検索・把握した上で回答してください。
                3. **シナリオ前提条件との冷徹な照合**:
                   - 【時期の照合】: 本日の日付がシナリオの予定時期（例: ○年○月〜○月）に入っているか？まだ早い場合はフライングと指摘してください。
                   - 【目標価格帯の照合】: 検索した現在の実勢株価は、シナリオが想定した打診買い水準まで実際に到達しているか？
                   - 【前提条件の照合】: シナリオに明記された「条件」（例: 数週間底固めを確認、決算発表を無事通過、悪材料出尽くしなど）が客観的に満たされているか？急落直後でまだ底固めが確認できていないなら、決して「買い」と言ってはいけません。
                4. **回答フォーマット（結論から即答）**:
                   回答の1行目に、必ず以下のいずれかの判定バッジを明記してください:
                   - 【🛑 待った（見送り）】: 前提条件（時期・価格・底固め等）が未達、または下落トレンドの途中で危険な場合
                   - 【⚠️ 条件付き慎重検討】: 価格帯には近いが、イベント通過待ちや底固め日数の確認が必要な場合
                   - 【✅ シナリオ合致（打診買い検討可）】: 時期・実勢価格・条件がすべて合致している場合（※ただし計画された比率の厳守を強く念押し）
                5. **具体的・簡潔な解説**:
                   「なぜその判定なのか」を、現在の実勢株価・本日の日付・シナリオ条件とのズレを箇条書きでスマホ向けに簡潔に説明してください。免責事項などの定型文は不要です。
                6. **次の一手サジェスト（ユーザーが次に聞くべき質問候補の推論）**:
                   回答の最末尾に、今回の判定や状況を踏まえてユーザーが次に深掘り・確認すべき質問を2〜3個提案してください。
                   必ず以下の区切り線と形式で最終行に出力してください:
                   ---SUGGESTIONS---
                   - 質問候補1
                   - 質問候補2
                   - 質問候補3
            """.trimIndent()

            val combinedPrompt = buildString {
                append(systemInstruction)
                append("\n\n")
                if (history.isNotEmpty()) {
                    append("【直前の会話ログ】\n")
                    history.forEach { msg ->
                        val who = if (msg.role == ChatMessage.Role.USER) "ユーザー" else "Gemini"
                        append("$who: ${msg.text}\n")
                    }
                    append("\n")
                }
                append("ユーザーからの相談: $userQuestion")
            }

            val extractedText = GeminiApiClient.generateContentText(
                client = client,
                apiKey = apiKey,
                prompt = combinedPrompt,
                category = "stock",
                enableGoogleSearch = true,
            )

            val fullText = extractedText.ifBlank { "回答を生成できませんでした。" }
            val delimiter = "---SUGGESTIONS---"

            if (fullText.contains(delimiter)) {
                val splitParts = fullText.split(delimiter, limit = 2)
                val answerClean = splitParts[0].trim()
                val suggestions = splitParts[1].lines()
                    .map { line ->
                        line.trim()
                            .removePrefix("-")
                            .removePrefix("*")
                            .removePrefix("•")
                            .trim()
                    }
                    .filter { it.isNotBlank() }
                    .take(4)

                ConsultationResult(
                    answer = answerClean,
                    suggestions = suggestions,
                )
            } else {
                ConsultationResult(
                    answer = fullText,
                    suggestions = emptyList(),
                )
            }
        }
    }
}
