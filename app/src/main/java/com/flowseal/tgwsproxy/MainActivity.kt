package com.flowseal.tgwsproxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import com.flowseal.tgwsproxy.proxy.CfProxyBalancer
import com.flowseal.tgwsproxy.proxy.ConnectionLog
import com.flowseal.tgwsproxy.proxy.ProxyRuntime
import com.flowseal.tgwsproxy.proxy.ProxyStats
import com.flowseal.tgwsproxy.proxy.TelegramProxy

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
    private lateinit var routeStatusValue: TextView
    private lateinit var routePolicyValue: TextView
    private lateinit var networkValue: TextView
    private lateinit var linkModeValue: TextView
    private lateinit var footerBody: TextView
    private lateinit var routeHealthValue: TextView
    private lateinit var connectionLogValue: TextView
    private lateinit var fakeTlsSwitch: SwitchCompat
    private lateinit var cfProxySwitch: SwitchCompat
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
        routeStatusValue = findViewById(R.id.routeStatusValue)
        routePolicyValue = findViewById(R.id.routePolicyValue)
        networkValue = findViewById(R.id.networkValue)
        linkModeValue = findViewById(R.id.linkModeValue)
        footerBody = findViewById(R.id.footerBody)
        routeHealthValue = findViewById(R.id.routeHealthValue)
        connectionLogValue = findViewById(R.id.connectionLogValue)
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
            Toast.makeText(this, ProxyRuntime.snapshot(this).routeStatus, Toast.LENGTH_SHORT).show()
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
        profileRow.setOnClickListener { showSettingsDialog() }

        connectionLogValue.setOnLongClickListener {
            val log = ConnectionLog.fullText()
            if (log.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TG for Work log", log))
                Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun showSettingsDialog() {
        val options = ProxyRuntime.loadOptions(this)
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val workerInput = view.findViewById<EditText>(R.id.workerDomainInput)
        val maskingInput = view.findViewById<EditText>(R.id.maskingDomainInput)
        val secretInput = view.findViewById<EditText>(R.id.secretInput)
        workerInput.setText(options.cfWorkerDomain)
        maskingInput.setText(options.fakeTlsDomain)
        secretInput.setText(options.secretHex)

        view.findViewById<Button>(R.id.generateSecretButton).setOnClickListener {
            secretInput.setText(TelegramProxy.generateSecret())
        }
        view.findViewById<Button>(R.id.copySecretButton).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("secret", secretInput.text.toString().trim()))
            Toast.makeText(this, R.string.settings_secret_copied, Toast.LENGTH_SHORT).show()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_dialog_title)
            .setView(view)
            .setNegativeButton(R.string.settings_cancel, null)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                val worker = workerInput.text.toString().trim()
                val masking = maskingInput.text.toString().trim()
                val secret = secretInput.text.toString().trim().lowercase()
                if (secret.isNotEmpty() &&
                    !(secret.length == 32 && secret.all { it.isDigit() || it in 'a'..'f' })
                ) {
                    Toast.makeText(this, R.string.settings_invalid_secret, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val workerDomains = worker.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
                if (workerDomains.any { CfProxyBalancer.normalizeDomain(it) == null }) {
                    Toast.makeText(this, R.string.settings_invalid_worker, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (masking.isNotEmpty() && CfProxyBalancer.normalizeDomain(masking) == null) {
                    Toast.makeText(this, R.string.settings_invalid_masking, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                ProxyRuntime.saveSettings(
                    this,
                    worker,
                    masking,
                    if (secret.isEmpty()) options.secretHex else secret
                )
                if (ProxyRuntime.isRunning()) ProxyService.start(this)
                renderState(ProxyRuntime.snapshot(this))
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }
            .show()
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
        statusBadge.setTextColor(colorCompat(if (stats.running) R.color.accent_dark else R.color.text_secondary))
        statusBadge.compoundDrawables.forEach { drawable ->
            drawable?.setTint(colorCompat(if (stats.running) R.color.accent_dark else R.color.text_secondary))
        }
        routeValue.text = routeLabel(stats)
        sessionsValue.text = "${stats.activeConnections}/${stats.totalConnections}"
        trafficValue.text = stats.transferred
        routeStatusValue.text = stats.routeStatus
        routePolicyValue.text = stats.routePolicy
        networkValue.text = stats.networkMode
        linkModeValue.text = if (stats.fakeTlsEnabled) {
            getString(R.string.link_mode_fake_tls)
        } else {
            getString(R.string.link_mode_mtproto)
        }
        stopButton.text = getString(if (stats.running) R.string.primary_action_stop else R.string.primary_action_start)
        stopButton.setBackgroundResource(if (stats.running) R.drawable.bg_danger_button else R.drawable.bg_success_button)
        stopButton.setTextColor(colorCompat(if (stats.running) R.color.danger else R.color.white))
        stopButton.compoundDrawables.forEach { drawable ->
            drawable?.setTint(colorCompat(if (stats.running) R.color.danger else R.color.white))
        }
        stopButton.alpha = 1.0f
        footerBody.text = buildString {
            append(stats.lastMessage)
            append("\n")
            append("Маршруты: WS ")
            append(stats.wsConnections)
            append(" / CFW ")
            append(stats.workerConnections)
            append(" / CF ")
            append(stats.cfProxyConnections)
            append(" / TCP ")
            append(stats.tcpFallbackConnections)
            if (stats.proxyLink.isNotBlank()) {
                append("\n")
                append("Режим ссылки: ")
                append(if (stats.fakeTlsEnabled) "Fake TLS" else "MTProto")
            }
            if (stats.cfProxyEnabled) {
                append("\nCloudflare включён")
            }
        }
        routeHealthValue.text = getString(R.string.route_health_prefix, stats.routeHealth)
        connectionLogValue.text = if (stats.recentEvents.isEmpty()) {
            getString(R.string.log_empty)
        } else {
            stats.recentEvents.joinToString("\n")
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
            !stats.running -> "Стоп"
            stats.routeStatus.startsWith("CF") -> "CF"
            stats.routeStatus.startsWith("WS") -> "WS"
            stats.routeStatus.startsWith("TCP") -> "TCP"
            else -> "Авто"
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
