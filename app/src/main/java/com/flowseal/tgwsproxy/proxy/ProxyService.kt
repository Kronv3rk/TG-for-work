package com.flowseal.tgwsproxy.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.flowseal.tgwsproxy.MainActivity
import com.flowseal.tgwsproxy.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service that owns the [ProxyServer] instance for the entire app.
 *
 * The service stays alive (and thus the bound TCP listener too) when the UI is
 * dismissed or the device is briefly idle, which is the only way to keep the
 * proxy reachable from the Telegram client running on the same device.
 */
class ProxyService : Service() {

    private val binder = LocalBinder()
    private var server: ProxyServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val _runningCfg = MutableStateFlow<ProxyConfig?>(null)
    val runningConfig: StateFlow<ProxyConfig?> get() = _runningCfg

    val stateFlow get() = server?.state
    val statsFlow get() = server?.stats?.snapshot

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val cfg = ProxyConfig.load(this)
                startProxy(cfg)
            }
            ACTION_STOP -> {
                stopProxy()
                stopSelf()
            }
        }
        return START_STICKY
    }

    fun startProxy(cfg: ProxyConfig) {
        if (server?.state?.value is ProxyServer.State.Running) return
        val srv = ProxyServer(cfg)
        server = srv
        srv.start()
        _runningCfg.value = cfg
        startForeground(NOTIF_ID, buildNotification(cfg))
        acquireWake()
    }

    fun stopProxy() {
        server?.shutdownAndFree()
        server = null
        _runningCfg.value = null
        releaseWake()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    private fun buildNotification(cfg: ProxyConfig): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.proxy_running))
            .setContentText("${cfg.host}:${cfg.port}")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgWsProxy:Wakelock").apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWake() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    inner class LocalBinder : Binder() {
        val service: ProxyService get() = this@ProxyService
    }

    companion object {
        const val ACTION_START = "com.flowseal.tgwsproxy.START"
        const val ACTION_STOP = "com.flowseal.tgwsproxy.STOP"
        private const val CHANNEL_ID = "tg-ws-proxy"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            val intent = Intent(ctx, ProxyService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ProxyService::class.java).setAction(ACTION_STOP))
        }
    }
}
