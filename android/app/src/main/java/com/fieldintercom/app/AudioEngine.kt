package com.fieldintercom.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * Mic is only ever opened while the user is actively transmitting (PTT held
 * or latched on). Playback runs continuously so incoming audio is heard the
 * instant it arrives. Because a phone never captures its own speaker output
 * while listening, and the server never sends a phone its own voice back,
 * there is no feedback path to fight.
 *
 * Sidetone: while transmitting, it feels dead and unnatural to hear nothing
 * of your own voice. We feed a reduced-volume copy of the mic straight to a
 * second, independent AudioTrack -- entirely local, no network round trip --
 * so it stays essentially instantaneous. Android mixes multiple concurrent
 * AudioTracks in hardware/HAL, so this needs no manual sample mixing.
 */
class AudioEngine(private val onFrameCaptured: (ByteArray) -> Unit) {

    private var audioTrack: AudioTrack? = null
    private var sidetoneTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null

    @Volatile private var capturing = false
    private var captureThread: Thread? = null

    @Volatile var sidetoneEnabled = true
    @Volatile private var sidetoneVolume = 0.2f

    private val sampleRate = Protocol.SAMPLE_RATE
    private val frameBytes = Protocol.FRAME_BYTES

    private fun buildLowLatencyTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, frameBytes * 2)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    fun startPlayback() {
        audioTrack = buildLowLatencyTrack()
        audioTrack?.play()

        sidetoneTrack = buildLowLatencyTrack()
        sidetoneTrack?.setVolume(sidetoneVolume)
        // Not started yet -- only plays while actively transmitting (see startCapture/stopCapture).
    }

    /** Called on the network receive thread as mixed audio frames from other talkers arrive. */
    fun playFrame(pcm: ByteArray) {
        audioTrack?.write(pcm, 0, pcm.size)
    }

    /** volume: 0.0 (silent) to 1.0 (full). Controls the "everyone else" stream. */
    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }

    /** How much of your own voice you hear back while transmitting. 0.0 = off. */
    fun setSidetoneVolume(volume: Float) {
        sidetoneVolume = volume.coerceIn(0f, 1f)
        sidetoneTrack?.setVolume(sidetoneVolume)
        sidetoneEnabled = sidetoneVolume > 0f
    }

    fun startCapture() {
        if (capturing) return
        capturing = true

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, frameBytes * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        audioRecord?.startRecording()

        if (sidetoneEnabled) {
            sidetoneTrack?.play()
        }

        captureThread = Thread {
            val buffer = ByteArray(frameBytes)
            while (capturing) {
                val read = audioRecord?.read(buffer, 0, frameBytes) ?: -1
                if (read == frameBytes) {
                    onFrameCaptured(buffer.copyOf())
                    if (sidetoneEnabled) {
                        sidetoneTrack?.write(buffer, 0, read)
                    }
                }
            }
        }
        captureThread?.start()
    }

    fun stopCapture() {
        if (!capturing) return
        capturing = false
        captureThread?.join(200)
        captureThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // Stop and clear the sidetone track so it doesn't hold or replay any
        // trailing buffered audio the next time it starts.
        sidetoneTrack?.pause()
        sidetoneTrack?.flush()
    }

    fun release() {
        stopCapture()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        sidetoneTrack?.stop()
        sidetoneTrack?.release()
        sidetoneTrack = null
    }

    // ---------------- Alert tones ----------------
    // Short, self-contained one-shot sounds, independent of the main playback
    // track, so they're unmistakably different from normal intercom audio.

    /** Pleasant two-note "ta-daa" chime for an incoming text message. */
    fun playMessageChime() {
        val segments = listOf(880.0 to 120, 1175.0 to 220)
        playToneSequence(segments, AudioAttributes.USAGE_NOTIFICATION_EVENT)
    }

    /** Deliberately different and more urgent: three low, insistent buzzes,
     *  played on the alarm stream so it stands out from a normal notification. */
    fun playDisconnectAlert() {
        val segments = listOf(
            330.0 to 220, 0.0 to 90,
            330.0 to 220, 0.0 to 90,
            330.0 to 220, 0.0 to 90,
        )
        playToneSequence(segments, AudioAttributes.USAGE_ALARM)
    }

    private fun playToneSequence(segments: List<Pair<Double, Int>>, usage: Int) {
        val totalSamples = segments.sumOf { (_, ms) -> sampleRate * ms / 1000 }
        val buffer = ShortArray(totalSamples)
        var offset = 0
        for ((freq, ms) in segments) {
            val n = sampleRate * ms / 1000
            for (i in 0 until n) {
                val sample = if (freq <= 0.0) {
                    0.0
                } else {
                    // Fade the first/last few ms of each note in and out a little
                    // so notes don't click against each other.
                    val fadeLen = (sampleRate * 8 / 1000).coerceAtMost(n / 2)
                    val fade = when {
                        i < fadeLen -> i.toDouble() / fadeLen
                        i > n - fadeLen -> (n - i).toDouble() / fadeLen
                        else -> 1.0
                    }
                    sin(2.0 * Math.PI * freq * i / sampleRate) * fade * 0.6
                }
                buffer[offset + i] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
            offset += n
        }

        val bytes = ByteArray(buffer.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
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
            .setBufferSizeInBytes(bytes.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(bytes, 0, bytes.size)
        track.play()

        val durationMs = segments.sumOf { it.second } + 200L
        Thread {
            Thread.sleep(durationMs)
            try {
                track.stop()
                track.release()
            } catch (e: Exception) {
                // already released/stopped -- fine
            }
        }.start()
    }
}
