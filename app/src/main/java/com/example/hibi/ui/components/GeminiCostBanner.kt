package com.example.hibi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hibi.data.GeminiMonthlyUsage
import com.example.hibi.data.GeminiUsageTracker
import java.util.Locale

/**
 * Compact clickable badge displaying the current JST month's Gemini API cost & request count
 * (synced across Android & Web, resetting automatically on the 1st of each month).
 */
@Composable
fun GeminiCostBanner(modifier: Modifier = Modifier) {
    val usage by GeminiUsageTracker.observeCurrentMonth()
        .collectAsStateWithLifecycle(initialValue = GeminiMonthlyUsage())
    var showDialog by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        GeminiUsageTracker.refreshMonth()
    }

    val monthNum = usage.month.substringAfter('-', missingDelimiterValue = usage.month)
        .toIntOrNull()?.let { "${it}月" } ?: usage.month
    val jpyText = GeminiUsageTracker.formatCostJpy(usage.estimatedCostUsd, usage.usdToJpyRate)
    val usdText = GeminiUsageTracker.formatCostUsd(usage.estimatedCostUsd)

    Surface(
        onClick = { showDialog = true },
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = Color(0xFFF5F3FF),
        border = BorderStroke(1.dp, Color(0xFFDDD6FE)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("💎", fontSize = 12.sp)
            Text(
                text = "$monthNum Gemini: 約$jpyText ($usdText) ・ ${usage.requestCount}回",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF5B21B6),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (showDialog) {
        GeminiCostDetailDialog(
            usage = usage,
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun GeminiCostDetailDialog(
    usage: GeminiMonthlyUsage,
    onDismiss: () -> Unit,
) {
    val jpyText = GeminiUsageTracker.formatCostJpy(usage.estimatedCostUsd, usage.usdToJpyRate)
    val usdText = GeminiUsageTracker.formatCostUsd(usage.estimatedCostUsd)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
        title = { Text("💎 Gemini 利用コスト (${usage.month})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F3FF)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "今月の推定コスト（毎月1日 00:00 自動リセット）",
                            fontSize = 11.sp,
                            color = Color(0xFF6D28D9),
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "約 $jpyText ($usdText)",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4C1D95),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "利用回数: 合計 ${usage.requestCount}回（アプリ ${usage.appRequests}回 / Web ${usage.webRequests}回）",
                            fontSize = 12.sp,
                            color = Color(0xFF5B21B6),
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatRow(
                        label = "💪 筋トレ相談",
                        value = "${GeminiUsageTracker.formatCostJpy(usage.workoutCostUsd, usage.usdToJpyRate)} (${usage.workoutRequests}回)",
                    )
                    StatRow(
                        label = "📈 かぶ相談",
                        value = "${GeminiUsageTracker.formatCostJpy(usage.stockCostUsd, usage.usdToJpyRate)} (${usage.stockRequests}回)",
                    )
                }

                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatRow(label = "入力トークン", value = "%,d".format(usage.promptTokens))
                    StatRow(
                        label = "出力トークン (思考含む)",
                        value = if (usage.thinkingTokens > 0) {
                            "%,d (思考 %,d)".format(usage.outputTokens, usage.thinkingTokens)
                        } else {
                            "%,d".format(usage.outputTokens)
                        },
                    )
                    if (usage.cachedTokens > 0) {
                        StatRow(label = "キャッシュ入力", value = "%,d".format(usage.cachedTokens))
                    }
                    if (usage.lastModel.isNotBlank()) {
                        StatRow(label = "直近モデル", value = usage.lastModel)
                    }
                }

                Text(
                    text = "※ 1ドル=${String.format(Locale.US, "%.1f", usage.usdToJpyRate)}円・各モデル標準従量課金レート（Gemini 4 Hydrogen: 入力 \$1.50 / 出力 \$7.50 per 1M tokens 等）で算出した概算です。無料枠キーをご利用中の場合、実際の請求額は¥0です。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                )
            }
        },
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 12.sp, color = Color.Gray)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
