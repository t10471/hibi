package com.example.hibi.feature.since

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hibi.data.TrackedEvent
import com.example.hibi.util.DateUtils

@Composable
fun SinceScreen(
    modifier: Modifier = Modifier,
    vm: SinceViewModel = viewModel(),
) {
    val events by vm.events.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var historyId by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        if (events.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(events, key = { it.id }) { event ->
                    EventRow(
                        event = event,
                        onOpenHistory = { historyId = event.id },
                        onMarkToday = { vm.recordOccurrence(event, DateUtils.todayIso()) },
                        onDelete = { vm.deleteEvent(event) },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "記録を追加")
        }
    }

    if (showAdd) {
        AddEventDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, iso ->
                vm.addEvent(name, iso)
                showAdd = false
            },
        )
    }

    // Detail/history dialog. Derived from the live list so edits update immediately.
    val historyEvent = events.firstOrNull { it.id == historyId }
    if (historyEvent != null) {
        EventHistoryDialog(
            event = historyEvent,
            onDismiss = { historyId = null },
            onAddDate = { iso -> vm.recordOccurrence(historyEvent, iso) },
            onRemoveDate = { iso -> vm.removeOccurrence(historyEvent, iso) },
        )
    }
}

@Composable
private fun EventRow(
    event: TrackedEvent,
    onOpenHistory: () -> Unit,
    onMarkToday: () -> Unit,
    onDelete: () -> Unit,
) {
    val latest = event.latest
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenHistory),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(event.name, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (latest != null) DateUtils.daysAgoLabel(latest) else "記録なし",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (latest != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "(${DateUtils.formatDate(latest)})",
                            fontSize = 13.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "履歴 ${event.history.size}件 ・ タップで詳細",
                    fontSize = 11.sp,
                    color = Color.Gray,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OutlinedButton(onClick = onMarkToday, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("今日", fontSize = 13.sp)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "削除", tint = Color.Gray)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventHistoryDialog(
    event: TrackedEvent,
    onDismiss: () -> Unit,
    onAddDate: (String) -> Unit,
    onRemoveDate: (String) -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        dismissButton = { TextButton(onClick = { showDatePicker = true }) { Text("日付を追加") } },
        title = { Text(event.name) },
        text = {
            Column {
                val latest = event.latest
                Text(
                    if (latest != null) {
                        "最終: ${DateUtils.formatDate(latest)}（${DateUtils.daysAgoLabel(latest)}）"
                    } else {
                        "記録がありません"
                    },
                    fontSize = 13.sp,
                    color = Color.Gray,
                )
                Spacer(Modifier.height(12.dp))
                Text("履歴（${event.history.size}件）", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    event.history.forEach { iso ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${DateUtils.monthDay(iso)}（${DateUtils.weekdayJa(iso)}）",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                DateUtils.daysAgoLabel(iso),
                                fontSize = 13.sp,
                                color = Color.Gray,
                            )
                            IconButton(onClick = { onRemoveDate(iso) }) {
                                Icon(Icons.Filled.Close, contentDescription = "この日を削除", tint = Color.Gray)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
    )

    if (showDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = DateUtils.todayUtcMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { onAddDate(DateUtils.isoFromUtcMillis(it)) }
                    showDatePicker = false
                }) { Text("追加") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("キャンセル") } },
        ) {
            DatePicker(state = dpState)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("記録がありません", fontSize = 16.sp, color = Color.Gray)
        Spacer(Modifier.height(4.dp))
        Text("「髪を切った」などを「＋」で追加", fontSize = 13.sp, color = Color.Gray)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEventDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedMillis by remember { mutableLongStateOf(DateUtils.todayUtcMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, DateUtils.isoFromUtcMillis(selectedMillis)) },
                enabled = name.isNotBlank(),
            ) { Text("追加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
        title = { Text("記録を追加") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("したこと（例：髪を切った）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("日付：", fontSize = 14.sp)
                    Text(
                        DateUtils.formatDate(DateUtils.isoFromUtcMillis(selectedMillis)),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = { showDatePicker = true }) { Text("変更") }
                }
            }
        },
    )

    if (showDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = selectedMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { selectedMillis = it }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("キャンセル") } },
        ) {
            DatePicker(state = dpState)
        }
    }
}
