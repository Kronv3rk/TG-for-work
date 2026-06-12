package com.flowseal.tgwsproxy.proxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
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

    fun currentLink(context: Context): String = start(context).proxyLink

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

    fun loadOptions(context: Context): ProxyOptions {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val secret = prefs.getString(KEY_SECRET, null)
            ?.takeIf { it.length == 32 }
            ?: TelegramProxy.generateSecret().also {
                prefs.edit().putString(KEY_SECRET, it).apply()
            }
        return ProxyOptions(
            secretHex = secret,
            cfProxyEnabled = prefs.getBoolean(KEY_CF_ENABLED, true),
            cfProxyPriority = prefs.getBoolean(KEY_CF_PRIORITY, true),
            cfProxyUserDomain = prefs.getString(KEY_CF_USER_DOMAIN, "").orEmpty(),
            fakeTlsDomain = prefs.getString(KEY_FAKE_TLS_DOMAIN, ProxyOptions.DEFAULT_FAKE_TLS_DOMAIN)
                .orEmpty(),
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
