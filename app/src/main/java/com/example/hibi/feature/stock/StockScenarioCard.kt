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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hibi.data.BuyStep
import com.example.hibi.data.StockScenario

@Composable
internal fun StockScenarioCard(
    scenario: StockScenario,
    onToggleStep: (Int) -> Unit,
    onUpdateStatus: (String) -> Unit,
    onUpdateHoldingDetails: (String, String, String) -> Unit,
    onDeleteScenario: () -> Unit,
    onStartConsultation: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var isExpanded by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val effStatus = scenario.effectiveStatus

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    if (effStatus == "HOLDING") "💼 保有中シナリオを削除" else "🗑️ Watchシナリオを削除"
                )
            },
            text = {
                Text(
                    if (effStatus == "HOLDING") {
                        "保有中の「${scenario.name} (${scenario.symbol})」のシナリオを完全に削除しますか？"
                    } else {
                        "保有していない Watch 銘柄「${scenario.name} (${scenario.symbol})」を削除しますか？"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteScenario()
                        showDeleteDialog = false
                    },
                ) {
                    Text("削除する", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("キャンセル") }
            },
        )
    }

    if (showNoteDialog) {
        var qtyDraft by remember(scenario.holdingQuantity) { mutableStateOf(scenario.holdingQuantity) }
        var avgDraft by remember(scenario.averageCost) { mutableStateOf(scenario.averageCost) }
        var noteDraft by remember(scenario.holdingNote) { mutableStateOf(scenario.holdingNote) }
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("💼 ${scenario.name} 保有状況") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = qtyDraft,
                        onValueChange = { qtyDraft = it },
                        label = { Text("保有数量（任意）") },
                        placeholder = { Text("例: 10株 / 100万円分") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = avgDraft,
                        onValueChange = { avgDraft = it },
                        label = { Text("平均取得単価（任意）") },
                        placeholder = { Text("例: \$280 / ¥3,500") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = noteDraft,
                        onValueChange = { noteDraft = it },
                        label = { Text("状況・買い増し方針メモ") },
                        placeholder = { Text("例: 押し目で買い増し検討") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdateHoldingDetails(qtyDraft, avgDraft, noteDraft)
                        showNoteDialog = false
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = false }) { Text("キャンセル") }
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val verdictMeta = scenario.latestVerdict?.takeIf { it.isNotBlank() }?.let { verdictUiMeta(it) }
            val verdictReason = scenario.latestVerdictReason?.takeIf { it.isNotBlank() }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = scenario.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${scenario.symbol}:${scenario.market}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (verdictMeta != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = verdictMeta.bgColor,
                        border = BorderStroke(1.dp, verdictMeta.borderColor),
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            text = verdictMeta.badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = verdictMeta.fgColor,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                } else if (effStatus != "ARCHIVED") {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            text = "⏳ 朝AI待ち",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            if (scenario.targetHorizon.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = scenario.targetHorizon,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (verdictMeta != null || verdictReason != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = verdictMeta?.bgColor?.copy(alpha = 0.55f)
                        ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = verdictMeta?.let { BorderStroke(1.dp, it.borderColor) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (verdictMeta != null) {
                            Text(
                                text = verdictMeta.actionHeadline,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = verdictMeta.fgColor,
                            )
                        }
                        if (verdictReason != null) {
                            Text(
                                text = verdictReason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        "WATCH" to "👀 注目・様子見",
                        "HOLDING" to "💼 保有中",
                        "ARCHIVED" to "📦 見送り",
                    ).forEach { (st, label) ->
                        FilterChip(
                            selected = effStatus == st,
                            onClick = { onUpdateStatus(st) },
                            label = { Text(label, fontSize = 11.sp) },
                        )
                    }
                }
                TextButton(
                    onClick = { showDeleteDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = if (effStatus == "WATCH") "Watch削除" else "削除",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Surface(
                onClick = { showNoteDialog = true },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                val summaryLine = listOfNotNull(
                    scenario.holdingQuantity.takeIf { it.isNotBlank() }?.let { "保有: $it" },
                    scenario.averageCost.takeIf { it.isNotBlank() }?.let { "取得: $it" },
                    scenario.holdingNote.takeIf { it.isNotBlank() },
                ).joinToString(" ／ ")
                Text(
                    text = if (summaryLine.isNotBlank()) {
                        "💼 $summaryLine"
                    } else {
                        "💼 保有数・取得単価・メモをタップして設定..."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { uriHandler.openUri(scenario.googleFinanceUrl) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Finance ↗", maxLines = 1)
                }

                val safeShareUrl = scenario.geminiShareUrl?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) }
                if (safeShareUrl != null) {
                    OutlinedButton(
                        onClick = { runCatching { uriHandler.openUri(safeShareUrl) } },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Gemini会話 ↗", maxLines = 1)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onStartConsultation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("Geminiにこの銘柄の現状をワンタイム相談")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = scenario.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "購入スケジュール (${scenario.steps.count { it.isDone }}/${scenario.steps.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                scenario.steps.forEach { step ->
                    StepItemRow(step = step, onToggle = { onToggleStep(step.step) })
                }
            }

            if (scenario.fullText.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = !isExpanded }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isExpanded) "Gemini分析全文を閉じる" else "Gemini分析全文を読む",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                AnimatedVisibility(visible = isExpanded) {
                    Text(
                        text = scenario.fullText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ApiKeyInputDialog(
    currentKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf(currentKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gemini APIキー設定") },
        text = {
            Column {
                Text(
                    text = "Google AI Studio (aistudio.google.com) で無料発行した Gemini API キーを入力してください。端末内に安全に保存されます。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(key) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun StepItemRow(
    step: BuyStep,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (step.isDone) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = if (step.isDone) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (step.isDone) "完了" else "未完了",
                    tint = if (step.isDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "第${step.step}回 (${step.ratio})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (step.isDone) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                )
                if (step.period.isNotBlank()) {
                    Text(
                        text = step.period,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "狙い: ${step.aim}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "条件: ${step.condition}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (step.notifyDate.isNotBlank()) {
                    val notifyLabel = if (step.isNotified) {
                        "🔔 通知済み (${step.notifyDate})"
                    } else {
                        "🔔 通知予定: ${step.notifyDate} 08:30"
                    }
                    Text(
                        text = notifyLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (step.isNotified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
internal fun EmptyStockView(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.TrendingUp,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = "登録されたシナリオはありません",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Web入稿画面からGeminiとの壁打ち結果を登録すると、ここに買い時スケジュールが表示されます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
