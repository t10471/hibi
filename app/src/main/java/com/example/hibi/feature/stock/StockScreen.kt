package com.example.hibi.feature.stock

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hibi.data.BuyStep
import com.example.hibi.data.StockScenario

@Composable
fun StockScreen(
    modifier: Modifier = Modifier,
    vm: StockViewModel = viewModel(),
) {
    val scenarios by vm.scenarios.collectAsStateWithLifecycle()
    val geminiApiKey by vm.geminiApiKey.collectAsStateWithLifecycle()
    val chatMessages by vm.chatMessages.collectAsStateWithLifecycle()
    val isChatLoading by vm.isChatLoading.collectAsStateWithLifecycle()
    val dynamicSuggestions by vm.dynamicSuggestions.collectAsStateWithLifecycle()

    var activeConsultScenario by remember { mutableStateOf<StockScenario?>(null) }
    var showApiKeyDialog by remember { mutableStateOf(false) }

    // 相談モード中の場合は、スクロール競合のない専用のチャット画面を表示
    if (activeConsultScenario != null) {
        val scenario = activeConsultScenario!!
        BackHandler { activeConsultScenario = null }

        StockConsultationView(
            modifier = modifier,
            scenario = scenario,
            messages = chatMessages,
            suggestions = dynamicSuggestions,
            isLoading = isChatLoading,
            onSendMessage = { q -> vm.sendConsultation(scenario, q) },
            onClose = { activeConsultScenario = null },
        )
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            if (scenarios.isEmpty()) {
                EmptyStockView()
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { showApiKeyDialog = true }) {
                                Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (geminiApiKey.isNullOrBlank()) "Gemini APIキーを設定" else "APIキー変更")
                            }
                        }
                    }

                    items(scenarios, key = { it.id }) { scenario ->
                        StockScenarioCard(
                            scenario = scenario,
                            onToggleStep = { stepNumber -> vm.toggleStepDone(scenario.id, stepNumber) },
                            onStartConsultation = {
                                if (geminiApiKey.isNullOrBlank()) {
                                    showApiKeyDialog = true
                                } else {
                                    vm.clearChat()
                                    activeConsultScenario = scenario
                                }
                            },
                        )
                    }
                }
            }

            if (showApiKeyDialog) {
                ApiKeyInputDialog(
                    currentKey = geminiApiKey ?: "",
                    onSave = { key ->
                        vm.saveApiKey(key)
                        showApiKeyDialog = false
                    },
                    onDismiss = { showApiKeyDialog = false },
                )
            }
        }
    }
}

/**
 * スクロール競合のない専用チャット画面（無駄な余白を削減し、情報密度と使いやすさを追求）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StockConsultationView(
    modifier: Modifier = Modifier,
    scenario: StockScenario,
    messages: List<ChatMessage>,
    suggestions: List<String> = emptyList(),
    isLoading: Boolean,
    onSendMessage: (String) -> Unit,
    onClose: () -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val uriHandler = LocalUriHandler.current

    // 新しいメッセージが来たら一番下へ自動スクロール
    LaunchedEffect(messages.size, isLoading) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val defaultQuickQuestions = listOf(
        "⚡ 今日買っていい？",
        "📉 現在株価とシナリオ条件を照合",
        "⏳ 第1回条件の達成状況チェック",
        "⚠️ リスク要因と警戒ラインは？",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // --- 銘柄コンテキスト・ヘッダー（余計な空白を排除し、情報密度高く） ---
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = scenario.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = "${scenario.symbol}:${scenario.market}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        Text(
                            text = "目標: ${scenario.targetHorizon}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    // Google Finance リンク
                    IconButton(
                        onClick = { uriHandler.openUri(scenario.googleFinanceUrl) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.TrendingUp,
                            contentDescription = "Finance",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // Gemini会話リンク
                    if (!scenario.geminiShareUrl.isNullOrBlank()) {
                        IconButton(
                            onClick = { uriHandler.openUri(scenario.geminiShareUrl) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Chat,
                                contentDescription = "Gemini",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                // 購入ステップの進捗バッジ行（横スクロール可能で常に計画を確認できる）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    scenario.steps.forEach { s ->
                        val isDone = s.isDone
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isDone) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                if (isDone) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (isDone) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = if (isDone) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "第${s.step}回 (${s.ratio}) ${s.period}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isDone) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        // --- チャット・相談ログ一覧 ---
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            // 初期状態のガイドカード（画面全体が無駄に白くならないよう配置）
            if (messages.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Filled.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "AIリスク管理・門番アドバイザー",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Google検索による本日の最新実勢株価・ニュースと、登録された【${scenario.name}】の購入条件（目標レンジ・底固め・決算発表等）を照合して、客観的・冷徹に買い時を判定します。\n感情的な飛びつき買いには明確にブレーキをかけます。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "💡 初回の質問候補（タップして相談開始）",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                defaultQuickQuestions.forEach { question ->
                                    Surface(
                                        onClick = {
                                            if (!isLoading) {
                                                onSendMessage(question)
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant,
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = question,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val lastGeminiIndex = messages.indexOfLast { it.role == ChatMessage.Role.GEMINI }

            // メッセージリスト
            itemsIndexed(messages) { index, msg ->
                val isUser = msg.role == ChatMessage.Role.USER
                if (isUser) {
                    // ユーザー発言（右寄せ、幅を必要最小限に抑えて無駄な余白をカット）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.widthIn(max = 290.dp),
                        ) {
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                } else {
                    // Gemini回答（左寄せ、カード形式、門番バッジ付き）
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "Gemini 門番アドバイザー",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "リアルタイム株価照合",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            MarkdownText(
                                markdown = msg.text,
                                textColor = MaterialTheme.colorScheme.onSurface,
                            )

                            // 最新のGemini回答末尾に「縦並び」で推奨質問を表示（Perplexity/Claudeスタイル）
                            val isLastGemini = index == lastGeminiIndex
                            if (isLastGemini && suggestions.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "次に質問すべき推奨アクション",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    suggestions.forEach { question ->
                                        Surface(
                                            onClick = {
                                                if (!isLoading) {
                                                    onSendMessage(question)
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(
                                                1.dp,
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                            ),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    text = question,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f),
                                                    lineHeight = 18.sp,
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Icon(
                                                    Icons.AutoMirrored.Filled.ArrowForward,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ローディングインジケーター
            if (isLoading) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "最新株価・ニュース・シナリオ条件を冷徹に照合中...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // --- 入力バー（すっきりコンパクトに配置） ---
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("質問・相談を入力...", style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 3,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank() && !isLoading) {
                            val textToSend = inputText.trim()
                            inputText = ""
                            onSendMessage(textToSend)
                        }
                    },
                    enabled = inputText.isNotBlank() && !isLoading,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "送信",
                        tint = if (inputText.isNotBlank() && !isLoading) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StockScenarioCard(
    scenario: StockScenario,
    onToggleStep: (Int) -> Unit,
    onStartConsultation: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ヘッダー: 銘柄情報
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
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
                Text(
                    text = scenario.targetHorizon,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 外部リンクボタン（Google Finance ＆ Gemini会話）
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

                if (!scenario.geminiShareUrl.isNullOrBlank()) {
                    OutlinedButton(
                        onClick = { uriHandler.openUri(scenario.geminiShareUrl) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Gemini会話 ↗", maxLines = 1)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ワンタイム相談ボタン
            OutlinedButton(
                onClick = onStartConsultation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("Geminiにこの銘柄の現状をワンタイム相談")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // シナリオ要約
            Text(
                text = scenario.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // 購入スケジュール一覧
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

            // 全文アコーディオン
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
private fun ApiKeyInputDialog(
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "第${step.step}回 (${step.ratio})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (step.isDone) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    )
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
            }
        }
    }
}

@Composable
private fun EmptyStockView(modifier: Modifier = Modifier) {
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
