package com.example.hibi.feature.stock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hibi.data.GeminiMonthlyUsage
import com.example.hibi.data.GeminiUsageTracker
import com.example.hibi.data.NewWatchCandidate
import com.example.hibi.data.StockAutomationSettings
import com.example.hibi.data.StockReport
import kotlin.math.roundToLong

@Composable
fun StockReportsView(
    reports: List<StockReport>,
    activeSymbols: Set<String>,
    automationSettings: StockAutomationSettings,
    currentUsage: GeminiMonthlyUsage,
    onToggleAutomation: (Boolean) -> Unit,
    onUpdateBudgetJpy: (Long) -> Unit,
    onAddWatchCandidate: (NewWatchCandidate) -> Unit,
    onArchiveSymbol: (String) -> Unit,
    onDeleteSymbol: (String) -> Unit,
    onDelete: (StockReport) -> Unit,
) {
    var filter by remember { mutableStateOf("all") }
    var deletingReport by remember { mutableStateOf<StockReport?>(null) }
    var deletingSymbolProposal by remember { mutableStateOf<String?>(null) }

    val filtered = remember(reports, filter) {
        if (filter == "all") reports else reports.filter { it.type == filter }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AutomationControlCard(
                settings = automationSettings,
                usage = currentUsage,
                onToggleAutomation = onToggleAutomation,
                onUpdateBudgetJpy = onUpdateBudgetJpy,
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    "all" to "すべて",
                    "daily_action" to "🎯 売買・Watch",
                    "daily_market" to "🌍 地合い・セクター",
                    "daily_gatekeeper" to "🛡️ 旧門番",
                ).forEach { (id, label) ->
                    FilterChip(
                        selected = filter == id,
                        onClick = { filter = id },
                        label = { Text(label, fontSize = 12.sp) },
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🤖", fontSize = 36.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "まだ AI レポートがありません",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "GitHub Actions の平日毎朝 07:30 実行後に\n自動でここに届きます",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        } else {
            items(filtered, key = { it.id }) { report ->
                StockReportCard(
                    report = report,
                    activeSymbols = activeSymbols,
                    initiallyExpanded = filtered.firstOrNull()?.id == report.id,
                    onAddWatchCandidate = onAddWatchCandidate,
                    onArchiveSymbol = onArchiveSymbol,
                    onRequestDeleteSymbol = { sym -> deletingSymbolProposal = sym },
                    onDelete = { deletingReport = report },
                )
            }
        }
    }

    deletingSymbolProposal?.let { sym ->
        AlertDialog(
            onDismissRequest = { deletingSymbolProposal = null },
            title = { Text("🗑️ 削除提案を承認して削除") },
            text = { Text("「$sym」のシナリオを完全に削除しますか？（履歴を残す場合は「見送り」を選べます）") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSymbol(sym)
                        deletingSymbolProposal = null
                    },
                ) {
                    Text("削除する", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSymbolProposal = null }) { Text("キャンセル") }
            },
        )
    }

    deletingReport?.let { item ->
        AlertDialog(
            onDismissRequest = { deletingReport = null },
            title = { Text("レポートを削除") },
            text = { Text("「${item.title}」を削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(item)
                        deletingReport = null
                    }
                ) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingReport = null }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun StockReportCard(
    report: StockReport,
    activeSymbols: Set<String>,
    initiallyExpanded: Boolean,
    onAddWatchCandidate: (NewWatchCandidate) -> Unit,
    onArchiveSymbol: (String) -> Unit,
    onRequestDeleteSymbol: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember(report.id) { mutableStateOf(initiallyExpanded) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = report.date,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        if (report.model.isNotBlank()) {
                            Surface(
                                color = Color(0xFFF5F3FF),
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                Text(
                                    text = report.model,
                                    fontSize = 10.sp,
                                    color = Color(0xFF6D28D9),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = report.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "削除",
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (report.verdicts.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    report.verdicts.forEach { v ->
                        val (bg, fg) = verdictColors(v.verdict)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = bg,
                            border = BorderStroke(1.dp, fg.copy(alpha = 0.3f)),
                        ) {
                            Text(
                                text = "${v.symbol}: ${v.verdict}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = fg,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            val dropProposals = remember(report.verdicts, activeSymbols) {
                report.verdicts.filter {
                    it.verdict.equals("DROP", ignoreCase = true) && activeSymbols.contains(it.symbol.uppercase())
                }
            }
            if (dropProposals.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFEF2F2),
                    border = BorderStroke(1.dp, Color(0xFFFECACA)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "🗑️ AI Watch 削除提案（最終判断は人間）",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF991B1B),
                        )
                        dropProposals.forEach { p ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.White,
                                border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = p.symbol,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF991B1B),
                                    )
                                    if (p.oneLiner.isNotBlank()) {
                                        Text(
                                            text = p.oneLiner,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Surface(
                                            onClick = { onArchiveSymbol(p.symbol) },
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFFF1F5F9),
                                            border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                        ) {
                                            Text(
                                                text = "📦 見送りにする",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF334155),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            )
                                        }
                                        Surface(
                                            onClick = { onRequestDeleteSymbol(p.symbol) },
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFFDC2626),
                                        ) {
                                            Text(
                                                text = "🗑️ 削除する",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (report.newWatchCandidates.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "🔭 地合い連動：新規 Watch 候補",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E3A8A),
                        )
                        report.newWatchCandidates.forEach { cand ->
                            val already = activeSymbols.contains(cand.symbol.uppercase())
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.White,
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${cand.name} (${cand.symbol}:${cand.market})",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        if (cand.targetHorizon.isNotBlank()) {
                                            Text(
                                                text = cand.targetHorizon,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        if (cand.reason.isNotBlank()) {
                                            Text(
                                                text = cand.reason,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.size(6.dp))
                                    Surface(
                                        onClick = { if (!already) onAddWatchCandidate(cand) },
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (already) Color(0xFFE2E8F0) else Color(0xFF2563EB),
                                    ) {
                                        Text(
                                            text = if (already) "✓ 済" else "＋ Watch",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (already) Color(0xFF64748B) else Color.White,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    MarkdownText(markdown = report.content)
                }
            }
        }
    }
}

@Composable
private fun AutomationControlCard(
    settings: StockAutomationSettings,
    usage: GeminiMonthlyUsage,
    onToggleAutomation: (Boolean) -> Unit,
    onUpdateBudgetJpy: (Long) -> Unit,
) {
    val currentJpy = (usage.estimatedCostUsd * usage.usdToJpyRate).roundToLong()
    val budgetJpy = settings.monthlyBudgetJpy
    val isOverBudget = budgetJpy > 0 && currentJpy >= budgetJpy

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (settings.enabled && !isOverBudget) {
                Color(0xFFF0FDF4)
            } else {
                Color(0xFFFEF2F2)
            },
        ),
        border = BorderStroke(
            1.dp,
            if (settings.enabled && !isOverBudget) Color(0xFFBBF7D0) else Color(0xFFFECACA),
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (!settings.enabled) {
                            "⏸️ 自動分析：停止中"
                        } else if (isOverBudget) {
                            "🛑 自動分析：月額上限により停止中"
                        } else {
                            "🤖 自動分析：稼働中"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (settings.enabled && !isOverBudget) Color(0xFF166534) else Color(0xFF991B1B),
                    )
                    Text(
                        text = "平日07:30 JST (地合い→保有買い増し・Watch入替)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = onToggleAutomation,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "今月 (${usage.month}) 推計: ${GeminiUsageTracker.formatCostJpy(usage.estimatedCostUsd, usage.usdToJpyRate)} (${GeminiUsageTracker.formatCostUsd(usage.estimatedCostUsd)})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (budgetJpy > 0) "上限: ¥$budgetJpy" else "上限なし",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (budgetJpy > 0) {
                    val progress = (currentJpy.toFloat() / budgetJpy.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (progress >= 0.9f) Color(0xFFDC2626) else Color(0xFF2563EB),
                    )
                }
                if (usage.batchRequests > 0) {
                    Text(
                        text = "うち定期バッチ: ${GeminiUsageTracker.formatCostJpy(usage.batchCostUsd, usage.usdToJpyRate)} (${usage.batchRequests}コール)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("自動停止上限:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(
                    500L to "¥500",
                    1000L to "¥1,000",
                    2000L to "¥2,000",
                    0L to "解除",
                ).forEach { (cap, label) ->
                    FilterChip(
                        selected = budgetJpy == cap,
                        onClick = { onUpdateBudgetJpy(cap) },
                        label = { Text(label, fontSize = 11.sp) },
                    )
                }
            }
        }
    }
}

fun verdictColors(verdict: String): Pair<Color, Color> = when (verdict.uppercase()) {
    "BUY", "ADD", "GO" -> Color(0xFFDCFCE7) to Color(0xFF166534)
    "HOLD" -> Color(0xFFE0F2FE) to Color(0xFF0369A1)
    "TRIM", "DROP", "ABORT" -> Color(0xFFFEE2E2) to Color(0xFF991B1B)
    else -> Color(0xFFFEF3C7) to Color(0xFF92400E)
}
