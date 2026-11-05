package com.flowseal.tgwsproxy.proxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import com.flowseal.tgwsproxy.R

object ProxyRuntime {
    private const val LOG_TAG = "TgForWork"
    private const val PREFS = "proxy"
    private const val KEY_SECRET = "secret"
    private const val KEY_FAKE_TLS_DOMAIN = "fake_tls_domain"
    private const val KEY_CF_ENABLED = "cf_enabled"
    private const val KEY_CF_PRIORITY = "cf_priority"
    private const val KEY_CF_USER_DOMAIN = "cf_user_domain"
    private const val KEY_CF_WORKER_DOMAIN = "cf_worker_domain"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_DC_IPS = "dc_ips"
    private const val KEY_BUFFER_KB = "buffer_kb"
    private const val KEY_MAX_CONNECTIONS = "max_connections"
    private const val KEY_WS_POOL = "ws_pool"
    private const val KEY_FRONTING_SNI = "fronting_sni"

    @Volatile
    private var proxy: TelegramProxy? = null

    @Synchronized
    fun start(context: Context): TelegramProxy {
        // Mirror connection journal lines to Logcat so stability issues can be
        // investigated with `adb logcat -s TgForWork` even without the UI open.
        if (ConnectionLog.sink == null) {
            ConnectionLog.sink = { line -> Log.i(LOG_TAG, line) }
        }
        val options = loadOptions(context.applicationContext)
        val existing = proxy
        if (existing != null) {
            if (!existing.hasOptions(options)) {
                existing.close()
                proxy = null
            } else {
                existing.updateNetwork(options)
                existing.start()
                return existing
            }
        }
        val created = TelegramProxy(options)
        proxy = created
        created.start()
        return created
    }

    @Synchronized
    fun ensureStarted(context: Context): TelegramProxy {
        val existing = proxy
        if (existing != null) {
            existing.start()
            return existing
        }
        return start(context)
    }

    @Synchronized
    fun restart(context: Context): TelegramProxy {
        proxy?.close()
        proxy = null
        return start(context)
    }

    @Synchronized
    fun stop() {
        proxy?.close()
        proxy = null
    }

    fun snapshot(context: Context): ProxyStats {
        return proxy?.snapshot() ?: ProxyStats.empty(context.getString(R.string.proxy_stopped))
    }

    fun isRunning(): Boolean = proxy?.isRunning == true

    /** Link generation must not start a listening socket or foreground service. */
    fun currentLink(context: Context): String =
        proxy?.takeIf { it.isRunning }?.proxyLink
            ?: ProxyLinkFormatter.link(loadOptions(context.applicationContext))

    fun setFakeTlsEnabled(context: Context, enabled: Boolean) {
        val value = if (enabled) ProxyOptions.DEFAULT_FAKE_TLS_DOMAIN else ""
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FAKE_TLS_DOMAIN, value)
            .apply()
        if (isRunning()) restart(context)
    }

    fun setCfProxyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CF_ENABLED, enabled)
            .apply()
        if (isRunning()) restart(context)
    }

    /**
     * Persists every user-editable field of [options] and restarts the proxy if it
     * is running, so new settings take effect immediately. Runtime-derived fields
     * (the network type) are not stored. A secret that is not 32 hex chars is
     * ignored and the existing one kept; callers validate before saving.
     */
    fun saveOptions(context: Context, options: ProxyOptions) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CF_WORKER_DOMAIN, options.cfWorkerDomain.trim())
            .putString(KEY_CF_USER_DOMAIN, options.cfProxyUserDomain.trim())
            .putString(KEY_FAKE_TLS_DOMAIN, options.fakeTlsDomain.trim())
            .putString(KEY_HOST, options.host.trim())
            .putInt(KEY_PORT, options.port)
            .putString(KEY_DC_IPS, options.dcIpOverrides.trim())
            .putInt(KEY_BUFFER_KB, options.bufferKb)
            .putInt(KEY_MAX_CONNECTIONS, options.maxConnections)
            .putBoolean(KEY_WS_POOL, options.wsPoolEnabled)
            .putString(KEY_FRONTING_SNI, options.frontingSni.trim())
        val cleanSecret = options.secretHex.trim().lowercase()
        if (isValidSecret(cleanSecret)) {
            editor.putString(KEY_SECRET, cleanSecret)
        }
        editor.apply()
        if (isRunning()) restart(context)
    }

    fun isValidSecret(secret: String): Boolean =
        secret.length == 32 && secret.all { it.isDigit() || it in 'a'..'f' }

    fun copyLink(context: Context) {
        val link = currentLink(context)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TG for Work proxy", link))
        Toast.makeText(context, R.string.proxy_link_copied, Toast.LENGTH_SHORT).show()
    }

    fun openTelegram(context: Context) {
        val link = currentLink(context)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (ignored: Exception) {
            copyLink(context)
            Toast.makeText(context, R.string.telegram_not_found, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Returns the stored proxy secret, generating one only the very first time.
     *
     * The secret is half of the tg://proxy link Telegram has saved, so minting a
     * new one silently invalidates that saved proxy and Telegram then reports
     * "the proxy is not configured correctly". Credential-protected storage is
     * unreadable until the user unlocks the device after a reboot, and the
     * foreground service can be restarted in exactly that window — reading an
     * empty store there must never be mistaken for "no secret yet". So a new
     * secret is generated only while storage is actually available, and it is
     * written with commit() so it is durable before it is used.
     */
    private fun resolveSecret(context: Context, prefs: android.content.SharedPreferences): String {
        prefs.getString(KEY_SECRET, null)?.takeIf(::isValidSecret)?.let { return it }
        check(isStorageUnlocked(context)) {
            "Storage is locked; refusing to generate a new proxy secret"
        }
        val generated = TelegramProxy.generateSecret()
        prefs.edit().putString(KEY_SECRET, generated).commit()
        return generated
    }

    private fun isStorageUnlocked(context: Context): Boolean {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            ?: return true
        return userManager.isUserUnlocked
    }

    fun loadOptions(context: Context): ProxyOptions {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val secret = resolveSecret(context, prefs)
        return ProxyOptions(
            host = prefs.getString(KEY_HOST, TelegramProxy.DEFAULT_HOST).orEmpty()
                .ifBlank { TelegramProxy.DEFAULT_HOST },
            port = prefs.getInt(KEY_PORT, TelegramProxy.DEFAULT_PORT),
            secretHex = secret,
            cfProxyEnabled = prefs.getBoolean(KEY_CF_ENABLED, true),
            cfProxyPriority = prefs.getBoolean(KEY_CF_PRIORITY, true),
            cfProxyUserDomain = prefs.getString(KEY_CF_USER_DOMAIN, "").orEmpty(),
            cfWorkerDomain = prefs.getString(KEY_CF_WORKER_DOMAIN, "").orEmpty(),
            dcIpOverrides = prefs.getString(KEY_DC_IPS, "").orEmpty(),
            bufferKb = prefs.getInt(KEY_BUFFER_KB, ProxyOptions.DEFAULT_BUFFER_KB),
            maxConnections = prefs.getInt(KEY_MAX_CONNECTIONS, ProxyOptions.DEFAULT_MAX_CONNECTIONS),
            fakeTlsDomain = prefs.getString(KEY_FAKE_TLS_DOMAIN, ProxyOptions.DEFAULT_FAKE_TLS_DOMAIN)
                .orEmpty(),
            wsPoolEnabled = prefs.getBoolean(KEY_WS_POOL, true),
            frontingSni = prefs.getString(KEY_FRONTING_SNI, "").orEmpty(),
            cellularNetwork = isCellularNetwork(context)
        )
    }

    private fun isCellularNetwork(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
