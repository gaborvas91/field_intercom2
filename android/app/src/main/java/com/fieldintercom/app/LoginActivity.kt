package com.fieldintercom.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class LoginActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private var service: IntercomService? = null
    private var bound = false

    private lateinit var serverInput: EditText
    private lateinit var deviceNameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var errorText: TextView
    private lateinit var loginButton: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as IntercomService.LocalBinder).getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)

        // Already logged in within the last 8 hours -- skip straight to the main screen.
        if (sessionManager.isSessionValid()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        serverInput = findViewById(R.id.serverAddressInput)
        deviceNameInput = findViewById(R.id.deviceNameInput)
        passwordInput = findViewById(R.id.passwordInput)
        errorText = findViewById(R.id.errorText)
        loginButton = findViewById(R.id.loginButton)

        serverInput.setText(sessionManager.serverAddress)
        deviceNameInput.setText(
            sessionManager.deviceName.ifEmpty { Build.MODEL ?: "Phone" }
        )

        // Login button starts faded/disabled and only lights up once both the
        // required fields (server address, password) are actually filled in.
        updateLoginButtonState()
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateLoginButtonState()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        serverInput.addTextChangedListener(watcher)
        passwordInput.addTextChangedListener(watcher)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        val serviceIntent = Intent(this, IntercomService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        loginButton.setOnClickListener {
            val address = serverInput.text.toString().trim()
            val password = passwordInput.text.toString()
            val deviceName = deviceNameInput.text.toString().trim().ifEmpty { "Phone" }
            if (address.isEmpty() || password.isEmpty()) return@setOnClickListener

            errorText.visibility = View.INVISIBLE
            loginButton.isEnabled = false
            sessionManager.serverAddress = address
            sessionManager.deviceName = deviceName

            service?.login(address, password, deviceName) { success ->
                updateLoginButtonState()
                if (success) {
                    sessionManager.markLoggedIn()
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    errorText.visibility = View.VISIBLE
                }
            } ?: run {
                errorText.visibility = View.VISIBLE
                updateLoginButtonState()
            }
        }
    }

    private fun updateLoginButtonState() {
        val ready = serverInput.text.toString().trim().isNotEmpty() &&
            passwordInput.text.toString().isNotEmpty()
        loginButton.isEnabled = ready
        loginButton.alpha = if (ready) 1.0f else 0.4f
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
