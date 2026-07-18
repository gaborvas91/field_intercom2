package com.fieldintercom.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

class IntercomService : Service() {

    interface StatusListener {
        fun onStatusChanged(status: NetworkClient.ConnectionStatus)
    }

    /** Optional listener the latency test screen attaches while it's open. */
    interface DiagnosticsListener {
        /** Called whenever an audio frame arrives carrying a non-zero origin timestamp --
         *  i.e. during an active loopback test, this is a frame of the phone's own voice
         *  coming back through the full pipeline. */
        fun onLoopbackFrame(originTimestampMs: Long)
    }

    interface MuteListener {
        fun onMuteChanged(muted: Boolean)
    }

    interface MessageListener {
        fun onTextMessage(text: String)
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): IntercomService = this@IntercomService
    }

    // Each of these immediately pushes current state to a newly attached
    // listener, not just future changes -- otherwise a listener that attaches
    // *after* a state change already happened (a very common race: the
    // network thread can flip to CONNECTED before an Activity has finished
    // binding) would silently miss it and show stale info until the next
    // actual change.
    var statusListener: StatusListener? = null
        set(value) {
            field = value
            value?.onStatusChanged(lastKnownStatus)
        }

    var muteListener: MuteListener? = null
        set(value) {
            field = value
            value?.onMuteChanged(isForceMuted)
        }

    var diagnosticsListener: DiagnosticsListener? = null
    var messageListener: MessageListener? = null

    private var lastKnownStatus = NetworkClient.ConnectionStatus.CONNECTING
    var isForceMuted = false
        private set

    private lateinit var networkClient: NetworkClient
    private lateinit var audioEngine: AudioEngine
    private val mainHandler = Handler(Looper.getMainLooper())

    var isTransmitting = false
        private set

    companion object {
        private const val CHANNEL_ID = "intercom_channel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()

        audioEngine = AudioEngine { pcm -> networkClient.sendAudioFrame(pcm) }

        networkClient = NetworkClient(object : NetworkClient.Listener {
            override fun onStatusChanged(status: NetworkClient.ConnectionStatus) {
                mainHandler.post {
                    val wasConnected = lastKnownStatus == NetworkClient.ConnectionStatus.CONNECTED
                    lastKnownStatus = status
                    statusListener?.onStatusChanged(status)
                    // Fire the "you've gone out of range" alert exactly once per
                    // transition, not on every subsequent status poll.
                    if (wasConnected && status == NetworkClient.ConnectionStatus.OUT_OF_RANGE) {
                        audioEngine.playDisconnectAlert()
                        vibrate(longArrayOf(0, 300, 150, 300, 150, 300))
                    }
                }
            }

            override fun onAudioReceived(pcm: ByteArray, originTimestampMs: Long) {
                audioEngine.playFrame(pcm)
                if (originTimestampMs != 0L) {
                    mainHandler.post { diagnosticsListener?.onLoopbackFrame(originTimestampMs) }
                }
            }

            override fun onMuteChanged(muted: Boolean) {
                mainHandler.post {
                    isForceMuted = muted
                    if (muted) stopTalking()
                    muteListener?.onMuteChanged(muted)
                }
            }

            override fun onTextMessage(text: String) {
                mainHandler.post {
                    audioEngine.playMessageChime()
                    vibrate(longArrayOf(0, 80, 60, 80))
                    messageListener?.onTextMessage(text)
                }
            }
        })

        audioEngine.startPlayback()
        startForegroundNotification()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun login(serverAddress: String, password: String, deviceName: String, callback: (Boolean) -> Unit) {
        networkClient.login(serverAddress, password, deviceName) { success ->
            mainHandler.post { callback(success) }
        }
    }

    fun setVolume(volume: Float) {
        audioEngine.setVolume(volume)
    }

    fun setSidetoneVolume(volume: Float) {
        audioEngine.setSidetoneVolume(volume)
    }

    /** callback(roundTripMs) is invoked on the main thread when the reply arrives. */
    fun pingTest(callback: (Long) -> Unit) {
        networkClient.sendPing { rtt -> mainHandler.post { callback(rtt) } }
    }

    fun startLoopbackTest() = networkClient.startLoopbackTest()
    fun stopLoopbackTest() = networkClient.stopLoopbackTest()

    fun startTalking() {
        if (isTransmitting || isForceMuted) return
        isTransmitting = true
        audioEngine.startCapture()
    }

    fun stopTalking() {
        if (!isTransmitting) return
        isTransmitting = false
        audioEngine.stopCapture()
    }

    fun logout() {
        stopTalking()
        networkClient.logout()
    }

    override fun onDestroy() {
        stopTalking()
        networkClient.logout()
        audioEngine.release()
        super.onDestroy()
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}
