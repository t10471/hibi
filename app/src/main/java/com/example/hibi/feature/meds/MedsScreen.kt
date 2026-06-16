package com.example.hibi.feature.meds

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hibi.data.Medication
import com.example.hibi.util.DateUtils

@Composable
fun MedsScreen(
    modifier: Modifier = Modifier,
    vm: MedsViewModel = viewModel(),
) {
    val meds by vm.meds.collectAsState()
    val selectedDate by vm.selectedDate.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    // 日付が変わっていないか復帰時に確認し、日付ストリップと選択日を今日基準に更新する
    // （アプリを開いたまま深夜を跨いだケースで「今日」がずれないように）。
    var today by remember { mutableStateOf(DateUtils.todayIso()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        today = DateUtils.todayIso()
        vm.refreshToday()
    }
    val days = remember(today) { DateUtils.recentDays(7) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            DaySelector(
                days = days,
                selected = selectedDate,
                onSelect = vm::selectDate,
            )
            Text(
                text = "${DateUtils.monthDay(selectedDate)}（${DateUtils.weekdayJa(selectedDate)}）の服用",
                fontSize = 13.sp,
                color = Color.Gray,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
            )

            if (meds.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(meds, key = { it.id }) { med ->
                        MedRow(
                            med = med,
                            taken = med.takenOn(selectedDate),
                            onToggle = { vm.toggleTaken(med, selectedDate) },
                            onDelete = { vm.deleteMed(med) },
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "薬を追加")
        }
    }

    if (showAdd) {
        AddMedDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, h, m ->
                vm.addMed(name, h, m)
                showAdd = false
            },
        )
    }
}

/** Horizontal strip of the last 7 days; tap one to review/record that day. */
@Composable
private fun DaySelector(days: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(days, key = { it }) { iso ->
            DayChip(iso = iso, selected = iso == selected, onClick = { onSelect(iso) })
        }
    }
}

@Composable
private fun DayChip(iso: String, selected: Boolean, onClick: () -> Unit) {
    val top = when (DateUtils.daysAgo(iso)) {
        0L -> "今日"
        1L -> "昨日"
        else -> DateUtils.weekdayJa(iso)
    }
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .width(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(top, fontSize = 11.sp, color = fg)
        Spacer(Modifier.height(2.dp))
        Text(DateUtils.monthDay(iso), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}

@Composable
private fun MedRow(med: Medication, taken: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (taken) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (taken) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                    contentDescription = if (taken) "服用済み" else "未服用",
                    tint = if (taken) MaterialTheme.colorScheme.primary else Color.Gray,
                    modifier = Modifier.width(36.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    med.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (taken) TextDecoration.LineThrough else null,
                )
                Text(
                    DateUtils.formatTime(med.hour, med.minute) + if (taken) "・服用済み" else "",
                    fontSize = 13.sp,
                    color = if (taken) MaterialTheme.colorScheme.primary else Color.Gray,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "削除", tint = Color.Gray)
            }
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
        Text("お薬が登録されていません", fontSize = 16.sp, color = Color.Gray)
        Spacer(Modifier.height(4.dp))
        Text("右下の「＋」から追加できます", fontSize = 13.sp, color = Color.Gray)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMedDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Int, Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val timeState = rememberTimePickerState(initialHour = 8, initialMinute = 0, is24Hour = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, timeState.hour, timeState.minute) },
                enabled = name.isNotBlank(),
            ) { Text("追加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
        title = { Text("お薬を追加") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("薬の名前") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text("飲む時間", fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                TimePicker(state = timeState)
            }
        },
    )
}
