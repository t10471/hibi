package com.example.hibi.feature.timer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hibi.data.HibiRepository
import com.example.hibi.data.TimerSettings
import com.example.hibi.util.Tone
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.ceil

enum class Phase { READY, PREP, WORK, REST, DONE }

data class RunState(
    val phase: Phase = Phase.READY,
    val currentSet: Int = 0,
    val totalSets: Int = 0,
    val remainingMs: Long = 0L,
    val totalMs: Long = 1L,
    val running: Boolean = false,
    val paused: Boolean = false,
) {
    /** 0f..1f remaining fraction for the ring. */
    val fraction: Float get() = (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    val secondsLeft: Int get() = ceil(remainingMs / 1000.0).toInt()
}

class TimerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = HibiRepository(app)
    private val tone = Tone()

    val settings: StateFlow<TimerSettings> = repo.timerSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimerSettings())

    private val _run = MutableStateFlow(RunState())
    val run: StateFlow<RunState> = _run.asStateFlow()

    private val paused = MutableStateFlow(false)
    private var job: Job? = null

    fun updateSettings(workSec: Int, restSec: Int, sets: Int) {
        viewModelScope.launch {
            repo.setTimerSettings(
                TimerSettings(
                    workSec = workSec.coerceIn(5, 600),
                    restSec = restSec.coerceIn(0, 600),
                    sets = sets.coerceIn(1, 50),
                )
            )
        }
    }

    fun start() {
        if (_run.value.running) return
        val s = settings.value
        paused.value = false
        job = viewModelScope.launch { runWorkout(s) }
    }

    fun pause() {
        if (_run.value.running) {
            paused.value = true
            _run.value = _run.value.copy(paused = true)
        }
    }

    fun resume() {
        if (_run.value.running) {
            paused.value = false
            _run.value = _run.value.copy(paused = false)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        paused.value = false
        _run.value = RunState()
    }

    private suspend fun runWorkout(s: TimerSettings) {
        runPhase(Phase.PREP, 3, 0, s.sets)
        for (set in 1..s.sets) {
            // 最終セットの運動が最後のフェーズ（休憩は最終セットでは走らない）。
            // 0 秒の合図は通常「ピーン」だが、最後だけはファンファーレに譲る。
            val isFinalPhase = set == s.sets
            runPhase(Phase.WORK, s.workSec, set, s.sets, playEndTone = !isFinalPhase)
            if (set < s.sets && s.restSec > 0) {
                runPhase(Phase.REST, s.restSec, set, s.sets)
            }
        }
        tone.fanfare()
        _run.value = RunState(phase = Phase.DONE, totalSets = s.sets)
    }

    private suspend fun runPhase(
        phase: Phase,
        seconds: Int,
        set: Int,
        totalSets: Int,
        playEndTone: Boolean = true,
    ) {
        val total = seconds * 1000L
        var remaining = total
        // フェーズ開始時の音は鳴らさない（0 秒の「ピーン」が切り替えの合図）。
        _run.value = RunState(
            phase = phase,
            currentSet = set,
            totalSets = totalSets,
            remainingMs = remaining,
            totalMs = total,
            running = true,
            paused = paused.value,
        )
        var lastBeepSec = -1
        val tick = 50L
        while (remaining > 0) {
            delay(tick)
            while (paused.value) {
                delay(50)
            }
            remaining -= tick
            if (remaining < 0) remaining = 0
            val secLeft = ceil(remaining / 1000.0).toInt()
            // 残り 3・2・1 秒で「ピッ」。
            if (secLeft in 1..3 && secLeft != lastBeepSec) {
                tone.tick()
                lastBeepSec = secLeft
            }
            _run.value = _run.value.copy(remainingMs = remaining, paused = paused.value)
        }
        // 0 秒ちょうどで「ピーン」（フェーズ切り替えの合図）。
        if (playEndTone) tone.tickFinal()
    }

    override fun onCleared() {
        job?.cancel()
        tone.release()
    }
}
