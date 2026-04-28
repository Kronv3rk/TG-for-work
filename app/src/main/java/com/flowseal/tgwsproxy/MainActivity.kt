package com.flowseal.tgwsproxy

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.flowseal.tgwsproxy.proxy.ProxyStats
import com.flowseal.tgwsproxy.proxy.TelegramProxy

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

    private val refreshUi = object : Runnable {
        override fun run() {
            renderState(currentProxy?.snapshot() ?: ProxyStats.empty(getString(R.string.proxy_stopped)))
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
        bindViews()
        bindActions()
        renderState(currentProxy?.snapshot() ?: ProxyStats.empty(getString(R.string.proxy_stopped)))
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
    }

    private fun bindActions() {
        connectButton.setOnClickListener {
            startProxy(openTelegram = true)
        }

        checkConnectionButton.setOnClickListener {
            startProxy(openTelegram = false)
            Toast.makeText(this, currentProxy?.snapshot()?.lastMessage.orEmpty(), Toast.LENGTH_SHORT).show()
        }

        copyButton.setOnClickListener {
            copyProxyLink()
        }

        restartButton.setOnClickListener {
            currentProxy?.close()
            currentProxy = null
            startProxy(openTelegram = false)
        }
    }

    private fun startProxy(openTelegram: Boolean) {
        try {
            val proxy = ensureProxy()
            proxy.start()
            renderState(proxy.snapshot())
            if (openTelegram) {
                openTelegram(proxy.proxyLink)
            }
        } catch (error: Exception) {
            footerBody.text = "Не удалось запустить прокси: ${error.message ?: "неизвестная ошибка"}"
            Toast.makeText(this, footerBody.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun ensureProxy(): TelegramProxy {
        val existing = currentProxy
        if (existing != null) return existing
        val proxy = TelegramProxy(
            host = TelegramProxy.DEFAULT_HOST,
            port = TelegramProxy.DEFAULT_PORT,
            secretHex = getOrCreateSecret()
        )
        currentProxy = proxy
        return proxy
    }

    private fun renderState(stats: ProxyStats) {
        statusBadge.text = if (stats.running) getString(R.string.proxy_running) else getString(R.string.proxy_stopped)
        sessionsValue.text = "${stats.activeConnections}/${stats.totalConnections}"
        trafficValue.text = stats.transferred
        footerBody.text = buildString {
            append(stats.lastMessage)
            val link = currentProxy?.proxyLink
            if (!link.isNullOrBlank()) {
                append("\n")
                append(link)
            }
        }
    }

    private fun openTelegram(link: String) {
        try {
            startActivity(IntentCompat.viewUri(link))
        } catch (ignored: Exception) {
            copyProxyLink()
            Toast.makeText(this, R.string.telegram_not_found, Toast.LENGTH_LONG).show()
        }
    }

    private fun copyProxyLink() {
        val proxy = ensureProxy()
        proxy.start()
        renderState(proxy.snapshot())
        val link = proxy.proxyLink
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TG for Work proxy", link))
        Toast.makeText(this, R.string.proxy_link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun getOrCreateSecret(): String {
        val prefs = getSharedPreferences("proxy", Context.MODE_PRIVATE)
        val existing = prefs.getString("secret", null)
        if (!existing.isNullOrBlank() && existing.length == 32) {
            return existing
        }
        val secret = TelegramProxy.generateSecret()
        prefs.edit().putString("secret", secret).apply()
        return secret
    }

    private object IntentCompat {
        fun viewUri(uri: String) = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            Uri.parse(uri)
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        private var currentProxy: TelegramProxy? = null
    }
}
