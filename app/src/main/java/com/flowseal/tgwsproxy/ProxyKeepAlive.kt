package com.flowseal.tgwsproxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.flowseal.tgwsproxy.proxy.ConnectionLog

/**
 * Keeps the CPU and the Wi-Fi radio awake while the proxy is running.
 *
 * Without a partial wake lock Android suspends the CPU shortly after the screen
 * turns off: the proxy's blocking socket reads freeze, Telegram's connection dies
 * and the app looks "asleep". The Wi-Fi lock stops the radio from entering
 * power-save, which otherwise drops long-lived relay sockets on the same devices.
 *
 * Both locks are held only while the foreground service runs and are always
 * released when it stops, so an idle app costs nothing.
 */
object ProxyKeepAlive {
    private const val WAKE_TAG = "TgForWork::proxy"
    private const val WIFI_TAG = "TgForWork:proxy-wifi"

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Suppress("DEPRECATION")
    @Synchronized
    fun acquire(context: Context) {
        val appContext = context.applicationContext
        if (wakeLock?.isHeld != true) {
            val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val lock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
            if (lock != null) {
                lock.setReferenceCounted(false)
                runCatching { lock.acquire() }
                wakeLock = lock
            }
        }
        setWifiActive(appContext, currentNetworkUsesWifi(appContext))
        ConnectionLog.info("Фоновый режим: CPU активен, Wi-Fi удерживается только при подключении по Wi-Fi")
    }

    /** A Wi-Fi lock must not drain power while the default network is mobile. */
    @Suppress("DEPRECATION")
    @Synchronized
    fun setWifiActive(context: Context, active: Boolean) {
        if (!active) {
            wifiLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
            wifiLock = null
            return
        }
        if (wifiLock?.isHeld == true) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG)
        if (lock != null) {
            lock.setReferenceCounted(false)
            if (runCatching { lock.acquire() }.isSuccess) wifiLock = lock
        }
    }

    private fun currentNetworkUsesWifi(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    @Synchronized
    fun release() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
        wifiLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wifiLock = null
    }
}
