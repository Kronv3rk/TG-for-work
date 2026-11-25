package com.flowseal.tgwsproxy

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.flowseal.tgwsproxy.proxy.ProxyRuntime

/** Notification actions run only in a focused, non-exported activity. */
class NotificationActionActivity : Activity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handled = savedInstanceState?.getBoolean("handled") ?: false
        if (handled) finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("handled", handled)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled || isFinishing) return
        handled = true
        try {
            if (!ProxyRuntime.isRunning()) {
                Toast.makeText(this, R.string.proxy_stopped_toast, Toast.LENGTH_SHORT).show()
                return
            }
            when (intent.action) {
                ProxyService.ACTION_OPEN_TELEGRAM -> ProxyRuntime.openTelegram(this)
                ProxyService.ACTION_COPY_LINK -> ProxyRuntime.copyLink(this)
            }
        } catch (error: Exception) {
            Toast.makeText(this, R.string.proxy_command_failed, Toast.LENGTH_SHORT).show()
        } finally {
            finish()
        }
    }
}