package com.flowseal.tgwsproxy

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.flowseal.tgwsproxy.proxy.ProxyRuntime
import com.flowseal.tgwsproxy.proxy.ProxyStats

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var connectButton: Button
    private lateinit var checkConnectionButton: Button
    private lateinit var stopButton: Button
    private lateinit var copyButton: Button
    private lateinit var restartButton: Button
    private lateinit var statusBadge: TextView
    private lateinit var routeValue: TextView
    private lateinit var sessionsValue: TextView
    private lateinit var trafficValue: TextView
    private lateinit var footerBody: TextView
    private lateinit var fakeTlsSwitch: Switch
    private lateinit var cfProxySwitch: Switch
    private lateinit var proxyRow: View
    private lateinit var securityRow: View
    private lateinit var routeRow: View
    private lateinit var profileRow: View

    private val refreshUi = object : Runnable {
        override fun run() {
            renderState(ProxyRuntime.snapshot(this@MainActivity))
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBars()
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
        stopButton = findViewById(R.id.stopButton)
        copyButton = findViewById(R.id.settingsButton)
        restartButton = findViewById(R.id.logsButton)
        statusBadge = findViewById(R.id.statusBadge)
        routeValue = findViewById(R.id.routeValue)
        sessionsValue = findViewById(R.id.sessionsValue)
        trafficValue = findViewById(R.id.trafficValue)
        footerBody = findViewById(R.id.footerBody)
        fakeTlsSwitch = findViewById(R.id.fakeTlsSwitch)
        cfProxySwitch = findViewById(R.id.cfProxySwitch)
        proxyRow = findViewById(R.id.proxyRow)
        securityRow = findViewById(R.id.securityRow)
        routeRow = findViewById(R.id.routeRow)
        profileRow = findViewById(R.id.profileRow)
    }

    private fun bindActions() {
        val options = ProxyRuntime.loadOptions(this)
        fakeTlsSwitch.isChecked = options.fakeTlsEnabled
        cfProxySwitch.isChecked = options.cfProxyEnabled

        fakeTlsSwitch.setOnCheckedChangeListener { _, checked ->
            val wasRunning = ProxyRuntime.isRunning()
            ProxyRuntime.setFakeTlsEnabled(this, checked)
            if (wasRunning) ProxyService.start(this)
            Toast.makeText(
                this,
                if (checked) R.string.fake_tls_enabled else R.string.fake_tls_disabled,
                Toast.LENGTH_SHORT
            ).show()
            renderState(ProxyRuntime.snapshot(this))
        }

        cfProxySwitch.setOnCheckedChangeListener { _, checked ->
            val wasRunning = ProxyRuntime.isRunning()
            ProxyRuntime.setCfProxyEnabled(this, checked)
            if (wasRunning) ProxyService.start(this)
            Toast.makeText(
                this,
                if (checked) R.string.cfproxy_enabled else R.string.cfproxy_disabled,
                Toast.LENGTH_SHORT
            ).show()
            renderState(ProxyRuntime.snapshot(this))
        }

        connectButton.setOnClickListener {
            startProxy(openTelegram = true)
        }

        checkConnectionButton.setOnClickListener {
            startProxy(openTelegram = false)
            Toast.makeText(this, ProxyRuntime.snapshot(this).lastMessage, Toast.LENGTH_SHORT).show()
        }

        stopButton.setOnClickListener {
            if (ProxyRuntime.isRunning()) {
                stopProxy()
            } else {
                startProxy(openTelegram = false)
            }
        }

        copyButton.setOnClickListener {
            copyProxyLink()
        }

        restartButton.setOnClickListener {
            restartProxy()
        }

        proxyRow.setOnClickListener { copyProxyLink() }
        securityRow.setOnClickListener { fakeTlsSwitch.isChecked = !fakeTlsSwitch.isChecked }
        routeRow.setOnClickListener { cfProxySwitch.isChecked = !cfProxySwitch.isChecked }
        profileRow.setOnClickListener {
            Toast.makeText(this, ProxyRuntime.snapshot(this).lastMessage, Toast.LENGTH_SHORT).show()
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
        statusBadge.setBackgroundResource(if (stats.running) R.drawable.bg_status_good else R.drawable.bg_status_idle)
        routeValue.text = routeLabel(stats)
        sessionsValue.text = "${stats.activeConnections}/${stats.totalConnections}"
        trafficValue.text = stats.transferred
        stopButton.text = getString(if (stats.running) R.string.primary_action_stop else R.string.primary_action_start)
        stopButton.setBackgroundResource(if (stats.running) R.drawable.bg_danger_button else R.drawable.bg_primary_button)
        stopButton.setTextColor(colorCompat(if (stats.running) R.color.danger else R.color.white))
        stopButton.compoundDrawables.forEach { drawable ->
            drawable?.setTint(colorCompat(if (stats.running) R.color.danger else R.color.white))
        }
        stopButton.alpha = 1.0f
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

    private fun copyProxyLink() {
        ProxyRuntime.start(this)
        ProxyService.start(this)
        ProxyRuntime.copyLink(this)
        renderState(ProxyRuntime.snapshot(this))
    }

    private fun restartProxy() {
        ProxyRuntime.restart(this)
        ProxyService.start(this)
        renderState(ProxyRuntime.snapshot(this))
        Toast.makeText(this, R.string.proxy_restarted, Toast.LENGTH_SHORT).show()
    }

    private fun stopProxy() {
        if (ProxyRuntime.isRunning()) {
            ProxyService.send(this, ProxyService.ACTION_STOP)
        } else {
            ProxyRuntime.stop()
        }
        renderState(ProxyRuntime.snapshot(this))
        Toast.makeText(this, R.string.proxy_stopped_toast, Toast.LENGTH_SHORT).show()
    }

    private fun routeLabel(stats: ProxyStats): String {
        return when {
            stats.cfProxyConnections > 0 -> "CF"
            stats.wsConnections > 0 -> "WS"
            stats.tcpFallbackConnections > 0 -> "TCP"
            stats.cfProxyEnabled && stats.fakeTlsEnabled -> "CF+TLS"
            stats.cfProxyEnabled -> "CF"
            stats.fakeTlsEnabled -> "TLS"
            else -> "TCP"
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }

    private fun applySystemBars() {
        window.statusBarColor = colorCompat(R.color.app_bg_start)
        window.navigationBarColor = colorCompat(R.color.surface)

        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        var flags = 0
        if (!isNight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (!isNight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun colorCompat(id: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getColor(id)
        } else {
            @Suppress("DEPRECATION")
            resources.getColor(id)
        }
    }
}
