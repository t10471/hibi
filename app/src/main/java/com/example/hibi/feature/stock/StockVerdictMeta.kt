package com.example.hibi.feature.stock

import androidx.compose.ui.graphics.Color

data class VerdictUiMeta(
    val badgeLabel: String,
    val actionHeadline: String,
    val bgColor: Color,
    val fgColor: Color,
    val borderColor: Color,
)

fun verdictUiMeta(verdict: String): VerdictUiMeta {
    val code = verdict.trim().uppercase().ifBlank { "WAIT" }
    return when (code) {
        "BUY", "GO" -> VerdictUiMeta(
            badgeLabel = "🚀 買い好機 ($code)",
            actionHeadline = "🚀 AI判断: 条件合致・買い検討可 ($code)",
            bgColor = Color(0xFFDCFCE7),
            fgColor = Color(0xFF166534),
            borderColor = Color(0xFF86EFAC),
        )
        "ADD" -> VerdictUiMeta(
            badgeLabel = "➕ 買い増し可 (ADD)",
            actionHeadline = "➕ AI判断: 買い増し好機 (ADD)",
            bgColor = Color(0xFFDCFCE7),
            fgColor = Color(0xFF166534),
            borderColor = Color(0xFF86EFAC),
        )
        "HOLD" -> VerdictUiMeta(
            badgeLabel = "💼 保有継続 (HOLD)",
            actionHeadline = "💼 AI判断: 保有継続・追加買いは静観 (HOLD)",
            bgColor = Color(0xFFE0F2FE),
            fgColor = Color(0xFF0369A1),
            borderColor = Color(0xFF7DD3FC),
        )
        "TRIM", "ABORT" -> VerdictUiMeta(
            badgeLabel = "⛔ 買いNG・警戒 ($code)",
            actionHeadline = "⛔ AI判断: 新規買い禁止・縮小/撤退警戒 ($code)",
            bgColor = Color(0xFFFEE2E2),
            fgColor = Color(0xFF991B1B),
            borderColor = Color(0xFFFCA5A5),
        )
        "DROP" -> VerdictUiMeta(
            badgeLabel = "🗑️ 削除提案 (DROP)",
            actionHeadline = "🗑️ AI判断: 買わない・監視除外を検討 (DROP)",
            bgColor = Color(0xFFFEE2E2),
            fgColor = Color(0xFF991B1B),
            borderColor = Color(0xFFFCA5A5),
        )
        else -> VerdictUiMeta(
            badgeLabel = "✋ 様子見 ($code)",
            actionHeadline = "✋ AI判断: まだ買わない（様子見・条件待ち / $code）",
            bgColor = Color(0xFFFEF3C7),
            fgColor = Color(0xFF92400E),
            borderColor = Color(0xFFFDE68A),
        )
    }
}

fun verdictColors(verdict: String): Pair<Color, Color> =
    verdictUiMeta(verdict).let { it.bgColor to it.fgColor }
