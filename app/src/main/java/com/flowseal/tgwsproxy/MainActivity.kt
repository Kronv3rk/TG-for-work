package com.flowseal.tgwsproxy

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.flowseal.tgwsproxy.proxy.ProxyRuntime
import com.flowseal.tgwsproxy.proxy.ProxyStats

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var connectButton: Button
    private lateinit var checkConnectionButton: Button
    private lateinit var copyButton: Button
    private lateinit var restartButton: Button
    private lateinit var statusBadge: TextView
    private lateinit var sessionsValue: TextView
    private lateinit var trafficValue: TextView
    private lateinit var footerBody: TextView
    private lateinit var fakeTlsSwitch: Switch
    private lateinit var cfProxySwitch: Switch

    private val refreshUi = object : Runnable {
        override fun run() {
            renderState(ProxyRuntime.snapshot(this@MainActivity))
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.WHITE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        setContentView(R.layout.activity_main)
        requestNotificationPermission()
        bindViews()
        bindActions()
        renderState(ProxyRuntime.snapshot(this))
        handler.post(refreshUi)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshUi)
        super.onDestroy()
    }

    private fun bindViews() {
        connectButton = findViewById(R.id.connectButton)
        checkConnectionButton = findViewById(R.id.checkConnectionButton)
        copyButton = findViewById(R.id.settingsButton)
        restartButton = findViewById(R.id.logsButton)
        statusBadge = findViewById(R.id.statusBadge)
        sessionsValue = findViewById(R.id.sessionsValue)
        trafficValue = findViewById(R.id.protectionValue)
        footerBody = findViewById(R.id.footerBody)
        fakeTlsSwitch = findViewById(R.id.fakeTlsSwitch)
        cfProxySwitch = findViewById(R.id.cfProxySwitch)
    }

    private fun bindActions() {
        val options = ProxyRuntime.loadOptions(this)
        fakeTlsSwitch.isChecked = options.fakeTlsEnabled
        cfProxySwitch.isChecked = options.cfProxyEnabled

        fakeTlsSwitch.setOnCheckedChangeListener { _, checked ->
            val wasRunning = ProxyRuntime.isRunning()
            ProxyRuntime.setFakeTlsEnabled(this, checked)
            if (wasRunning) ProxyService.start(this)
            renderState(ProxyRuntime.snapshot(this))
        }

        cfProxySwitch.setOnCheckedChangeListener { _, checked ->
            val wasRunning = ProxyRuntime.isRunning()
            ProxyRuntime.setCfProxyEnabled(this, checked)
            if (wasRunning) ProxyService.start(this)
            renderState(ProxyRuntime.snapshot(this))
        }

        connectButton.setOnClickListener {
            startProxy(openTelegram = true)
        }

        checkConnectionButton.setOnClickListener {
            startProxy(openTelegram = false)
            Toast.makeText(this, ProxyRuntime.snapshot(this).lastMessage, Toast.LENGTH_SHORT).show()
        }

        copyButton.setOnClickListener {
            ProxyRuntime.start(this)
            ProxyService.start(this)
            ProxyRuntime.copyLink(this)
            renderState(ProxyRuntime.snapshot(this))
        }

        restartButton.setOnClickListener {
            ProxyRuntime.restart(this)
            ProxyService.start(this)
            renderState(ProxyRuntime.snapshot(this))
        }
    }

    private fun startProxy(openTelegram: Boolean) {
        try {
            val proxy = ProxyRuntime.start(this)
            ProxyService.start(this)
            renderState(proxy.snapshot())
            if (openTelegram) {
                ProxyRuntime.openTelegram(this)
            }
        } catch (error: Exception) {
            footerBody.text = "Не удалось запустить прокси: ${error.message ?: "неизвестная ошибка"}"
            Toast.makeText(this, footerBody.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderState(stats: ProxyStats) {
        statusBadge.text = if (stats.running) getString(R.string.proxy_running) else getString(R.string.proxy_stopped)
        sessionsValue.text = "${stats.activeConnections}/${stats.totalConnections}"
        trafficValue.text = stats.transferred
        footerBody.text = buildString {
            append(stats.lastMessage)
            if (stats.proxyLink.isNotBlank()) {
                append("\n")
                append(if (stats.fakeTlsEnabled) "Fake TLS: " else "MTProto: ")
                append(stats.proxyLink)
            }
            if (stats.cfProxyEnabled) {
                append("\nCloudflare fallback включён")
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }
}
