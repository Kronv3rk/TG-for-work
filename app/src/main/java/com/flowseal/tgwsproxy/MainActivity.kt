package com.flowseal.tgwsproxy

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.flowseal.tgwsproxy.proxy.ProxyConfig
import com.flowseal.tgwsproxy.proxy.ProxyServer
import com.flowseal.tgwsproxy.proxy.ProxyService
import com.flowseal.tgwsproxy.proxy.ProxyStats
import com.flowseal.tgwsproxy.ui.AppTheme
import com.flowseal.tgwsproxy.ui.MainScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {

    private var service: ProxyService? = null
    private val serviceState = MutableStateFlow<ProxyService?>(null)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? ProxyService.LocalBinder ?: return
            service = local.service
            serviceState.value = local.service
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceState.value = null
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignore — service still works without notif if user denies */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotifPermission()

        bindService(Intent(this, ProxyService::class.java), conn, Context.BIND_AUTO_CREATE)

        setContent {
            AppTheme {
                MainScreen(
                    initialConfig = ProxyConfig.load(this),
                    serviceFlow = serviceState,
                    onSaveConfig = { ProxyConfig.save(this, it) },
                    onStart = { cfg ->
                        ProxyConfig.save(this, cfg)
                        service?.startProxy(cfg) ?: ProxyService.start(this)
                    },
                    onStop = {
                        service?.stopProxy() ?: ProxyService.stop(this)
                    },
                    onCopyLink = { link ->
                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("tg-proxy", link))
                    },
                    onShareLink = { link ->
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, link)
                        }
                        startActivity(Intent.createChooser(send, "Share proxy link"))
                    },
                    onOpenInTelegram = { link ->
                        val tg = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                        runCatching { startActivity(tg) }
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        runCatching { unbindService(conn) }
        super.onDestroy()
    }

    private fun maybeRequestNotifPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
