package com.example.hibi.feature.timer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hibi.ui.theme.PrepColor
import com.example.hibi.ui.theme.RestColor
import com.example.hibi.ui.theme.WorkColor

@Composable
fun TimerScreen(
    modifier: Modifier = Modifier,
    vm: TimerViewModel = viewModel(),
) {
    val run by vm.run.collectAsState()
    val settings by vm.settings.collectAsState()

    // タイマー動作中は画面を消灯させない（一時停止・完了・設定中は通常どおり消灯可）。
    val view = LocalView.current
    val keepScreenOn = run.running && !run.paused
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (run.phase) {
            Phase.READY -> ReadyControls(
                workSec = settings.workSec,
                restSec = settings.restSec,
                sets = settings.sets,
                onChange = vm::updateSettings,
                onStart = vm::start,
            )

            else -> RunningView(
                run = run,
                onPause = vm::pause,
                onResume = vm::resume,
                onStop = vm::stop,
                onRestart = vm::start,
            )
        }
    }
}

@Composable
private fun ReadyControls(
    workSec: Int,
    restSec: Int,
    sets: Int,
    onChange: (Int, Int, Int) -> Unit,
    onStart: () -> Unit,
) {
    Text("タバタ式 HIIT タイマー", fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text("運動と休憩を繰り返すインターバルタイマー", fontSize = 13.sp, color = Color.Gray)
    Spacer(Modifier.height(28.dp))

    Stepper("運動", workSec, "秒", step = 5, min = 5) { onChange(it, restSec, sets) }
    Spacer(Modifier.height(12.dp))
    Stepper("休憩", restSec, "秒", step = 5, min = 0) { onChange(workSec, it, sets) }
    Spacer(Modifier.height(12.dp))
    Stepper("セット数", sets, "回", step = 1, min = 1) { onChange(workSec, restSec, it) }

    Spacer(Modifier.height(36.dp))
    val totalSec = (workSec + restSec) * sets
    Text("合計 約 ${totalSec / 60}分${totalSec % 60}秒", fontSize = 13.sp, color = Color.Gray)
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("スタート", fontSize = 18.sp)
    }
}

@Composable
private fun RunningView(
    run: RunState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    val phaseColor = when (run.phase) {
        Phase.WORK -> WorkColor
        Phase.REST -> RestColor
        Phase.PREP -> PrepColor
        Phase.DONE -> RestColor
        Phase.READY -> PrepColor
    }
    val phaseLabel = when (run.phase) {
        Phase.WORK -> "運動"
        Phase.REST -> "休憩"
        Phase.PREP -> "準備"
        Phase.DONE -> "完了！"
        Phase.READY -> ""
    }

    val targetFraction = if (run.phase == Phase.DONE) 1f else run.fraction
    val animated by animateFloatAsState(targetValue = targetFraction, label = "ring")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            val stroke = 26.dp.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2)
            drawArc(
                color = phaseColor.copy(alpha = 0.15f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = phaseColor,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(phaseLabel, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = phaseColor)
            if (run.phase != Phase.DONE) {
                Text("${run.secondsLeft}", fontSize = 72.sp, fontWeight = FontWeight.Bold)
                if (run.phase != Phase.PREP) {
                    Text("セット ${run.currentSet} / ${run.totalSets}", fontSize = 15.sp, color = Color.Gray)
                }
            } else {
                Text("おつかれさま！", fontSize = 16.sp, color = Color.Gray)
            }
        }
    }

    Spacer(Modifier.height(36.dp))

    if (run.phase == Phase.DONE) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onStop) { Text("設定に戻る") }
            Button(onClick = onRestart) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("もう一度")
            }
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onStop, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Filled.Stop, contentDescription = "停止")
            }
            if (run.paused) {
                Button(onClick = onResume, modifier = Modifier.size(width = 140.dp, height = 64.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("再開")
                }
            } else {
                Button(onClick = onPause, modifier = Modifier.size(width = 140.dp, height = 64.dp)) {
                    Icon(Icons.Filled.Pause, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("一時停止")
                }
            }
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    unit: String,
    step: Int,
    min: Int,
    onValue: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 17.sp, modifier = Modifier.width(96.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onValue((value - step).coerceAtLeast(min)) }) {
                Icon(Icons.Filled.Remove, contentDescription = "減らす")
            }
            Text(
                "$value$unit",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(80.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            FilledTonalIconButton(onClick = { onValue(value + step) }) {
                Icon(Icons.Filled.Add, contentDescription = "増やす")
            }
        }
    }
}
