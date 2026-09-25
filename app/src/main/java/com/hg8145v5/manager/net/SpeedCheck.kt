package com.hg8145v5.manager.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Average round-trip stats from a `ping -c N` run. */
data class PingStats(val sent: Int, val received: Int, val avgMs: Double, val mdevMs: Double) {
    val lossPct: Int get() = if (sent <= 0) 0 else (((sent - received) * 100.0) / sent).roundToInt()
}

/** A DNS server and its measured average query time (null = no reply). */
data class DnsResult(val server: String, val label: String, val ms: Int?)

/** Bufferbloat: latency while the line is idle vs. saturated. */
data class Bloom(val idleMs: Double, val loadedMs: Double, val jitterMs: Double, val lossPct: Int) {
    val increaseMs: Double get() = loadedMs - idleMs
    val grade: String get() = when {
        lossPct > 0 -> "D"
        increaseMs < 30 -> "A"
        increaseMs < 70 -> "B"
        increaseMs < 150 -> "C"
        else -> "D"
    }
}

/** Phone's own Wi-Fi link (what the router's radio is actually delivering to this device). */
data class WifiLink(val ssid: String?, val freqMhz: Int, val rssiDbm: Int, val linkMbps: Int, val bssid: String?) {
    val band: String get() = when {
        freqMhz <= 0 -> "—"
        freqMhz < 2500 -> "2.4 GHz"
        freqMhz < 5925 -> "5 GHz"
        else -> "6 GHz"
    }
}

/**
 * Lightweight on-device checks for the things that actually shape browsing speed. All of them
 * measure the *real* path the phone uses (Wi-Fi → router → ISP), no root, no router login.
 */
object SpeedCheck {

    private val pingBin = listOf("/system/bin/ping", "/system/xbin/ping")
        .firstOrNull { File(it).exists() } ?: "/system/bin/ping"

    // ---- latency / packet loss / jitter -------------------------------------------------

    private fun runPing(host: String, count: Int): String? = try {
        val p = ProcessBuilder(pingBin, "-c", count.toString(), "-i", "0.2", "-W", "2", host)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        out
    } catch (e: Exception) { null }

    private val rttRe = Regex("""([\d.]+)/([\d.]+)/([\d.]+)/([\d.]+)\s*ms""")
    private val lossRe = Regex("""(\d+(?:\.\d+)?)%\s*packet loss""")

    suspend fun ping(host: String = "1.1.1.1", count: Int = 12): PingStats? = withContext(Dispatchers.IO) {
        val out = runPing(host, count) ?: return@withContext null
        val loss = lossRe.find(out)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@withContext null
        val m = rttRe.find(out)
        val avg = m?.groupValues?.get(2)?.toDoubleOrNull()
        val mdev = m?.groupValues?.get(4)?.toDoubleOrNull() ?: 0.0
        if (avg == null) return@withContext null
        val received = (count * (100 - loss) / 100).roundToInt()
        PingStats(count, received, avg, mdev)
    }

    // ---- DNS ---------------------------------------------------------------------------

    private fun dnsQuery(server: String, name: String, id: Int): Int? = try {
        val query = buildQuery(id, name)
        val sock = DatagramSocket()
        sock.soTimeout = 1500
        val t0 = System.nanoTime()
        sock.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
        val buf = ByteArray(512)
        val resp = DatagramPacket(buf, buf.size)
        sock.receive(resp)
        val ms = ((System.nanoTime() - t0) / 1_000_000.0).roundToInt()
        sock.close()
        // sanity: reply must echo our transaction id
        if (resp.length >= 2 && ((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)) == id) ms else null
    } catch (e: Exception) { null }

    /** Average query time over [tries] lookups; null if the server never answered. */
    suspend fun dns(server: String, tries: Int = 3): Int? = withContext(Dispatchers.IO) {
        val times = mutableListOf<Int>()
        for (i in 0 until tries) {
            dnsQuery(server, "www.example.com", 0x1000 + i)?.let { times += it }
        }
        if (times.isEmpty()) null else (times.sum().toDouble() / times.size).roundToInt()
    }

    private fun buildQuery(id: Int, name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(out)
        d.writeShort(id); d.writeShort(0x0100)        // standard query, recursion desired
        d.writeShort(1); d.writeShort(0); d.writeShort(0); d.writeShort(0)
        name.split('.').forEach { d.writeByte(it.length); d.write(it.toByteArray(Charsets.US_ASCII)) }
        d.writeByte(0)
        d.writeShort(1); d.writeShort(1)              // QTYPE A, QCLASS IN
        return out.toByteArray()
    }

    // ---- TCP handshake -----------------------------------------------------------------

    suspend fun tcpConnect(host: String = "1.1.1.1", port: Int = 443): Int? = withContext(Dispatchers.IO) {
        try {
            val t0 = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(host, port), 3000) }
            ((System.nanoTime() - t0) / 1_000_000.0).roundToInt()
        } catch (e: Exception) { null }
    }

    // ---- IPv6 --------------------------------------------------------------------------

    /** True only if this device has a real global IPv6 address *and* a v6 host answers. */
    suspend fun ipv6Reachable(): Boolean = withContext(Dispatchers.IO) {
        val hasGlobal = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().any { nif ->
                !nif.isLoopback && nif.isUp && nif.inetAddresses.toList().any { a ->
                    a is java.net.Inet6Address && !a.isLinkLocalAddress && !a.isLoopbackAddress && !a.isAnyLocalAddress
                }
            }
        }.getOrDefault(false)
        if (!hasGlobal) return@withContext false
        try {
            Socket().use { it.connect(InetSocketAddress("2606:4700:4700::1111", 443), 2500) }
            true
        } catch (e: Exception) { false }
    }

    // ---- bufferbloat -------------------------------------------------------------------

    /**
     * Pings the gateway target twice: once idle, once while three downloads saturate the line.
     * The latency increase is the "bufferbloat". Downloads stop the moment the loaded ping ends.
     */
    suspend fun bufferbloat(host: String = "1.1.1.1"): Bloom? = withContext(Dispatchers.IO) {
        val idle = ping(host, 8) ?: return@withContext null
        val stop = AtomicBoolean(false)
        val workers = (1..3).map {
            Thread { saturate(stop) }.also { t -> t.isDaemon = true; t.start() }
        }
        Thread.sleep(1200)                       // let the queue build up
        val loaded = ping(host, 16)
        stop.set(true)
        workers.forEach { runCatching { it.join(1500) } }
        if (loaded == null) return@withContext null
        Bloom(idle.avgMs, loaded.avgMs, loaded.mdevMs, loaded.lossPct)
    }

    private fun saturate(stop: AtomicBoolean) {
        try {
            val conn = URL("https://speed.cloudflare.com/__down?bytes=100000000").openConnection()
            if (conn is HttpURLConnection) {
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                conn.inputStream.use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (!stop.get() && ins.read(buf) >= 0) { /* drain */ }
                }
                conn.disconnect()
            }
        } catch (e: Exception) { /* load is best-effort */ }
    }
}
