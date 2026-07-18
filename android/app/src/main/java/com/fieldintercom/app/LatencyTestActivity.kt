package com.fieldintercom.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Two independent measurements:
 *
 * 1. Network ping: a bare round trip to the server and back, answered
 *    immediately without going through the audio mixing tick. This isolates
 *    WiFi + server overhead. One-way estimate = RTT / 2.
 *
 * 2. Full pipeline loopback: while this screen is open, the server stops
 *    excluding this phone's own voice from its mix, so audio sent while
 *    holding the test button comes all the way back through the *actual*
 *    mic-capture -> network -> mixing -> network -> receive path. This
 *    number is already a one-way estimate (it traverses the same two
 *    network hops a real conversation between two phones would) -- don't
 *    halve it. It doesn't include the final speaker playback buffering
 *    stage, so real perceived latency will be a little higher.
 */
class LatencyTestActivity : AppCompatActivity() {

    private var service: IntercomService? = null
    private var bound = false

    private lateinit var pingResultText: TextView
    private lateinit var loopResultText: TextView
    private lateinit var testTalkButton: Button
    private lateinit var closeButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pingSamples = mutableListOf<Long>()
    private val loopSamples = mutableListOf<Long>()

    private var awaitingPingSince = 0L

    private val pingRunnable = object : Runnable {
        override fun run() {
            awaitingPingSince = SystemClock.elapsedRealtime()
            service?.pingTest { rtt ->
                val oneWay = rtt / 2
                pingSamples.add(oneWay)
                if (pingSamples.size > 20) pingSamples.removeAt(0)
                val avg = pingSamples.average().toLong()
                pingResultText.text = "Last: $oneWay ms   Avg: $avg ms"
            } ?: run {
                pingResultText.text = "Not connected to the service yet..."
            }

            // If the previous ping never got a response, say so instead of
            // silently leaving stale/placeholder text on screen forever.
            mainHandler.postDelayed({
                if (SystemClock.elapsedRealtime() - awaitingPingSince >= 2500 && pingSamples.isEmpty()) {
                    pingResultText.text = "No response yet -- check the server is running and reachable"
                }
            }, 2500)

            mainHandler.postDelayed(this, 1000)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as IntercomService.LocalBinder).getService()
            bound = true

            service?.diagnosticsListener = object : IntercomService.DiagnosticsListener {
                override fun onLoopbackFrame(originTimestampMs: Long) {
                    val latency = SystemClock.elapsedRealtime() - originTimestampMs
                    if (latency in 0..5000) { // guard against a stray/stale sample
                        loopSamples.add(latency)
                        if (loopSamples.size > 50) loopSamples.removeAt(0)
                        val avg = loopSamples.average().toLong()
                        loopResultText.text =
                            "Last: $latency ms   Avg: $avg ms   (samples: ${loopSamples.size})"
                    }
                }
            }

            service?.startLoopbackTest()
            mainHandler.post(pingRunnable)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_latency_test)

        pingResultText = findViewById(R.id.pingResultText)
        loopResultText = findViewById(R.id.loopResultText)
        testTalkButton = findViewById(R.id.testTalkButton)
        closeButton = findViewById(R.id.closeTestButton)

        testTalkButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    service?.startTalking()
                    testTalkButton.backgroundTintList =
                        ContextCompat.getColorStateList(this, R.color.talk_button_active)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    service?.stopTalking()
                    testTalkButton.backgroundTintList =
                        ContextCompat.getColorStateList(this, R.color.talk_button_idle)
                    true
                }
                else -> false
            }
        }

        closeButton.setOnClickListener { finish() }

        val serviceIntent = Intent(this, IntercomService::class.java)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pingRunnable)
        service?.stopTalking()
        service?.stopLoopbackTest()
        if (bound) {
            service?.diagnosticsListener = null
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
