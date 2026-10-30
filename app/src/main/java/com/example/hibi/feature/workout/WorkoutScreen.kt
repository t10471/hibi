package com.example.hibi.feature.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hibi.data.ExerciseLogItem
import com.example.hibi.data.WorkoutLog
import com.example.hibi.data.WorkoutMenu
import com.example.hibi.data.WorkoutSetResult
import com.example.hibi.feature.stock.MarkdownText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(
    modifier: Modifier = Modifier,
    vm: WorkoutViewModel = viewModel(),
) {
    val menus by vm.menus.collectAsState()
    val logs by vm.logs.collectAsState()
    val selectedMenu by vm.selectedMenu.collectAsState()
    val exerciseLogs by vm.exerciseLogs.collectAsState()
    val userNotes by vm.userNotes.collectAsState()
    val isConsulting by vm.isConsulting.collectAsState()
    val consultationResult by vm.consultationResult.collectAsState()
    val statusMessage by vm.statusMessage.collectAsState()
    val geminiApiKey by vm.geminiApiKey.collectAsState()
    val durationMinutes by vm.durationMinutes.collectAsState()
    val caloriesBurned by vm.caloriesBurned.collectAsState()
    val avgHeartRate by vm.avgHeartRate.collectAsState()
    val maxHeartRate by vm.maxHeartRate.collectAsState()
    val isSyncingHealth by vm.isSyncingHealth.collectAsState()
    val lastSyncedExerciseTitle by vm.lastSyncedExerciseTitle.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val permissionsLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(vm.healthConnectManager.permissions)) {
            vm.syncFromHealthConnect()
        }
    }

    val onRequestHealthSync: () -> Unit = {
        coroutineScope.launch {
            if (vm.healthConnectManager.hasAllPermissions()) {
                vm.syncFromHealthConnect()
            } else {
                permissionsLauncher.launch(vm.healthConnectManager.permissions)
            }
        }
    }

    var activeSubTab by remember { mutableIntStateOf(0) } // 0: 記録, 1: Gemini分析, 2: 履歴
    var showApiKeyDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // メニューセレクター (Firestoreから同期)
        if (menus.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Web画面からスプレッドシートを取り込むと、ここに筋トレメニューが表示されます。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.addSampleMenu() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("＋ サンプルメニューを追加して試す", fontSize = 12.sp)
                    }
                }
            }
        } else {
            val todayDay = remember {
                SimpleDateFormat("E", Locale.JAPAN).format(Date()).substring(0, 1)
            }
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(menus) { menu ->
                    val dayName = when {
                        menu.dayOfWeek.isNotBlank() -> "${menu.dayOfWeek}曜"
                        menu.title.contains("曜") -> menu.title.substringBefore("曜") + "曜"
                        else -> menu.title
                    }
                    val isToday = menu.dayOfWeek == todayDay || menu.title.contains(todayDay)
                    FilterChip(
                        selected = selectedMenu?.id == menu.id,
                        onClick = { vm.selectMenu(menu) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = dayName,
                                    fontWeight = if (selectedMenu?.id == menu.id) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp
                                )
                                if (isToday) {
                                    Spacer(Modifier.width(4.dp))
                                    Surface(
                                        color = if (selectedMenu?.id == menu.id) MaterialTheme.colorScheme.primary else Color.Gray,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            "今日",
                                            fontSize = 9.sp,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }

            selectedMenu?.let { sm ->
                val displayTitle = if (sm.title.contains("(") || sm.targetMuscle.isEmpty()) {
                    sm.title
                } else {
                    "${sm.title} (${sm.targetMuscle})"
                }
                Text(
                    text = displayTitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
        }

        // サブタブ
        SecondaryTabRow(
            selectedTabIndex = activeSubTab,
            modifier = Modifier.fillMaxWidth()
        ) {
            Tab(
                selected = activeSubTab == 0,
                onClick = { activeSubTab = 0 },
                text = { Text("📝 実績記録") }
            )
            Tab(
                selected = activeSubTab == 1,
                onClick = { activeSubTab = 1 },
                text = { Text("🤖 Gemini分析") }
            )
            Tab(
                selected = activeSubTab == 2,
                onClick = { activeSubTab = 2 },
                text = { Text("📜 履歴") }
            )
        }

        if (statusMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable {
                        if (statusMessage?.contains("APIキー") == true) {
                            showApiKeyDialog = true
                        }
                    }
                    .padding(8.dp)
            ) {
                Text(
                    text = if (statusMessage?.contains("APIキー") == true) "$statusMessage (タップして設定)" else statusMessage!!,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        when (activeSubTab) {
            0 -> WorkoutLoggingView(
                selectedMenu = selectedMenu,
                exerciseLogs = exerciseLogs,
                durationMinutes = durationMinutes,
                caloriesBurned = caloriesBurned,
                avgHeartRate = avgHeartRate,
                maxHeartRate = maxHeartRate,
                isSyncingHealth = isSyncingHealth,
                lastSyncedExerciseTitle = lastSyncedExerciseTitle,
                onRequestHealthSync = onRequestHealthSync,
                onOpenHealthConnect = vm::openHealthConnect,
                userNotes = userNotes,
                isConsulting = isConsulting,
                onUpdateExerciseWeight = vm::updateExerciseWeight,
                onUpdateExerciseReps = vm::updateExerciseReps,
                onToggleExerciseCompleted = vm::toggleExerciseCompleted,
                onUpdateSet = vm::updateSet,
                onAddSet = vm::addSet,
                onRemoveSet = vm::removeSet,
                onUpdateDuration = vm::setDurationMinutes,
                onUpdateCalories = vm::setCaloriesBurned,
                onUpdateAvgHr = vm::setAvgHeartRate,
                onUpdateMaxHr = vm::setMaxHeartRate,
                onUpdateNotes = vm::updateUserNotes,
                onConsultGemini = {
                    if (geminiApiKey.isNullOrBlank()) {
                        showApiKeyDialog = true
                    } else {
                        vm.consultGemini()
                        activeSubTab = 1
                    }
                },
                onSaveLog = vm::saveLog,
            )

            1 -> WorkoutGeminiView(
                selectedMenu = selectedMenu,
                consultationResult = consultationResult,
                isConsulting = isConsulting,
                onConsult = { question ->
                    if (geminiApiKey.isNullOrBlank()) {
                        showApiKeyDialog = true
                    } else {
                        vm.consultGemini(question)
                    }
                },
                onSaveLog = vm::saveLog,
            )

            2 -> WorkoutHistoryView(logs = logs)
        }

        if (showApiKeyDialog) {
            WorkoutApiKeyDialog(
                currentKey = geminiApiKey.orEmpty(),
                onSave = { key ->
                    vm.saveGeminiApiKey(key)
                    showApiKeyDialog = false
                },
                onDismiss = { showApiKeyDialog = false },
            )
        }
    }
}

@Composable
private fun WorkoutApiKeyDialog(
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
                    text = "Google AI Studio (aistudio.google.com) で無料発行した Gemini API キーを入力してください。端末内に安全に保存され、株・筋トレ共通で利用されます。",
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
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun WorkoutLoggingView(
    selectedMenu: WorkoutMenu?,
    exerciseLogs: List<ExerciseLogItem>,
    durationMinutes: Int,
    caloriesBurned: Int,
    avgHeartRate: Int,
    maxHeartRate: Int,
    isSyncingHealth: Boolean,
    lastSyncedExerciseTitle: String?,
    onRequestHealthSync: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    userNotes: String,
    isConsulting: Boolean,
    onUpdateExerciseWeight: (Int, Double) -> Unit,
    onUpdateExerciseReps: (Int, Int) -> Unit,
    onToggleExerciseCompleted: (Int) -> Unit,
    onUpdateSet: (Int, Int, Double, Int, Double, Boolean) -> Unit,
    onAddSet: (Int) -> Unit,
    onRemoveSet: (Int, Int) -> Unit,
    onUpdateDuration: (Int) -> Unit,
    onUpdateCalories: (Int) -> Unit,
    onUpdateAvgHr: (Int) -> Unit,
    onUpdateMaxHr: (Int) -> Unit,
    onUpdateNotes: (String) -> Unit,
    onConsultGemini: () -> Unit,
    onSaveLog: () -> Unit,
) {
    if (selectedMenu == null || exerciseLogs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("メニューを選択してください", color = Color.Gray)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Google ヘルスケア (Fitbit) 連携カード
        item {
            GoogleHealthMetricsCard(
                durationMinutes = durationMinutes,
                caloriesBurned = caloriesBurned,
                avgHeartRate = avgHeartRate,
                maxHeartRate = maxHeartRate,
                isSyncingHealth = isSyncingHealth,
                lastSyncedExerciseTitle = lastSyncedExerciseTitle,
                onRequestHealthSync = onRequestHealthSync,
                onOpenHealthConnect = onOpenHealthConnect,
                onUpdateDuration = onUpdateDuration,
                onUpdateCalories = onUpdateCalories,
                onUpdateAvgHr = onUpdateAvgHr,
                onUpdateMaxHr = onUpdateMaxHr,
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "本日の種目 (${exerciseLogs.size}種目)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = "※重量・回数は直接調整可能",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }

        itemsIndexed(exerciseLogs) { exIdx, ex ->
            ExerciseLogCard(
                exIndex = exIdx,
                exercise = ex,
                onUpdateWeight = { delta -> onUpdateExerciseWeight(exIdx, delta) },
                onUpdateReps = { delta -> onUpdateExerciseReps(exIdx, delta) },
                onToggleCompleted = { onToggleExerciseCompleted(exIdx) },
                onUpdateSet = { setIdx, w, r, rpe, comp ->
                    onUpdateSet(exIdx, setIdx, w, r, rpe, comp)
                },
                onAddSet = { onAddSet(exIdx) },
                onRemoveSet = { setIdx -> onRemoveSet(exIdx, setIdx) },
            )
        }

        item {
            OutlinedTextField(
                value = userNotes,
                onValueChange = onUpdateNotes,
                label = { Text("体感メモ・反省点 (Geminiへの相談材料)") },
                placeholder = { Text("例: 3セット目も余力あり。スジの違和感なし。") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onConsultGemini,
                    enabled = !isConsulting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (isConsulting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("分析＆保存中...")
                    } else {
                        Icon(Icons.Filled.Psychology, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Geminiに相談して記録保存", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = onSaveLog) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Gray)
                        Spacer(Modifier.width(4.dp))
                        Text("相談せずに保存のみ", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun GoogleHealthMetricsCard(
    durationMinutes: Int,
    caloriesBurned: Int,
    avgHeartRate: Int,
    maxHeartRate: Int,
    isSyncingHealth: Boolean,
    lastSyncedExerciseTitle: String?,
    onRequestHealthSync: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onUpdateDuration: (Int) -> Unit,
    onUpdateCalories: (Int) -> Unit,
    onUpdateAvgHr: (Int) -> Unit,
    onUpdateMaxHr: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⌚", fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Google ヘルスケア (Fitbit)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilledTonalButton(
                        onClick = onRequestHealthSync,
                        enabled = !isSyncingHealth,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            if (isSyncingHealth) "同期中..." else "🔄 再同期",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenHealthConnect,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            "⚙️ ヘルスコネクト",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (lastSyncedExerciseTitle != null) {
                    Text(
                        text = "✓ Fitbit: $lastSyncedExerciseTitle",
                        fontSize = 11.sp,
                        color = Color(0xFF1B5E20),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                } else {
                    Text(
                        text = "未同期（手動入力も可能）",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                Spacer(Modifier.width(8.dp))

                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        "目標: 70分 / 400kcal",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricMiniStepper(
                    label = "運動時間",
                    value = "${durationMinutes}分",
                    onMinus = { onUpdateDuration((durationMinutes - 5).coerceAtLeast(0)) },
                    onPlus = { onUpdateDuration(durationMinutes + 5) }
                )

                MetricMiniStepper(
                    label = "消費カロリー",
                    value = "${caloriesBurned}kcal",
                    onMinus = { onUpdateCalories((caloriesBurned - 20).coerceAtLeast(0)) },
                    onPlus = { onUpdateCalories(caloriesBurned + 20) }
                )

                MetricMiniStepper(
                    label = "平均心拍数",
                    value = "${avgHeartRate}bpm",
                    onMinus = { onUpdateAvgHr((avgHeartRate - 5).coerceAtLeast(0)) },
                    onPlus = { onUpdateAvgHr(avgHeartRate + 5) }
                )
            }
        }
    }
}

@Composable
private fun MetricMiniStepper(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 10.sp, color = Color.Gray)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMinus, modifier = Modifier.size(22.dp)) {
                Icon(Icons.Filled.Remove, contentDescription = null, modifier = Modifier.size(12.dp))
            }
            IconButton(onClick = onPlus, modifier = Modifier.size(22.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(12.dp))
            }
        }
    }
}

private fun formatTargetReps(repsStr: String, isBilateral: Boolean): String {
    val clean = repsStr.replace("左右", "").replace("回", "").trim()
    return if (isBilateral) "左右各${clean}回" else "${clean}回"
}

@Composable
private fun ExerciseLogCard(
    exIndex: Int,
    exercise: ExerciseLogItem,
    onUpdateWeight: (delta: Double) -> Unit,
    onUpdateReps: (delta: Int) -> Unit,
    onToggleCompleted: () -> Unit,
    onUpdateSet: (setIdx: Int, weight: Double, reps: Int, rpe: Double, completed: Boolean) -> Unit,
    onAddSet: () -> Unit,
    onRemoveSet: (setIdx: Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val firstSet = exercise.sets.firstOrNull()
    val currentWeight = firstSet?.weight ?: exercise.targetWeight
    val currentReps = firstSet?.reps ?: 10
    val allCompleted = exercise.sets.all { it.isCompleted }
    val isBilateral = exercise.targetReps.contains("左右") || exercise.exerciseName.contains("ワンハンド") || exercise.exerciseName.contains("片手")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 種目ヘッダー
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${exIndex + 1}. ${exercise.exerciseName}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                exercise.targetMuscle,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                        if (isBilateral) {
                            Spacer(Modifier.width(4.dp))
                            Surface(
                                color = Color(0xFFFEF3C7),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    "左右各",
                                    fontSize = 10.sp,
                                    color = Color(0xFFB45309),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "予定: ${exercise.targetWeight}kg × ${formatTargetReps(exercise.targetReps, isBilateral)} × ${exercise.targetSets}set",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }

                // 完遂チェックボタン
                IconButton(
                    onClick = { onToggleCompleted() },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (allCompleted) Color(0xFFE8F5E9) else Color(0xFFF1F5F9))
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = if (allCompleted) "完了" else "未完了",
                        tint = if (allCompleted) Color(0xFF2E7D32) else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // クイック調整バー (重量 & 回数)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 重量調整
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("重量", fontSize = 11.sp, color = Color.Gray)
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { onUpdateWeight(-1.0) },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                    val weightStr = if (currentWeight % 1.0 == 0.0) "${currentWeight.toInt()}kg" else "${currentWeight}kg"
                    Text(weightStr, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    IconButton(
                        onClick = { onUpdateWeight(1.0) },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                }

                // 回数調整
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("回数", fontSize = 11.sp, color = Color.Gray)
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { onUpdateReps(-1) },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                    Text(
                        text = if (isBilateral) "${currentReps}回 (左右各)" else "${currentReps}回",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = { onUpdateReps(1) },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                }

                // セット数
                Text(
                    text = "${exercise.sets.size}set",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // セット別詳細展開ボタン
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(top = 6.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (expanded) "▲ セット別詳細を閉じる" else "▼ 各セットの詳細・微調整 (${exercise.sets.size}セット)",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
                if (expanded) {
                    TextButton(onClick = onAddSet) {
                        Text("+ Set追加", fontSize = 11.sp)
                    }
                }
            }

            if (expanded) {
                Spacer(Modifier.height(4.dp))
                exercise.sets.forEachIndexed { setIdx, set ->
                    SetRowItem(
                        set = set,
                        canDelete = exercise.sets.size > 1,
                        onUpdate = { w, r, rpe, comp ->
                            onUpdateSet(setIdx, w, r, rpe, comp)
                        },
                        onDelete = { onRemoveSet(setIdx) }
                    )
                    if (setIdx < exercise.sets.size - 1) {
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SetRowItem(
    set: WorkoutSetResult,
    canDelete: Boolean,
    onUpdate: (weight: Double, reps: Int, rpe: Double, completed: Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("S${set.setNum}", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.Gray)

        // 重量 Stepper
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onUpdate((set.weight - 2.5).coerceAtLeast(0.0), set.reps, set.rpe, set.isCompleted) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Filled.Remove, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            val weightStr = if (set.weight % 1.0 == 0.0) "${set.weight.toInt()}kg" else "${set.weight}kg"
            Text(weightStr, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            IconButton(
                onClick = { onUpdate(set.weight + 2.5, set.reps, set.rpe, set.isCompleted) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        }

        // 回数 Stepper
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onUpdate(set.weight, (set.reps - 1).coerceAtLeast(0), set.rpe, set.isCompleted) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Filled.Remove, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text("${set.reps}回", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            IconButton(
                onClick = { onUpdate(set.weight, set.reps + 1, set.rpe, set.isCompleted) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        }

        // RPE Chip
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFF1F5F9))
                .clickable {
                    val nextRpe = when (set.rpe) {
                        7.0 -> 8.0
                        8.0 -> 8.5
                        8.5 -> 9.0
                        9.0 -> 10.0
                        else -> 7.0
                    }
                    onUpdate(set.weight, set.reps, nextRpe, set.isCompleted)
                }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text("RPE ${set.rpe}", fontSize = 11.sp, color = Color(0xFF334155))
        }

        Spacer(Modifier.weight(1f))

        // 完遂チェック
        IconButton(
            onClick = { onUpdate(set.weight, set.reps, set.rpe, !set.isCompleted) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = if (set.isCompleted) Color(0xFF16A34A) else Color.LightGray
            )
        }

        if (canDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Gray)
            }
        }
    }
}

@Composable
private fun TextButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

@Composable
private fun WorkoutGeminiView(
    selectedMenu: WorkoutMenu?,
    consultationResult: WorkoutConsultationResult?,
    isConsulting: Boolean,
    onConsult: (String?) -> Unit,
    onSaveLog: () -> Unit,
) {
    var followUpInput by remember { mutableStateOf("") }

    if (consultationResult == null && !isConsulting) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.FitnessCenter, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                Spacer(Modifier.height(12.dp))
                Text("「実績記録」タブからGeminiに分析を依頼してください", color = Color.Gray, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { onConsult(null) }) {
                    Text("今すぐGeminiコーチに分析を依頼")
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isConsulting) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Geminiコーチが実績データを分析中...", fontSize = 13.sp, color = Color.Gray)
                    }
                }
            }
        }

        if (consultationResult != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🏋️‍♂️", fontSize = 20.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Gemini パーソナルコーチ",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        HorizontalDivider(Modifier.padding(vertical = 10.dp))

                        MarkdownText(
                            markdown = consultationResult.answer,
                            textColor = MaterialTheme.colorScheme.onSurface,
                        )

                        // 質問サジェスト (Option 1: 垂直カード)
                        if (consultationResult.suggestions.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "💡 続けて質問する:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                            Spacer(Modifier.height(8.dp))
                            consultationResult.suggestions.forEach { sug ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFF8FAFC))
                                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                                        .clickable { onConsult(sug) }
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = "💬 $sug",
                                        fontSize = 13.sp,
                                        color = Color(0xFF1D4ED8),
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 保存ボタン
            item {
                OutlinedButton(
                    onClick = onSaveLog,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("この実績とGemini分析をFirestoreに保存", fontWeight = FontWeight.Bold)
                }
            }

            // 追質問入力行
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = followUpInput,
                        onValueChange = { followUpInput = it },
                        placeholder = { Text("質問を追加 (例: 重量アップ時の注意点は？)", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (followUpInput.isNotBlank()) {
                                onConsult(followUpInput)
                                followUpInput = ""
                            }
                        },
                        enabled = followUpInput.isNotBlank() && !isConsulting
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "送信", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}

@Composable
private fun WorkoutHistoryView(logs: List<WorkoutLog>) {
    if (logs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("実施ログがまだありません", color = Color.Gray)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(logs) { log ->
            var expanded by remember { mutableStateOf(false) }
            val totalVolume = log.exercises.sumOf { ex ->
                ex.sets.filter { it.isCompleted }.sumOf { (it.weight * it.reps).toInt() }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(log.date, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "総ボリューム: ${totalVolume}kg",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Text(
                        log.menuTitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    if (log.userNotes.isNotBlank()) {
                        Text("💭 ${log.userNotes}", fontSize = 12.sp, color = Color.Gray)
                    }

                    if (expanded) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))

                        log.exercises.forEach { ex ->
                            Text(
                                "• ${ex.exerciseName}: " + ex.sets.joinToString(", ") { "${it.weight}kg×${it.reps}" },
                                fontSize = 12.sp,
                                color = Color(0xFF334155)
                            )
                        }

                        if (!log.geminiFeedback.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFF0FDF4), RoundedCornerShape(6.dp))
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text("🤖 当時のGeminiアドバイス:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF166534))
                                    Spacer(Modifier.height(4.dp))
                                    MarkdownText(markdown = log.geminiFeedback)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
