package com.flowseal.tgwsproxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.ResultReceiver
import android.text.method.PasswordTransformationMethod
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import com.flowseal.tgwsproxy.proxy.CfProxyBalancer
import com.flowseal.tgwsproxy.proxy.ConnectionLog
import com.flowseal.tgwsproxy.proxy.ConnectionPhase
import com.flowseal.tgwsproxy.proxy.ProxyOptions
import com.flowseal.tgwsproxy.proxy.ProxyRuntime
import com.flowseal.tgwsproxy.proxy.ProxyStats
import com.flowseal.tgwsproxy.proxy.TelegramProxy

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val separators = Regex("[,\\s]+")
    private lateinit var connectButton: Button
    private lateinit var stopButton: Button
    private lateinit var copyButton: Button
    private lateinit var restartButton: Button
    private lateinit var heroTitle: TextView
    private lateinit var heroBody: TextView
    private lateinit var endpointValue: TextView
    private lateinit var detailsToggleButton: Button
    private lateinit var diagnosticsContainer: View
    private var diagnosticsExpanded = false
    private var commandPending = false
    private val settingsDraftState by lazy { androidx.lifecycle.ViewModelProvider(this)[SettingsDraftState::class.java] }
    private var settingsDialog: AlertDialog? = null
    private var captureSettingsDraft: (() -> SettingsDraft)? = null
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
    private lateinit var proxyRow: View
    private lateinit var profileRow: View

    private val refreshUi = object : Runnable {
        override fun run() {
            renderState(ProxyRuntime.snapshot(this@MainActivity))
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppearancePreferences.load(this).nightMode)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBars()
        applySystemInsets()
        requestNotificationPermission()
        bindViews()
        adaptMetricRows()
        bindActions()
        diagnosticsExpanded = savedInstanceState?.getBoolean("diagnostics_expanded") ?: false
        setDiagnosticsExpanded(diagnosticsExpanded)
        buildInfoValue.text = buildInfoText()
        renderState(ProxyRuntime.snapshot(this))
        settingsDraftState.take()?.let { showSettingsDialog(it) }
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(refreshUi)
        handler.post(refreshUi)
    }

    override fun onStop() {
        handler.removeCallbacks(refreshUi)
        super.onStop()
    }

    override fun onDestroy() {
        if (isChangingConfigurations) {
            settingsDraftState.retain(captureSettingsDraft?.invoke())
        }
        settingsDialog?.setOnDismissListener(null)
        settingsDialog?.dismiss()
        settingsDialog = null
        captureSettingsDraft = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("diagnostics_expanded", diagnosticsExpanded)
        super.onSaveInstanceState(outState)
    }

    private fun bindViews() {
        connectButton = findViewById(R.id.connectButton)
        stopButton = findViewById(R.id.stopButton)
        copyButton = findViewById(R.id.settingsButton)
        restartButton = findViewById(R.id.logsButton)
        heroTitle = findViewById(R.id.heroTitle)
        androidx.core.view.ViewCompat.setAccessibilityHeading(heroTitle, true)
        heroTitle.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        heroBody = findViewById(R.id.heroBody)
        endpointValue = findViewById(R.id.endpointValue)
        detailsToggleButton = findViewById(R.id.detailsToggleButton)
        diagnosticsContainer = findViewById(R.id.diagnosticsContainer)
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
        proxyRow = findViewById(R.id.proxyRow)
        profileRow = findViewById(R.id.profileRow)
    }

    private fun adaptMetricRows() {
        val configuration = resources.configuration
        if (configuration.screenWidthDp >= 360 && configuration.fontScale < 1.3f) return
        for (value in listOf(endpointValue, routeValue, sessionsValue, trafficValue, networkValue, linkModeValue)) {
            val row = value.parent as? LinearLayout ?: continue
            row.orientation = LinearLayout.VERTICAL
            row.gravity = Gravity.START
            for (index in 0 until row.childCount) {
                val child = row.getChildAt(index)
                val params = child.layoutParams as LinearLayout.LayoutParams
                params.width = LinearLayout.LayoutParams.MATCH_PARENT
                params.weight = 0f
                params.marginStart = 0
                params.marginEnd = 0
                child.layoutParams = params
                if (child is TextView) child.gravity = Gravity.START
            }
        }
    }

    private fun bindActions() {
        val options = ProxyRuntime.loadOptions(this)
        showEndpoint(options)

        connectButton.setOnClickListener {
            startProxy(openTelegram = true)
        }


        stopButton.setOnClickListener {
            if (ProxyRuntime.isRunning()) {
                stopProxy()
            } else {
                startProxy(openTelegram = false)
            }
        }

        // The proxy link is copied from the dedicated quick-action row.
        // This button belongs to Diagnostics and therefore copies the log.
        copyButton.setOnClickListener { copyDiagnosticLog() }

        restartButton.setOnClickListener {
            restartProxy()
        }

        proxyRow.setOnClickListener { copyProxyLink() }
        profileRow.setOnClickListener { showSettingsDialog() }
        findViewById<Button>(R.id.appearanceRow).setOnClickListener { showAppearanceDialog() }
        detailsToggleButton.setOnClickListener { setDiagnosticsExpanded(!diagnosticsExpanded) }

        backgroundWarning.setOnClickListener { requestIgnoreBatteryOptimizations() }

        connectionLogValue.setOnLongClickListener {
            copyDiagnosticLog()
            true
        }
    }

    private fun copyDiagnosticLog() {
        val log = ConnectionLog.fullText(censored = true)
        if (log.isBlank()) {
            Toast.makeText(this, R.string.log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TG for Work log", log))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showEndpoint(options: ProxyOptions) {
        endpointValue.text = getString(R.string.home_endpoint_value, options.host, options.port)
    }

    private fun setDiagnosticsExpanded(expanded: Boolean) {
        diagnosticsExpanded = expanded
        diagnosticsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        detailsToggleButton.setText(if (expanded) R.string.hide_diagnostics else R.string.show_diagnostics)
        describeExpansion(detailsToggleButton, expanded)
    }

    private fun describeExpansion(button: View, expanded: Boolean) {
        androidx.core.view.ViewCompat.setStateDescription(
            button, getString(if (expanded) R.string.section_expanded else R.string.section_collapsed)
        )
    }

    private fun applySystemInsets() {
        val scroll = findViewById<AdaptiveScrollView>(R.id.mainScroll)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            scroll.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                scroll.setSafeInsets(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            scroll.requestApplyInsets()
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

        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {

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
    private fun showAppearanceDialog() {
        val modes = AppearanceMode.entries
        val current = AppearancePreferences.load(this)
        AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
            .setTitle(R.string.appearance_title)
            .setSingleChoiceItems(modes.map { getString(it.label) }.toTypedArray(), modes.indexOf(current)) { dialog, index ->
                val selected = modes[index]
                AppearancePreferences.save(this, selected)
                dialog.dismiss()
                AppCompatDelegate.setDefaultNightMode(selected.nightMode)
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private fun showSettingsDialog(draft: SettingsDraft? = null) {
        if (settingsDialog?.isShowing == true) return
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
        val poolCheck = view.findViewById<CheckBox>(R.id.wsPoolCheck)
        val h2MediaCheck = view.findViewById<CheckBox>(R.id.h2MediaCheck)
        val testDcCheck = view.findViewById<CheckBox>(R.id.forceTestDcCheck)
        val proxyProtocolCheck = view.findViewById<CheckBox>(R.id.proxyProtocolCheck)
        val cfPlaintextCheck = view.findViewById<CheckBox>(R.id.cfPlaintextCheck)
        val cfAutoFallbackCheck = view.findViewById<CheckBox>(R.id.cfAutoFallbackCheck)
        val frontingInput = view.findViewById<EditText>(R.id.frontingSniInput)
        val toggleSecretButton = view.findViewById<Button>(R.id.toggleSecretButton)
        val fakeTlsSwitch = view.findViewById<SwitchCompat>(R.id.fakeTlsSwitch)
        val cfProxySwitch = view.findViewById<SwitchCompat>(R.id.cfProxySwitch)
        val advanced = view.findViewById<View>(R.id.advancedSettings)
        val advancedToggle = view.findViewById<Button>(R.id.advancedToggle)
        val secretSection = view.findViewById<View>(R.id.secretSection)
        val secretSectionToggle = view.findViewById<Button>(R.id.secretSectionToggle)

        fun setSecretExpanded(expanded: Boolean) {
            secretSection.visibility = if (expanded) View.VISIBLE else View.GONE
            secretSectionToggle.setText(if (expanded) R.string.settings_secret_section_close else R.string.settings_secret_section_open)
            describeExpansion(secretSectionToggle, expanded)
            if (!expanded) {
                secretInput.transformationMethod = PasswordTransformationMethod.getInstance()
                toggleSecretButton.setText(R.string.settings_show_secret)
            }
        }
        secretSectionToggle.setOnClickListener { setSecretExpanded(secretSection.visibility != View.VISIBLE) }

        fun setAdvancedExpanded(expanded: Boolean) {
            advanced.visibility = if (expanded) View.VISIBLE else View.GONE
            advancedToggle.setText(if (expanded) R.string.settings_hide_advanced else R.string.settings_show_advanced)
            describeExpansion(advancedToggle, expanded)
        }
        advancedToggle.setOnClickListener { setAdvancedExpanded(advanced.visibility != View.VISIBLE) }
        setSecretExpanded(false)
        setAdvancedExpanded(false)
        fakeTlsSwitch.isChecked = options.fakeTlsEnabled
        cfProxySwitch.isChecked = options.cfProxyEnabled
        maskingInput.isEnabled = fakeTlsSwitch.isChecked
        fakeTlsSwitch.setOnCheckedChangeListener { _, enabled ->
            maskingInput.isEnabled = enabled
            if (enabled && maskingInput.text.isNullOrBlank()) {
                maskingInput.setText(ProxyOptions.DEFAULT_FAKE_TLS_DOMAIN)
            }
        }
        fun showFieldError(field: EditText, message: Int) {
            if (!field.isShown) {
                if (field == secretInput) setSecretExpanded(true) else setAdvancedExpanded(true)
            }
            fieldError(field, message)
        }

        workerInput.setText(options.cfWorkerDomain)
        maskingInput.setText(options.fakeTlsDomain)
        secretInput.setText(options.secretHex)
        cfDomainInput.setText(options.cfProxyUserDomain)
        hostInput.setText(options.host)
        portInput.setText(options.port.toString())
        dcIpInput.setText(options.dcIpOverrides)
        bufferInput.setText(options.bufferKb.toString())
        maxConnInput.setText(options.maxConnections.toString())
        poolCheck.isChecked = options.wsPoolEnabled
        h2MediaCheck.isChecked = options.cfH2MediaEnabled
        testDcCheck.isChecked = options.forceTestDc
        proxyProtocolCheck.isChecked = options.proxyProtocolEnabled
        cfPlaintextCheck.isChecked = options.cfPlaintextEnabled
        cfAutoFallbackCheck.isChecked = options.cfAutoFallbackEnabled
        cfPlaintextCheck.setOnCheckedChangeListener { _, checked -> if (checked) cfAutoFallbackCheck.isChecked = false }
        cfAutoFallbackCheck.setOnCheckedChangeListener { _, checked -> if (checked) cfPlaintextCheck.isChecked = false }
        frontingInput.setText(options.frontingSni)

        val fields = listOf(workerInput, maskingInput, secretInput, cfDomainInput, hostInput,
            portInput, dcIpInput, bufferInput, maxConnInput, frontingInput)
        val checks = listOf<android.widget.CompoundButton>(poolCheck, h2MediaCheck, testDcCheck,
            proxyProtocolCheck, cfPlaintextCheck, cfAutoFallbackCheck, fakeTlsSwitch, cfProxySwitch)
        // Keep drafts only in the ViewModel; never put a secret in saved-instance state.
        secretInput.isSaveEnabled = false
        if (draft != null) {
            checks.forEach { check -> draft.checked[check.id]?.let { check.isChecked = it } }
            fields.forEach { field -> draft.text[field.id]?.let { field.setText(it) } }
            setAdvancedExpanded(draft.advancedExpanded)
            setSecretExpanded(draft.secretExpanded)
            secretInput.transformationMethod = PasswordTransformationMethod.getInstance()
            toggleSecretButton.setText(R.string.settings_show_secret)
        }

        view.findViewById<Button>(R.id.generateSecretButton).setOnClickListener {
            secretInput.setText(TelegramProxy.generateSecret())
        }
        toggleSecretButton.setOnClickListener {
            val hidden = secretInput.transformationMethod is PasswordTransformationMethod
            secretInput.transformationMethod =
                if (hidden) null else PasswordTransformationMethod.getInstance()
            toggleSecretButton.setText(
                if (hidden) R.string.settings_hide_secret else R.string.settings_show_secret
            )
            secretInput.setSelection(secretInput.text.length)
        }
        view.findViewById<Button>(R.id.copySecretButton).setOnClickListener {
            SensitiveClipboard.copy(this, "secret", secretInput.text.toString().trim())
            Toast.makeText(this, R.string.settings_secret_copied, Toast.LENGTH_SHORT).show()
        }

        val compact = resources.configuration.screenWidthDp < 360 || resources.configuration.screenHeightDp < 480 || resources.configuration.fontScale >= 1.3f
        val builder = AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
            .setTitle(R.string.settings_dialog_title)
        var compactSave: Button? = null
        var compactCancel: Button? = null
        if (compact) {
            val dp = resources.displayMetrics.density
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), (8 * dp).toInt())
            }
            fun action(label: Int, buttonId: Int): Button =
                androidx.appcompat.widget.AppCompatButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                    id = buttonId
                    setText(label)
                    setAllCaps(false)
                    textSize = 14f
                    setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.brand_dark))
                    minimumWidth = 0
                    minWidth = 0
                    minHeight = (48 * dp).toInt()
                    setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
                }
            compactCancel = action(R.string.settings_cancel, android.R.id.button2)
            compactSave = action(R.string.settings_save, android.R.id.button1)
            actions.addView(compactCancel, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(compactSave, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(actions, LinearLayout.LayoutParams(-1, -2))
            builder.setView(content)
        } else {
            builder.setView(view)
                .setNegativeButton(R.string.settings_cancel, null)
                .setPositiveButton(R.string.settings_save, null)
        }
        val dialog = builder.create()
        settingsDialog = dialog
        captureSettingsDraft = {
            SettingsDraft(
                fields.associate { it.id to it.text.toString() },
                checks.associate { it.id to it.isChecked },
                advanced.visibility == View.VISIBLE,
                secretSection.visibility == View.VISIBLE,
                view.scrollY
            )
        }
        dialog.setOnDismissListener {
            if (settingsDialog === dialog) {
                settingsDialog = null
                captureSettingsDraft = null
                settingsDraftState.retain(null)
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
            val wide = resources.configuration.screenWidthDp >= 600
            val width = if (wide) (560 * resources.displayMetrics.density).toInt() else -1
            if (compact || wide) {
                dialog.window?.setLayout(width, if (compact) -1 else -2)
            }
            val saveButton = compactSave ?: dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            val cancelButton = compactCancel ?: dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
            for (button in listOf(saveButton, cancelButton)) {
                button.setAllCaps(false)
                button.textSize = 14f
            }
            cancelButton.setOnClickListener { dialog.dismiss() }
            saveButton.setOnClickListener {
                val worker = workerInput.text.toString().trim()
                val masking = if (fakeTlsSwitch.isChecked) {
                    maskingInput.text.toString().trim().ifBlank { ProxyOptions.DEFAULT_FAKE_TLS_DOMAIN }
                } else ""
                val secret = secretInput.text.toString().trim().lowercase()
                val cfDomain = cfDomainInput.text.toString().trim()
                val host = hostInput.text.toString().trim()
                val portText = portInput.text.toString().trim()
                val dcIps = dcIpInput.text.toString().trim()
                val frontingSni = frontingInput.text.toString().trim().lowercase()

                if (secret.isNotEmpty() && !ProxyRuntime.isValidSecret(secret)) {
                    showFieldError(secretInput, R.string.settings_invalid_secret); return@setOnClickListener
                }
                if (worker.split(separators).filter { it.isNotEmpty() }
                        .any { CfProxyBalancer.normalizeDomain(it) == null }
                ) {
                    showFieldError(workerInput, R.string.settings_invalid_worker); return@setOnClickListener
                }
                if (masking.isNotEmpty() && CfProxyBalancer.normalizeDomain(masking) == null) {
                    showFieldError(maskingInput, R.string.settings_invalid_masking); return@setOnClickListener
                }
                if (cfDomain.isNotEmpty() && CfProxyBalancer.normalizeDomain(cfDomain) == null) {
                    showFieldError(cfDomainInput, R.string.settings_invalid_cfdomain); return@setOnClickListener
                }
                if (frontingSni.isNotEmpty() && CfProxyBalancer.normalizeDomain(frontingSni) == null) {
                    showFieldError(frontingInput, R.string.settings_invalid_fronting); return@setOnClickListener
                }
                if (!isValidHost(host)) {
                    showFieldError(hostInput, R.string.settings_invalid_host); return@setOnClickListener
                }
                val port = portText.toIntOrNull()
                if (port == null || port !in 1..65535) {
                    showFieldError(portInput, R.string.settings_invalid_port); return@setOnClickListener
                }
                // Every non-empty DC:IP token must parse, otherwise a typo would be
                // silently dropped and the user would think the override is active.
                if (dcIps.split(separators).filter { it.isNotEmpty() }
                        .any { ProxyOptions.parseDcIpPair(it) == null }
                ) {
                    showFieldError(dcIpInput, R.string.settings_invalid_dcip); return@setOnClickListener
                }

                val bufferKb = bufferInput.text.toString().trim().toIntOrNull()
                if (bufferKb == null || bufferKb !in ProxyOptions.MIN_BUFFER_KB..ProxyOptions.MAX_BUFFER_KB) {
                    showFieldError(bufferInput, R.string.settings_invalid_buffer)
                    return@setOnClickListener
                }
                val maxConnections = maxConnInput.text.toString().trim().toIntOrNull()
                if (maxConnections == null || maxConnections !in ProxyOptions.MIN_MAX_CONNECTIONS..ProxyOptions.MAX_MAX_CONNECTIONS) {
                    showFieldError(maxConnInput, R.string.settings_invalid_connections)
                    return@setOnClickListener
                }

                val updated = options.copy(
                    host = host,
                    port = port,
                    secretHex = if (secret.isEmpty()) options.secretHex else secret,
                    cfProxyEnabled = cfProxySwitch.isChecked,
                    cfProxyUserDomain = cfDomain,
                    cfWorkerDomain = worker,
                    dcIpOverrides = dcIps,
                    bufferKb = bufferKb,
                    maxConnections = maxConnections,
                    fakeTlsDomain = masking,
                    wsPoolEnabled = poolCheck.isChecked,
                    cfH2MediaEnabled = h2MediaCheck.isChecked,
                    forceTestDc = testDcCheck.isChecked,
                    proxyProtocolEnabled = proxyProtocolCheck.isChecked,
                    cfPlaintextEnabled = cfPlaintextCheck.isChecked,
                    cfAutoFallbackEnabled = cfAutoFallbackCheck.isChecked && !cfPlaintextCheck.isChecked,
                    frontingSni = frontingSni
                )
                // Changing any of these rewrites the tg://proxy link, which makes the
                // entry already saved inside Telegram stale: its handshake then fails
                // and Telegram reports the proxy as misconfigured. Offer to re-add it.
                val linkChanged = updated.secretHex != options.secretHex ||
                    updated.fakeTlsDomain != options.fakeTlsDomain ||
                    updated.host != options.host ||
                    updated.port != options.port
                val wasRunning = ProxyRuntime.isRunning()
                ProxyRuntime.saveOptions(this, updated)
                showEndpoint(updated)

                if (wasRunning && updated != options) requestProxyAction(ProxyService.ACTION_RESTART)
                renderState(ProxyRuntime.snapshot(this))
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                if (linkChanged) promptReconnectTelegram()
            }
        }
        dialog.show()
        if (draft != null) view.post { view.scrollTo(0, draft.scrollY) }
    }

    /**
     * The proxy link changed, so the proxy Telegram already has saved no longer
     * matches this server. Until it is re-added Telegram keeps failing the
     * handshake and shows "proxy uses incorrect settings".
     */
    private fun promptReconnectTelegram() {
        AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
            .setTitle(R.string.link_changed_title)
            .setMessage(R.string.link_changed_body)
            .setNegativeButton(R.string.link_changed_later, null)
            .setPositiveButton(R.string.link_changed_open) { _, _ ->
                startProxy(openTelegram = true)
            }
            .show()
    }

    private fun fieldError(field: EditText, messageId: Int) {
        field.error = getString(messageId)
        field.requestFocus()
    }

    /** The local listen address: an IP literal, "localhost" or a plain hostname. */
    private fun isValidHost(host: String): Boolean {
        if (host.isBlank() || host.contains("://") || host.contains('/')) return false
        return host == "localhost" ||
            ProxyOptions.isIpAddress(host) ||
            CfProxyBalancer.normalizeDomain(host) != null
    }

    private fun startProxy(openTelegram: Boolean) {
        requestProxyAction(ProxyService.ACTION_START, openTelegram)
    }

    private fun requestProxyAction(action: String, openTelegram: Boolean = false) {
        if (commandPending) return
        commandPending = true
        renderState(ProxyRuntime.snapshot(this))
        val receiver = object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (isFinishing || isDestroyed) return
                commandPending = false
                renderState(ProxyRuntime.snapshot(this@MainActivity))
                if (resultCode == ProxyService.RESULT_OK) {
                    when (action) {
                        ProxyService.ACTION_STOP -> Toast.makeText(this@MainActivity, R.string.proxy_stopped_toast, Toast.LENGTH_SHORT).show()
                        ProxyService.ACTION_RESTART -> Toast.makeText(this@MainActivity, R.string.proxy_restarted, Toast.LENGTH_SHORT).show()
                    }
                    if (openTelegram && ProxyRuntime.isRunning() && hasWindowFocus()) {
                        ProxyRuntime.openTelegram(this@MainActivity)
                    }
                } else if (resultCode == ProxyService.RESULT_FAILED) {
                    showCommandError()
                }
            }
        }
        try {
            ProxyService.send(this, action, receiver)
        } catch (error: Exception) {
            commandPending = false
            renderState(ProxyRuntime.snapshot(this))
            showCommandError()
        }
    }

    private fun showCommandError() {
        Toast.makeText(this, R.string.proxy_command_failed, Toast.LENGTH_LONG).show()
    }

    private fun renderState(stats: ProxyStats) {
        val phase = ConnectionPhase.of(stats.running, stats.activeConnections)
        val (title, body) = when (phase) {
            ConnectionPhase.STOPPED -> R.string.home_idle_title to R.string.home_idle_body
            ConnectionPhase.LISTENING -> R.string.home_running_title to R.string.home_running_body
            ConnectionPhase.HAS_CLIENTS -> R.string.home_clients_title to R.string.home_clients_body
        }
        val titleText = getString(if (commandPending) R.string.home_command_pending else title)
        if (heroTitle.text.toString() != titleText) heroTitle.text = titleText
        val bodyText = getString(body)
        if (heroBody.text.toString() != bodyText) heroBody.text = bodyText
        connectButton.visibility = if (stats.running) View.VISIBLE else View.GONE
        connectButton.isEnabled = !commandPending
        stopButton.isEnabled = !commandPending
        restartButton.isEnabled = stats.running && !commandPending
        profileRow.isEnabled = !commandPending
        findViewById<Button>(R.id.appearanceRow).isEnabled = !commandPending
        routeValue.text = routeLabel(stats)
        sessionsValue.text = getString(R.string.sessions_count, stats.activeConnections, stats.totalConnections)
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
        stopButton.setBackgroundResource(if (stats.running) R.drawable.bg_danger_button else R.drawable.bg_primary_button)
        stopButton.setTextColor(colorCompat(if (stats.running) R.color.danger else R.color.white))
        stopButton.compoundDrawables.forEach { drawable ->
            drawable?.setTint(colorCompat(if (stats.running) R.color.danger else R.color.white))
        }
        stopButton.alpha = if (commandPending) 0.65f else 1.0f
        // The dashboard shows one actionable message, not a wall of counters.
        // Detailed route events remain available in expandable diagnostics.
        footerBody.text = stats.lastMessage
        routeHealthValue.text = getString(R.string.route_health_prefix, stats.routeHealth)
        connectionLogValue.text = if (stats.recentEvents.isEmpty()) {
            getString(R.string.log_empty)
        } else {
            stats.recentEvents.joinToString("\n")
        }
    }

    private fun copyProxyLink() {
        ProxyRuntime.copyLink(this)
        renderState(ProxyRuntime.snapshot(this))
    }

    private fun restartProxy() {
        requestProxyAction(ProxyService.ACTION_RESTART)
    }

    private fun stopProxy() {
        requestProxyAction(ProxyService.ACTION_STOP)
    }

    private fun routeLabel(stats: ProxyStats): String {
        return when {
            !stats.running -> getString(R.string.route_not_selected)
            stats.routeStatus.startsWith("CF без TLS") -> getString(R.string.route_cf_plaintext)
            stats.routeStatus.startsWith("CF TLS") -> getString(R.string.route_cf_tls)
            stats.routeStatus.startsWith("CFW") -> "CF Worker"
            stats.routeStatus.startsWith("CF") -> "CF"
            stats.routeStatus.startsWith("WS") -> "WS"
            stats.routeStatus.startsWith("TCP") -> "TCP"
            else -> getString(R.string.route_automatic)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        } else {
            window.statusBarColor = colorCompat(R.color.app_bg_start)
            window.navigationBarColor = colorCompat(R.color.surface)
        }

        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        var flags = 0
        if (!isNight) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (!isNight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun colorCompat(id: Int): Int = getColor(id)
}
