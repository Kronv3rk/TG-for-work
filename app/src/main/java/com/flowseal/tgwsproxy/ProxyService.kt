package com.flowseal.tgwsproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import com.flowseal.tgwsproxy.proxy.ConnectionLog
import com.flowseal.tgwsproxy.proxy.DefaultNetworkTracker
import com.flowseal.tgwsproxy.proxy.ProxyRuntime

class ProxyService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val networkThread = HandlerThread("tg-proxy-network-events")
    private lateinit var networkHandler: Handler
    private val networkTracker = DefaultNetworkTracker()
    private var networkManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkMonitoring = false
    private val refreshNotification = object : Runnable {
        override fun run() {
            if (!ProxyRuntime.isRunning()) return
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification())
            handler.postDelayed(this, NOTIFICATION_REFRESH_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        networkThread.start()
        networkHandler = Handler(networkThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Before the user unlocks the device after a reboot the settings store is
        // unreadable, so starting here would refuse to run rather than mint a new
        // identity. Stay alive and let a later start command bring the proxy up.
        // Stop must work even while credential-protected settings are locked.
        // Otherwise a user-issued STOP can be ignored before dispatch.
        if (intent?.action == ACTION_STOP) {
            stopNetworkMonitoring()
            ProxyRuntime.stop()
            ProxyKeepAlive.release()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!canStartProxy()) return START_STICKY
        when (intent?.action ?: ACTION_START) {
            ACTION_OPEN_TELEGRAM -> {
                ProxyRuntime.start(this)
                startForegroundCompat()
                ProxyRuntime.openTelegram(this)
            }
            ACTION_COPY_LINK -> {
                ProxyRuntime.start(this)
                startForegroundCompat()
                ProxyRuntime.copyLink(this)
            }
            ACTION_RESTART -> {
                ProxyRuntime.restart(this)
                startForegroundCompat()
            }
            else -> {
                ProxyRuntime.start(this)
                startForegroundCompat()
            }
        }
        return START_STICKY
    }

    private fun canStartProxy(): Boolean {
        val ok = runCatching { ProxyRuntime.loadOptions(applicationContext) }.isSuccess
        if (!ok) {
            ConnectionLog.info("Хранилище заблокировано, прокси стартует после разблокировки")
        }
        return ok
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshNotification)
        stopNetworkMonitoring()
        networkThread.quitSafely()
        ProxyRuntime.stop()
        ProxyKeepAlive.release()
        super.onDestroy()
    }

    /**
     * Swiping the app out of recents must not take the proxy down: Telegram may
     * still be using it. stopWithTask=false already keeps the service alive on
     * stock Android; re-issuing the start intent additionally recovers on
     * aggressive vendor ROMs (MIUI and friends) that kill it anyway.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (ProxyRuntime.isRunning()) {
            runCatching { start(applicationContext) }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        // Hold the CPU/Wi-Fi locks before going foreground: without them the
        // relay sockets freeze as soon as the screen turns off.
        ProxyKeepAlive.acquire(this)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // From Android 15 a dataSync foreground service is stopped after ~6
            // hours a day. A proxy is expected to stay up, so run as specialUse.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        handler.removeCallbacks(refreshNotification)
        handler.postDelayed(refreshNotification, NOTIFICATION_REFRESH_MS)
        startNetworkMonitoring()
    }

    /**
     * Listen only while the proxy service is active. Use the supplied capabilities,
     * not synchronous ConnectivityManager queries from inside network callbacks.
     */
    private fun startNetworkMonitoring() {
        if (networkCallback != null) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // API 24-25 dispatch callbacks on the connectivity thread. Android
                // 26+ uses our Handler, supplied at registration below.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    networkHandler.post { handleNetworkCapabilities(this, network, capabilities) }
                } else {
                    handleNetworkCapabilities(this, network, capabilities)
                }
            }

            override fun onLost(network: Network) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    networkHandler.post { handleNetworkLost(this, network) }
                } else {
                    handleNetworkLost(this, network)
                }
            }
        }
        networkMonitoring = true
        networkCallback = callback
        networkManager = manager
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.registerDefaultNetworkCallback(callback, networkHandler)
            } else {
                manager.registerDefaultNetworkCallback(callback)
            }
        }
        if (registered.isFailure) {
            networkMonitoring = false
            networkCallback = null
            networkManager = null
            ConnectionLog.info("Не удалось отслеживать смену сети")
        }
    }

    private fun handleNetworkCapabilities(
        callback: ConnectivityManager.NetworkCallback,
        network: Network,
        capabilities: NetworkCapabilities
    ) {
        if (!networkMonitoring || networkCallback !== callback || !ProxyRuntime.isRunning()) return
        val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val cellular = !wifi && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val handover = networkTracker.changed(network.toString(), cellular, wifi)
        ProxyRuntime.onDefaultNetworkChanged(cellular, forceReset = handover)
        ProxyKeepAlive.setWifiActive(this, wifi)
    }

    private fun handleNetworkLost(callback: ConnectivityManager.NetworkCallback, network: Network) {
        if (!networkMonitoring || networkCallback !== callback || !ProxyRuntime.isRunning()) return
        if (networkTracker.lost(network.toString())) {
            ProxyRuntime.onDefaultNetworkLost()
            ProxyKeepAlive.setWifiActive(this, false)
        }
    }

    private fun stopNetworkMonitoring() {
        networkMonitoring = false
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { networkManager?.unregisterNetworkCallback(callback) }
        networkManager = null
    }

    private fun stopForegroundCompat() {
        handler.removeCallbacks(refreshNotification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(): Notification {
        val stats = ProxyRuntime.snapshot(this)
        val openAppIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            pendingFlags()
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(R.drawable.ic_proxy)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(stats.lastMessage)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(R.drawable.ic_power, getString(R.string.notification_open), serviceIntent(ACTION_OPEN_TELEGRAM, 11))
            .addAction(R.drawable.ic_list, getString(R.string.notification_copy), serviceIntent(ACTION_COPY_LINK, 12))
            .addAction(R.drawable.ic_check, getString(R.string.notification_stop), serviceIntent(ACTION_STOP, 13))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_SERVICE)
        }
        return builder.build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, ProxyService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, pendingFlags())
    }

    private fun pendingFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "tg_for_work_proxy"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_REFRESH_MS = 3_000L
        const val ACTION_START = "com.flowseal.tgwsproxy.action.START"
        const val ACTION_OPEN_TELEGRAM = "com.flowseal.tgwsproxy.action.OPEN_TELEGRAM"
        const val ACTION_COPY_LINK = "com.flowseal.tgwsproxy.action.COPY_LINK"
        const val ACTION_RESTART = "com.flowseal.tgwsproxy.action.RESTART"
        const val ACTION_STOP = "com.flowseal.tgwsproxy.action.STOP"

        fun start(context: Context) {
            send(context, ACTION_START)
        }

        fun send(context: Context, action: String) {
            val intent = Intent(context, ProxyService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
