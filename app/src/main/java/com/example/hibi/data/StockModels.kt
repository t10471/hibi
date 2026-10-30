package com.example.hibi.data

import kotlinx.serialization.Serializable

/**
 * 買い時ステップ情報
 */
@Serializable
data class BuyStep(
    val step: Int,
    val period: String,       // 例: "2026年11月〜12月中旬"
    val notifyDate: String,   // 例: "2026-11-01" (YYYY-MM-DD)
    val ratio: String,        // 例: "30%"
    val aim: String,          // 例: "年末の節税売りで60ドル前後の底固め"
    val condition: String,    // 例: "数週間連続で60〜62ドルを割り込まない底固めの動きを確認"
    val isNotified: Boolean = false,
    val isDone: Boolean = false, // 買ったかどうかのチェック
)

/**
 * 投資シナリオ全体
 */
@Serializable
data class StockScenario(
    val id: String,
    val symbol: String,              // 例: "NFLX", "7203"
    val market: String = "NASDAQ",   // 例: "NASDAQ", "TYO", "NYSE"
    val name: String,                // 例: "Netflix", "トヨタ自動車"
    val targetHorizon: String,       // 例: "85〜100ドル（2〜3年中長期）"
    val summary: String,             // 概要
    val geminiShareUrl: String? = null, // 当時のGemini会話共有URL
    val steps: List<BuyStep> = emptyList(),
    val fullText: String = "",       // 壁打ち全文
    val createdAt: String = "",
) {
    /** Google Finance の該当銘柄ページへの直リンク */
    val googleFinanceUrl: String
        get() = "https://www.google.com/finance/quote/$symbol:$market"
}
