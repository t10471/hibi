package com.example.hibi.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Synthesises short tones with [AudioTrack] so we control pitch precisely:
 * normal countdown blips on "3, 2, 1", a higher final blip on "0", and a little
 * fanfare when every set is finished. All playback happens on a background thread.
 */
class Tone {

    private val sampleRate = 44100
    private val thread = HandlerThread("hibi-tone").apply { start() }
    private val handler = Handler(thread.looper)

    /** In-flight tracks awaiting release. Only touched on the HandlerThread. */
    private val active = mutableSetOf<AudioTrack>()

    private data class Note(val freq: Double, val durationMs: Int, val gapMs: Int = 0)

    /** カウントダウンの「ピッ」（残り 3, 2, 1 用）。短く。 */
    fun tick() = play(listOf(Note(880.0, 95)))

    /** カウントダウン 0 秒の「ピーン」。高く・長く伸ばす（フェーズ切り替えの合図）。 */
    fun tickFinal() = play(listOf(Note(1318.5, 520)))

    /** 全セット終了の「パンパカパーン」。上行アルペジオ＋最後を伸ばす。 */
    fun fanfare() = play(
        listOf(
            Note(523.25, 120, 25),  // C5  パン
            Note(659.25, 120, 25),  // E5  パ
            Note(783.99, 120, 25),  // G5  カ
            Note(1046.50, 560),     // C6  パーン（伸ばす）
        )
    )

    private fun play(notes: List<Note>) {
        handler.post { runCatching { synthAndPlay(notes) } }
    }

    private fun synthAndPlay(notes: List<Note>) {
        val samples = synth(notes)
        if (samples.isEmpty()) return
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            samples.size * 2,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        active.add(track)
        try {
            track.write(samples, 0, samples.size)
            track.play()
        } catch (t: Throwable) {
            active.remove(track)
            runCatching { track.release() }
            return
        }
        val durationMs = samples.size * 1000L / sampleRate
        handler.postDelayed({
            runCatching { track.stop(); track.release() }
            active.remove(track)
        }, durationMs + 250)
    }

    private fun synth(notes: List<Note>): ShortArray {
        var total = 0
        for (n in notes) {
            total += sampleRate * n.durationMs / 1000
            total += sampleRate * n.gapMs / 1000
        }
        val out = ShortArray(total)
        var idx = 0
        for (n in notes) {
            val count = sampleRate * n.durationMs / 1000
            val attack = (count * 0.04).toInt().coerceAtLeast(1)
            val release = (count * 0.25).toInt().coerceAtLeast(1)
            for (i in 0 until count) {
                val env = when {
                    i < attack -> i.toDouble() / attack
                    i > count - release -> (count - i).toDouble() / release
                    else -> 1.0
                }
                val t = i.toDouble() / sampleRate
                // base tone plus a couple of harmonics for a brighter, brass-ish sound
                val w = sin(2 * PI * n.freq * t) +
                    0.35 * sin(2 * PI * 2 * n.freq * t) +
                    0.18 * sin(2 * PI * 3 * n.freq * t)
                out[idx++] = (env * (w / 1.53) * 0.55 * Short.MAX_VALUE).toInt().toShort()
            }
            repeat(sampleRate * n.gapMs / 1000) { out[idx++] = 0 }
        }
        return out
    }

    fun release() {
        // Release in-flight tracks now: a release posted via postDelayed would be
        // dropped by quitSafely() (it discards not-yet-due messages), leaking the
        // native AudioTrack. The immediate post below is "due", so it runs first.
        handler.post {
            active.forEach { runCatching { it.stop(); it.release() } }
            active.clear()
        }
        runCatching { thread.quitSafely() }
    }
}
