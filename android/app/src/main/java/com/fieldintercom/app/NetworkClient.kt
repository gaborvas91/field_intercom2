package com.fieldintercom.app

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Everything rides one UDP socket -- auth, heartbeat, and audio -- so there
 * is no separate "reconnect" handshake. The phone just keeps sending; the
 * moment packets start getting through again, it's connected again, with no
 * user action.
 *
 * "Out of range" is purely a local decision: if we haven't heard *anything*
 * back from the server in CONNECTION_TIMEOUT_MS, we consider ourselves out
 * of range, even though we keep transmitting heartbeats the whole time in
 * case connectivity returns.
 *
 * All latency timestamps use SystemClock.elapsedRealtime() (monotonic,
 * unaffected by wall-clock/NTP adjustments) rather than wall-clock time.
 */
class NetworkClient(private val listener: Listener) {

    enum class ConnectionStatus { CONNECTING, CONNECTED, OUT_OF_RANGE }

    interface Listener {
        fun onStatusChanged(status: ConnectionStatus)
        /** originTimestampMs: for a normal mix this is 0; during a loopback test it's the
         *  timestamp this same phone originally stamped on the audio it just sent. */
        fun onAudioReceived(pcm: ByteArray, originTimestampMs: Long)
        fun onMuteChanged(muted: Boolean)
        fun onTextMessage(text: String)
    }

    companion object {
        private const val SERVER_PORT = 5005
        private const val HEARTBEAT_INTERVAL_MS = 1000L
        private const val CONNECTION_TIMEOUT_MS = 3000L
        private const val LOGIN_ATTEMPTS = 5
        private const val LOGIN_TIMEOUT_MS = 1000
    }

    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null

    @Volatile private var token: Int = 0
    @Volatile private var running = false
    @Volatile private var lastPacketReceivedTime = 0L
    private var lastHeartbeatSentTime = 0L
    private var seqCounter = 0
    private var currentStatus = ConnectionStatus.CONNECTING

    // Simple single-ping-in-flight tracking (fine for a manual diagnostics screen;
    // a new ping just replaces any prior pending one).
    @Volatile private var pendingPingCallback: ((Long) -> Unit)? = null

    fun login(serverHost: String, password: String, deviceName: String, onResult: (Boolean) -> Unit) {
        Thread {
            var success = false
            try {
                socket = DatagramSocket()
                socket?.soTimeout = LOGIN_TIMEOUT_MS
                serverAddress = InetAddress.getByName(serverHost)

                var attempts = 0
                while (attempts < LOGIN_ATTEMPTS && !success) {
                    attempts++
                    val request = Protocol.makeAuthRequest(password, deviceName)
                    socket?.send(DatagramPacket(request, request.size, serverAddress, SERVER_PORT))

                    val buf = ByteArray(2048)
                    val response = DatagramPacket(buf, buf.size)
                    try {
                        socket?.receive(response)
                        val header = Protocol.parseHeader(buf, response.length)
                        if (header != null && header.type == Protocol.AUTH_RESPONSE) {
                            val tok = Protocol.parseAuthResponse(buf, header.payloadOffset, response.length)
                            if (tok != null) {
                                token = tok
                                success = true
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        // no response yet, retry
                    }
                }
            } catch (e: Exception) {
                success = false
            }

            onResult(success)
            if (success) {
                running = true
                lastPacketReceivedTime = System.currentTimeMillis()
                startReceiveLoop()
            }
        }.start()
    }

    private fun startReceiveLoop() {
        Thread {
            socket?.soTimeout = 200
            setStatus(ConnectionStatus.CONNECTED)

            val buf = ByteArray(2048)
            while (running) {
                val now = System.currentTimeMillis()
                if (now - lastHeartbeatSentTime >= HEARTBEAT_INTERVAL_MS) {
                    lastHeartbeatSentTime = now
                    sendRaw(Protocol.makeHeartbeat(token))
                }

                try {
                    val response = DatagramPacket(buf, buf.size)
                    socket?.receive(response)
                    lastPacketReceivedTime = System.currentTimeMillis()

                    val header = Protocol.parseHeader(buf, response.length)
                    if (header != null) {
                        when (header.type) {
                            Protocol.AUDIO_FRAME -> {
                                val frame = Protocol.parseAudioFramePayload(buf, header.payloadOffset, response.length)
                                listener.onAudioReceived(frame.pcm, frame.originTimestampMs)
                            }
                            Protocol.PONG -> {
                                val echoedTs = Protocol.parsePongPayload(buf, header.payloadOffset)
                                val rtt = SystemClock.elapsedRealtime() - echoedTs
                                pendingPingCallback?.invoke(rtt)
                                pendingPingCallback = null
                            }
                            Protocol.MUTE_ON -> listener.onMuteChanged(true)
                            Protocol.MUTE_OFF -> listener.onMuteChanged(false)
                            Protocol.TEXT_MESSAGE -> {
                                val text = Protocol.parseTextMessagePayload(buf, header.payloadOffset, response.length)
                                listener.onTextMessage(text)
                            }
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // nothing arrived this cycle -- fine, we'll check staleness below
                } catch (e: Exception) {
                    break // socket closed
                }

                val elapsed = System.currentTimeMillis() - lastPacketReceivedTime
                val newStatus = if (elapsed > CONNECTION_TIMEOUT_MS) ConnectionStatus.OUT_OF_RANGE else ConnectionStatus.CONNECTED
                setStatus(newStatus)
            }
        }.start()
    }

    private fun setStatus(status: ConnectionStatus) {
        if (status != currentStatus) {
            currentStatus = status
            listener.onStatusChanged(status)
        }
    }

    fun sendAudioFrame(pcm: ByteArray) {
        if (!running) return
        seqCounter++
        sendRaw(Protocol.makeAudioFrame(token, seqCounter, SystemClock.elapsedRealtime(), pcm))
    }

    /** Fires callback(roundTripMs) when the PONG comes back, or never if it's lost -- callers
     *  should use their own timeout if they need one. Bypasses the audio mixing tick entirely,
     *  so this measures raw network + server overhead only, not the audio pipeline. */
    fun sendPing(callback: (Long) -> Unit) {
        if (!running) return
        pendingPingCallback = callback
        sendRaw(Protocol.makePing(token, SystemClock.elapsedRealtime()))
    }

    /** Starts the full-pipeline latency self-test: the server will stop excluding this
     *  phone's own voice from its mix, so audio sent while talking comes all the way back. */
    fun startLoopbackTest() {
        sendRaw(Protocol.makeLoopbackOn(token))
    }

    fun stopLoopbackTest() {
        sendRaw(Protocol.makeLoopbackOff(token))
    }

    private fun sendRaw(data: ByteArray) {
        try {
            socket?.send(DatagramPacket(data, data.size, serverAddress, SERVER_PORT))
        } catch (e: Exception) {
            // best-effort; heartbeat/receive loop will notice if the network is really down
        }
    }

    fun logout() {
        if (running) {
            sendRaw(Protocol.makeLogout(token))
        }
        running = false
        socket?.close()
    }
}
