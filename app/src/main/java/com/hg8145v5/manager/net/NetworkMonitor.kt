package com.hg8145v5.manager.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import javax.net.SocketFactory

/**
 * Watches the Wi-Fi network and hands out a socket factory bound to it.
 *
 * Why this matters: while the router reboots its Wi-Fi has no internet, so Android moves traffic
 * to mobile data. Requests to 192.168.100.1 then leave through the wrong interface and never reach
 * the router — which is why the app used to sit on the "restarting" screen forever. Binding the
 * sockets to the Wi-Fi network fixes that, and the callback lets us retry the moment Wi-Fi is back.
 */
class NetworkMonitor(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    var wifi: Network? = null
        private set

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** [onChange] is called with true when Wi-Fi becomes usable, false when it goes away. */
    fun start(onChange: (Boolean) -> Unit) {
        if (callback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifi = network
                onChange(true)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (wifi == null) { wifi = network; onChange(true) }
            }

            override fun onLost(network: Network) {
                if (wifi == network) wifi = null
                onChange(false)
            }
        }
        callback = cb
        runCatching { cm.registerNetworkCallback(request, cb) }
    }

    fun stop() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    /** Socket factory pinned to Wi-Fi, or null to let the system route normally. */
    fun socketFactory(): SocketFactory? = wifi?.socketFactory
}
