package com.example.watchmetronome

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class MetronomeEngine(context: Context) {

    private val sampleRate = 44100
    @Volatile var beatsPerMeasure: Int = 4

    private val downbeatTrack = PooledClick(generateClick(1800, 0.06))
    private val beatTrack = PooledClick(generateClick(1200, 0.04))

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    @Volatile var bpm: Int = 120
    @Volatile var soundEnabled: Boolean = true
    @Volatile var vibrationEnabled: Boolean = false

    @Volatile private var running: Boolean = false
    private var tickThread: Thread? = null

    fun isRunning(): Boolean = running

    fun start(onBeat: (beatIndex: Int, isDownbeat: Boolean) -> Unit) {
        if (running) return
        running = true
        tickThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var beatIndex = 0
            var nextTickNs = System.nanoTime()
            while (running) {
                val isDownbeat = (beatIndex % beatsPerMeasure) == 0

                try {
                    onBeat(beatIndex % beatsPerMeasure, isDownbeat)
                } catch (e: Throwable) {
                    // UI コールバックの例外でループを止めない
                }

                if (soundEnabled) {
                    if (isDownbeat) downbeatTrack.play() else beatTrack.play()
                }
                if (vibrationEnabled) vibrate(isDownbeat)

                beatIndex++
                val intervalNs = 60_000_000_000L / bpm.coerceAtLeast(1)
                nextTickNs += intervalNs
                val now = System.nanoTime()
                val sleep = nextTickNs - now
                if (sleep <= 0L) {
                    // BPM が上がってタイミングが追いつけない場合に再同期
                    nextTickNs = now
                } else {
                    LockSupport.parkNanos(sleep)
                }
            }
        }, "MetronomeTick").also { it.start() }
    }

    fun stop() {
        running = false
        tickThread?.let { t ->
            LockSupport.unpark(t)
            t.join(200)
        }
        tickThread = null
    }

    fun release() {
        stop()
        downbeatTrack.release()
        beatTrack.release()
    }

    private fun vibrate(isDownbeat: Boolean) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val duration = if (isDownbeat) 40L else 25L
        val amplitude = if (isDownbeat) VibrationEffect.DEFAULT_AMPLITUDE else 120
        v.vibrate(VibrationEffect.createOneShot(duration, amplitude))
    }

    private fun generateClick(frequencyHz: Int, durationSec: Double): ShortArray {
        val numSamples = (sampleRate * durationSec).toInt()
        val out = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val envelope = exp(-t * 60.0)
            val sample = sin(2.0 * PI * frequencyHz * t) * envelope * 0.85
            out[i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /**
     * AudioTrack を MODE_STATIC で保持し、reloadStaticData() で繰り返し再生する。
     * pause() → reloadStaticData() → play() が static バッファの正しい再生パスで、
     * flush() は MODE_STATIC では動作しないため使わない。
     */
    private inner class PooledClick(private val samples: ShortArray) {
        private val track: AudioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        init {
            track.write(samples, 0, samples.size)
        }

        @Synchronized
        fun play() {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                track.reloadStaticData()
                track.play()
            } catch (e: IllegalStateException) {
                // トラックが解放済みか不正な状態 → 無視
            }
        }

        fun release() {
            try { track.stop() } catch (e: IllegalStateException) { /* ignore */ }
            track.release()
        }
    }
}
