package com.fieldintercom.app

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Mirrors server/protocol.py exactly. If you change one, change the other.
 *
 * Header layout (5 bytes): [type: 1 byte][token: 4 bytes big-endian] then payload.
 */
object Protocol {
    const val AUTH_REQUEST: Byte = 0x01
    const val AUTH_RESPONSE: Byte = 0x02
    const val HEARTBEAT: Byte = 0x03
    const val HEARTBEAT_ACK: Byte = 0x04
    const val AUDIO_FRAME: Byte = 0x05
    const val LOGOUT: Byte = 0x06
    const val PING: Byte = 0x07
    const val PONG: Byte = 0x08
    const val LOOPBACK_ON: Byte = 0x09
    const val LOOPBACK_OFF: Byte = 0x0A
    const val MUTE_ON: Byte = 0x0B
    const val MUTE_OFF: Byte = 0x0C
    const val TEXT_MESSAGE: Byte = 0x0D

    const val HEADER_SIZE = 5

    // Must match server/config.py
    const val SAMPLE_RATE = 16000
    const val FRAME_MS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000 // 320
    const val FRAME_BYTES = SAMPLES_PER_FRAME * 2 // 16-bit PCM

    data class Header(val type: Byte, val token: Int, val payloadOffset: Int)

    fun parseHeader(data: ByteArray, length: Int): Header? {
        if (length < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(data, 0, HEADER_SIZE)
        val type = buf.get()
        val token = buf.int
        return Header(type, token, HEADER_SIZE)
    }

    fun makeAuthRequest(password: String, deviceName: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(AUTH_REQUEST.toInt())
        out.write(intToBytes(0))
        out.write(password.toByteArray(StandardCharsets.UTF_8))
        out.write(0) // null separator
        out.write(deviceName.toByteArray(StandardCharsets.UTF_8))
        return out.toByteArray()
    }

    fun makeHeartbeat(token: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(HEARTBEAT.toInt())
        out.write(intToBytes(token))
        return out.toByteArray()
    }

    /** originTimestampMs: sender's local clock at capture time (elapsedRealtime-based). */
    fun makeAudioFrame(token: Int, seq: Int, originTimestampMs: Long, pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(AUDIO_FRAME.toInt())
        out.write(intToBytes(token))
        out.write(intToBytes(seq))
        out.write(longToBytes(originTimestampMs))
        out.write(pcm)
        return out.toByteArray()
    }

    fun makeLogout(token: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(LOGOUT.toInt())
        out.write(intToBytes(token))
        return out.toByteArray()
    }

    fun makePing(token: Int, timestampMs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PING.toInt())
        out.write(intToBytes(token))
        out.write(longToBytes(timestampMs))
        return out.toByteArray()
    }

    fun makeLoopbackOn(token: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(LOOPBACK_ON.toInt())
        out.write(intToBytes(token))
        return out.toByteArray()
    }

    fun makeLoopbackOff(token: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(LOOPBACK_OFF.toInt())
        out.write(intToBytes(token))
        return out.toByteArray()
    }

    /** Parses an AUTH_RESPONSE payload (bytes after the 5-byte header). Returns token, or null on failure. */
    fun parseAuthResponse(data: ByteArray, offset: Int, length: Int): Int? {
        if (length - offset < 1) return null
        val ok = data[offset].toInt() != 0
        if (!ok) return null
        if (length - offset < 5) return null
        return ByteBuffer.wrap(data, offset + 1, 4).int
    }

    data class AudioFramePayload(val seq: Int, val originTimestampMs: Long, val pcm: ByteArray)

    /** Parses an AUDIO_FRAME payload (bytes after the 5-byte header). */
    fun parseAudioFramePayload(data: ByteArray, offset: Int, length: Int): AudioFramePayload {
        val seq = ByteBuffer.wrap(data, offset, 4).int
        val originTs = ByteBuffer.wrap(data, offset + 4, 8).long
        val pcmStart = offset + 12
        val pcmLen = length - pcmStart
        return AudioFramePayload(seq, originTs, data.copyOfRange(pcmStart, pcmStart + pcmLen))
    }

    /** Parses a PONG payload (bytes after the 5-byte header). Returns the echoed timestamp. */
    fun parsePongPayload(data: ByteArray, offset: Int): Long {
        return ByteBuffer.wrap(data, offset, 8).long
    }

    /** Parses a TEXT_MESSAGE payload (bytes after the 5-byte header) as UTF-8 text. */
    fun parseTextMessagePayload(data: ByteArray, offset: Int, length: Int): String {
        return String(data, offset, length - offset, StandardCharsets.UTF_8)
    }

    private fun intToBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).putInt(value).array()
    }

    private fun longToBytes(value: Long): ByteArray {
        return ByteBuffer.allocate(8).putLong(value).array()
    }
}
