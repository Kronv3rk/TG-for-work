package com.flowseal.tgwsproxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
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
import com.flowseal.tgwsproxy.proxy.ProxyOptions
import com.flowseal.tgwsproxy.proxy.ProxyRuntime
import com.flowseal.tgwsproxy.proxy.ProxyStats
import com.flowseal.tgwsproxy.proxy.TelegramProxy

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val separators = Regex("[,\\s]+")
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
    private lateinit var backgroundWarning: TextView
    private lateinit var buildInfoValue: TextView
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
        buildInfoValue.text = buildInfoText()
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
        backgroundWarning = findViewById(R.id.backgroundWarning)
        buildInfoValue = findViewById(R.id.buildInfoValue)
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
        attachSwitchListeners()

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

        backgroundWarning.setOnClickListener { requestIgnoreBatteryOptimizations() }

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

    /**
     * "TG for Work 1.8.2 · ключ D6:A9:66:D0" — the installed version and the
     * signing certificate prefix. A stable key across versions is what lets
     * updates install over the top instead of failing with a package conflict.
     */
    private fun buildInfoText(): String {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        val fingerprint = signingFingerprintPrefix()
        return buildString {
            append("TG for Work")
            if (version.isNotBlank()) append(" ").append(version)
            if (fingerprint.isNotBlank()) append(" · ключ ").append(fingerprint)
        }
    }

    private fun signingFingerprintPrefix(): String {
        return runCatching {
            val signatureBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }
            if (signatureBytes == null) {
                ""
            } else {
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(signatureBytes)
                    .take(4)
                    .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
            }
        }.getOrDefault("")
    }

    override fun onResume() {
        super.onResume()
        updateBackgroundWarning()
    }

    /**
     * Android suspends background apps unless they are exempt from battery
     * optimisation; while suspended the relay sockets stall and the proxy looks
     * asleep. Surface a tappable banner only while the exemption is missing.
     */
    private fun updateBackgroundWarning() {
        backgroundWarning.visibility = if (isIgnoringBatteryOptimizations()) View.GONE else View.VISIBLE
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        try {
            startActivity(direct)
        } catch (ignored: Exception) {
            runCatching { startActivity(fallback) }
        }
        Toast.makeText(this, R.string.background_hint, Toast.LENGTH_LONG).show()
    }

    /**
     * (Re)attaches the Fake TLS and Cloudflare switch listeners. Kept separate so
     * the switches can be resynced from saved options without firing a listener.
     */
    private fun attachSwitchListeners() {
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
    }

    private fun showSettingsDialog() {
        val options = ProxyRuntime.loadOptions(this)
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val workerInput = view.findViewById<EditText>(R.id.workerDomainInput)
        val maskingInput = view.findViewById<EditText>(R.id.maskingDomainInput)
        val secretInput = view.findViewById<EditText>(R.id.secretInput)
        val cfDomainInput = view.findViewById<EditText>(R.id.cfDomainInput)
        val hostInput = view.findViewById<EditText>(R.id.hostInput)
        val portInput = view.findViewById<EditText>(R.id.portInput)
        val dcIpInput = view.findViewById<EditText>(R.id.dcIpInput)
        val bufferInput = view.findViewById<EditText>(R.id.bufferInput)
        val maxConnInput = view.findViewById<EditText>(R.id.maxConnInput)

        workerInput.setText(options.cfWorkerDomain)
        maskingInput.setText(options.fakeTlsDomain)
        secretInput.setText(options.secretHex)
        cfDomainInput.setText(options.cfProxyUserDomain)
        hostInput.setText(options.host)
        portInput.setText(options.port.toString())
        dcIpInput.setText(options.dcIpOverrides)
        bufferInput.setText(options.bufferKb.toString())
        maxConnInput.setText(options.maxConnections.toString())

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
                val cfDomain = cfDomainInput.text.toString().trim()
                val host = hostInput.text.toString().trim()
                val portText = portInput.text.toString().trim()
                val dcIps = dcIpInput.text.toString().trim()

                if (secret.isNotEmpty() && !ProxyRuntime.isValidSecret(secret)) {
                    toastError(R.string.settings_invalid_secret); return@setPositiveButton
                }
                if (worker.split(separators).filter { it.isNotEmpty() }
                        .any { CfProxyBalancer.normalizeDomain(it) == null }
                ) {
                    toastError(R.string.settings_invalid_worker); return@setPositiveButton
                }
                if (masking.isNotEmpty() && CfProxyBalancer.normalizeDomain(masking) == null) {
                    toastError(R.string.settings_invalid_masking); return@setPositiveButton
                }
                if (cfDomain.isNotEmpty() && CfProxyBalancer.normalizeDomain(cfDomain) == null) {
                    toastError(R.string.settings_invalid_masking); return@setPositiveButton
                }
                if (!isValidHost(host)) {
                    toastError(R.string.settings_invalid_host); return@setPositiveButton
                }
                val port = portText.toIntOrNull()
                if (port == null || port !in 1..65535) {
                    toastError(R.string.settings_invalid_port); return@setPositiveButton
                }
                // Every non-empty DC:IP token must parse, otherwise a typo would be
                // silently dropped and the user would think the override is active.
                if (dcIps.split(separators).filter { it.isNotEmpty() }
                        .any { ProxyOptions.parseDcIpPair(it) == null }
                ) {
                    toastError(R.string.settings_invalid_dcip); return@setPositiveButton
                }

                val updated = options.copy(
                    host = host,
                    port = port,
                    secretHex = if (secret.isEmpty()) options.secretHex else secret,
                    cfProxyUserDomain = cfDomain,
                    cfWorkerDomain = worker,
                    dcIpOverrides = dcIps,
                    bufferKb = bufferInput.text.toString().trim().toIntOrNull()
                        ?: ProxyOptions.DEFAULT_BUFFER_KB,
                    maxConnections = maxConnInput.text.toString().trim().toIntOrNull()
                        ?: ProxyOptions.DEFAULT_MAX_CONNECTIONS,
                    fakeTlsDomain = masking
                )
                // Changing any of these rewrites the tg://proxy link, which makes the
                // entry already saved inside Telegram stale: its handshake then fails
                // and Telegram reports the proxy as misconfigured. Offer to re-add it.
                val linkChanged = updated.secretHex != options.secretHex ||
                    updated.fakeTlsDomain != options.fakeTlsDomain ||
                    updated.host != options.host ||
                    updated.port != options.port
                ProxyRuntime.saveOptions(this, updated)
                // Saving a custom masking domain can flip Fake TLS on or off, so
                // resync the switch from the saved options without firing its
                // listener — otherwise a later toggle would overwrite the domain.
                fakeTlsSwitch.setOnCheckedChangeListener(null)
                fakeTlsSwitch.isChecked = ProxyRuntime.loadOptions(this).fakeTlsEnabled
                attachSwitchListeners()
                if (ProxyRuntime.isRunning()) ProxyService.start(this)
                renderState(ProxyRuntime.snapshot(this))
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                if (linkChanged) promptReconnectTelegram()
            }
            .show()
    }

    /**
     * The proxy link changed, so the proxy Telegram already has saved no longer
     * matches this server. Until it is re-added Telegram keeps failing the
     * handshake and shows "proxy uses incorrect settings".
     */
    private fun promptReconnectTelegram() {
        AlertDialog.Builder(this)
            .setTitle(R.string.link_changed_title)
            .setMessage(R.string.link_changed_body)
            .setNegativeButton(R.string.link_changed_later, null)
            .setPositiveButton(R.string.link_changed_open) { _, _ ->
                ProxyRuntime.start(this)
                ProxyService.start(this)
                ProxyRuntime.openTelegram(this)
            }
            .show()
    }

    private fun toastError(messageId: Int) {
        Toast.makeText(this, messageId, Toast.LENGTH_LONG).show()
    }

    /** The local listen address: an IP literal, "localhost" or a plain hostname. */
    private fun isValidHost(host: String): Boolean {
        if (host.isBlank() || host.contains("://") || host.contains('/')) return false
        return host == "localhost" ||
            ProxyOptions.isIpAddress(host) ||
            CfProxyBalancer.normalizeDomain(host) != null
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
