package com.hg8145v5.manager.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** IP address of the Wi-Fi gateway (the router) the phone is currently connected to. */
fun gatewayIp(context: Context): String? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
    val best = cm.getLinkProperties(cm.activeNetwork)
        ?.routes?.firstOrNull { it.hasGateway() && it.isDefaultRoute }?.gateway?.hostAddress
    if (!best.isNullOrBlank()) return best

    // fallback for older Android: WifiManager DhcpInfo
    return runCatching {
        val wm = context.getApplicationContext()
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val gw = wm.dhcpInfo?.gateway ?: 0
        if (gw == 0) null else InetAddress.getByAddress(
            byteArrayOf(
                (gw shr 24).toByte(), (gw shr 16).toByte(), (gw shr 8).toByte(), gw.toByte()
            )
        ).hostAddress
    }.getOrNull()
}

/**
 * Best-effort model detection WITHOUT logging in: the router's own login page publishes
 * `var ProductName = 'HG8145V5';`. Requires being on a network where the router is reachable.
 */
fun fetchRouterModel(ip: String): String? {
    val ssl = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), SecureRandom())
    }
    val client = OkHttpClient.Builder()
        .sslSocketFactory(ssl.socketFactory, trustAll)
        .hostnameVerifier(HostnameVerifier { _, _ -> true })
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    for (scheme in listOf("https", "http")) {
        val req = Request.Builder().url("$scheme://$ip/").get().build()
        val html = runCatching { client.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string().orEmpty() else ""
        } }.getOrDefault("")
        if (html.isBlank()) continue
        val m = Regex("""(?i)ProductName\s*=\s*['\"]([^'\"]+)['\"]""").find(html)
            ?: Regex("""(?i)<title>([^<]{2,40})</title>""").find(html)
        val v = m?.groupValues?.get(1)?.trim() ?: continue
        if (v.contains("login", true).not() || v.any { it.isDigit() }) return v
    }
    return null
}

private val trustAll = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}