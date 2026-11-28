package com.example.hibi.feature.stock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hibi.data.GeminiMonthlyUsage
import com.example.hibi.data.GeminiUsageTracker
import com.example.hibi.data.StockScenario
import com.example.hibi.ui.components.GeminiCostBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockScreen(
    modifier: Modifier = Modifier,
    vm: StockViewModel = viewModel(),
) {
    val scenarios by vm.scenarios.collectAsStateWithLifecycle()
    val knowledgeList by vm.knowledgeList.collectAsStateWithLifecycle()
    val reports by vm.reports.collectAsStateWithLifecycle()
    val automationSettings by vm.automationSettings.collectAsStateWithLifecycle()
    val currentUsage by GeminiUsageTracker.observeCurrentMonth()
        .collectAsStateWithLifecycle(initialValue = GeminiMonthlyUsage())
    val geminiApiKey by vm.geminiApiKey.collectAsStateWithLifecycle()
    val chatMessages by vm.chatMessages.collectAsStateWithLifecycle()
    val isChatLoading by vm.isChatLoading.collectAsStateWithLifecycle()
    val dynamicSuggestions by vm.dynamicSuggestions.collectAsStateWithLifecycle()

    var activeSubTab by remember { mutableIntStateOf(0) }
    var activeConsultScenario by remember { mutableStateOf<StockScenario?>(null) }
    var showApiKeyDialog by remember { mutableStateOf(false) }

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
        Column(modifier = modifier.fillMaxSize()) {
            SecondaryTabRow(selectedTabIndex = activeSubTab) {
                listOf(
                    "📊 シナリオ (${scenarios.size})",
                    "📚 ナレッジ (${knowledgeList.size})",
                    "🤖 レポート (${reports.size})",
                ).forEachIndexed { idx, label ->
                    Tab(
                        selected = activeSubTab == idx,
                        onClick = { activeSubTab = idx },
                        text = {
                            Text(
                                text = label,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                            )
                        },
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (activeSubTab) {
                    0 -> {
                        if (scenarios.isEmpty()) {
                            EmptyStockView()
                        } else {
                            var statusFilter by remember { mutableStateOf("ALL") }
                            val holdingCount = scenarios.count { it.effectiveStatus == "HOLDING" }
                            val watchCount = scenarios.count { it.effectiveStatus == "WATCH" }
                            val archivedCount = scenarios.count { it.effectiveStatus == "ARCHIVED" }
                            val visibleScenarios = remember(scenarios, statusFilter) {
                                val order = mapOf("HOLDING" to 0, "WATCH" to 1, "ARCHIVED" to 2)
                                if (statusFilter == "ALL") {
                                    scenarios.sortedBy { order[it.effectiveStatus] ?: 9 }
                                } else {
                                    scenarios.filter { it.effectiveStatus == statusFilter }
                                }
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        GeminiCostBanner(modifier = Modifier.weight(1f, fill = false))
                                        Spacer(Modifier.width(6.dp))
                                        TextButton(onClick = { showApiKeyDialog = true }) {
                                            Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = if (geminiApiKey.isNullOrBlank()) "キー設定" else "キー変更",
                                                maxLines = 1,
                                                softWrap = false,
                                                fontSize = 12.sp,
                                            )
                                        }
                                    }
                                }

                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        listOf(
                                            "ALL" to "すべて (${scenarios.size})",
                                            "HOLDING" to "💼 保有中 ($holdingCount)",
                                            "WATCH" to "👀 注目・様子見 ($watchCount)",
                                            "ARCHIVED" to "📦 見送り ($archivedCount)",
                                        ).forEach { (id, label) ->
                                            FilterChip(
                                                selected = statusFilter == id,
                                                onClick = { statusFilter = id },
                                                label = { Text(label, fontSize = 12.sp) },
                                            )
                                        }
                                    }
                                }

                                items(visibleScenarios, key = { it.id }) { scenario ->
                                    StockScenarioCard(
                                        scenario = scenario,
                                        onToggleStep = { stepNumber -> vm.toggleStepDone(scenario.id, stepNumber) },
                                        onUpdateStatus = { next -> vm.updateScenarioStatus(scenario.id, next) },
                                        onUpdateHoldingDetails = { qty, avg, note ->
                                            vm.updateHoldingDetails(scenario.id, qty, avg, note)
                                        },
                                        onDeleteScenario = { vm.deleteScenario(scenario.id) },
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
                    }

                    1 -> StockKnowledgeView(
                        knowledgeList = knowledgeList,
                        onSave = vm::saveKnowledge,
                        onTogglePin = vm::toggleKnowledgePin,
                        onDelete = vm::deleteKnowledge,
                    )

                    2 -> {
                        val activeSymbols = remember(scenarios) {
                            scenarios
                                .filter { it.effectiveStatus != "ARCHIVED" }
                                .map { it.symbol.uppercase() }
                                .toSet()
                        }
                        val holdingSymbols = remember(scenarios) {
                            scenarios
                                .filter { it.effectiveStatus == "HOLDING" }
                                .map { it.symbol.uppercase() }
                                .toSet()
                        }
                        val watchSymbols = remember(scenarios) {
                            scenarios
                                .filter { it.effectiveStatus == "WATCH" }
                                .map { it.symbol.uppercase() }
                                .toSet()
                        }
                        StockReportsView(
                            reports = reports,
                            activeSymbols = activeSymbols,
                            holdingSymbols = holdingSymbols,
                            watchSymbols = watchSymbols,
                            automationSettings = automationSettings,
                            currentUsage = currentUsage,
                            onToggleAutomation = vm::setAutomationEnabled,
                            onUpdateBudgetJpy = vm::setMonthlyBudgetJpy,
                            onToggleWatchCandidate = vm::toggleWatchCandidate,
                            onArchiveSymbol = vm::archiveBySymbol,
                            onDeleteSymbol = vm::deleteBySymbol,
                            onDelete = vm::deleteReport,
                        )
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
}
