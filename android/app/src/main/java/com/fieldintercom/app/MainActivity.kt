package com.fieldintercom.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        // Below this hold duration, a press+release is treated as a "tap" that
        // latches talk on/off. At or above it, it's a "hold" that talks only
        // while held.
        private const val TAP_THRESHOLD_MS = 350L
        private const val SESSION_CHECK_INTERVAL_MS = 60_000L
    }

    private lateinit var statusBanner: TextView
    private lateinit var talkButton: Button
    private lateinit var volumeSlider: SeekBar
    private lateinit var sidetoneSlider: SeekBar
    private lateinit var logoutButton: Button
    private lateinit var latencyTestButton: Button
    private lateinit var sessionManager: SessionManager

    private var service: IntercomService? = null
    private var bound = false

    private var pressStartTime = 0L
    private var isLatched = false
    private var isForceMuted = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionCheckRunnable = object : Runnable {
        override fun run() {
            if (!sessionManager.isSessionValid()) {
                forceLogoutToLogin()
            } else {
                mainHandler.postDelayed(this, SESSION_CHECK_INTERVAL_MS)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as IntercomService.LocalBinder).getService()
            bound = true
            service?.statusListener = object : IntercomService.StatusListener {
                override fun onStatusChanged(status: NetworkClient.ConnectionStatus) {
                    updateStatusBanner(status)
                }
            }
            service?.muteListener = object : IntercomService.MuteListener {
                override fun onMuteChanged(muted: Boolean) {
                    isForceMuted = muted
                    if (muted) isLatched = false
                    updateTalkButtonAppearance(talking = false)
                }
            }
            service?.messageListener = object : IntercomService.MessageListener {
                override fun onTextMessage(text: String) {
                    showIncomingMessage(text)
                }
            }
            service?.setVolume(volumeSlider.progress / 100f)
            service?.setSidetoneVolume(sidetoneSlider.progress / 100f)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sessionManager = SessionManager(this)

        statusBanner = findViewById(R.id.statusBanner)
        talkButton = findViewById(R.id.talkButton)
        volumeSlider = findViewById(R.id.volumeSlider)
        sidetoneSlider = findViewById(R.id.sidetoneSlider)
        logoutButton = findViewById(R.id.logoutButton)
        latencyTestButton = findViewById(R.id.latencyTestButton)

        talkButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    onPressStart()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onPressEnd()
                    true
                }
                else -> false
            }
        }

        volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                service?.setVolume(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sidetoneSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                service?.setSidetoneVolume(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        logoutButton.setOnClickListener {
            forceLogoutToLogin()
        }

        latencyTestButton.setOnClickListener {
            startActivity(Intent(this, LatencyTestActivity::class.java))
        }

        val serviceIntent = Intent(this, IntercomService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        mainHandler.postDelayed(sessionCheckRunnable, SESSION_CHECK_INTERVAL_MS)
    }

    // --- Wired headset / Bluetooth headset button support ---
    // The inline mic button on a wired headset (or a Bluetooth headset's call
    // button) generates KEYCODE_HEADSETHOOK down/up events, which we treat
    // identically to touching the on-screen talk button.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (event?.repeatCount == 0) {
                onPressStart()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            onPressEnd()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun onPressStart() {
        if (isForceMuted) return
        if (isLatched) {
            // A press while latched-on means "stop talking".
            isLatched = false
            service?.stopTalking()
            updateTalkButtonAppearance(false)
            return
        }
        pressStartTime = System.currentTimeMillis()
        service?.startTalking()
        updateTalkButtonAppearance(true)
    }

    private fun onPressEnd() {
        if (isForceMuted) return
        if (isLatched) return // release doesn't matter, already latched from a previous tap

        val heldMs = System.currentTimeMillis() - pressStartTime
        if (heldMs < TAP_THRESHOLD_MS) {
            // Quick tap -- latch talk on until the next press.
            isLatched = true
            updateTalkButtonAppearance(true)
        } else {
            // Was a hold -- releasing stops talking.
            service?.stopTalking()
            updateTalkButtonAppearance(false)
        }
    }

    private fun updateTalkButtonAppearance(talking: Boolean) {
        if (isForceMuted) {
            talkButton.text = getString(R.string.talk_button_muted)
            talkButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_out_of_range_bg)
            talkButton.alpha = 0.6f
            return
        }
        talkButton.alpha = 1.0f
        talkButton.text = if (talking) getString(R.string.talk_button_active) else getString(R.string.talk_button_idle)
        talkButton.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (talking) R.color.talk_button_active else R.color.talk_button_idle
        )
    }

    private fun showIncomingMessage(text: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.incoming_message_title))
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun updateStatusBanner(status: NetworkClient.ConnectionStatus) {
        when (status) {
            NetworkClient.ConnectionStatus.CONNECTED -> {
                statusBanner.text = getString(R.string.status_connected)
                statusBanner.setBackgroundColor(ContextCompat.getColor(this, R.color.status_connected_bg))
            }
            NetworkClient.ConnectionStatus.OUT_OF_RANGE -> {
                statusBanner.text = getString(R.string.status_out_of_range)
                statusBanner.setBackgroundColor(ContextCompat.getColor(this, R.color.status_out_of_range_bg))
            }
            NetworkClient.ConnectionStatus.CONNECTING -> {
                statusBanner.text = getString(R.string.status_connecting)
                statusBanner.setBackgroundColor(ContextCompat.getColor(this, R.color.status_connecting_bg))
            }
        }
    }

    private fun forceLogoutToLogin() {
        sessionManager.clearSession()
        service?.logout()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(sessionCheckRunnable)
        if (bound) {
            service?.statusListener = null
            service?.muteListener = null
            service?.messageListener = null
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
