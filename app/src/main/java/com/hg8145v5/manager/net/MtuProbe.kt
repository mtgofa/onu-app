package com.hg8145v5.manager.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Outcome of an MTU discovery run (DF-flagged ICMP echo sweep from the phone). */
sealed interface MtuProbe {
    /** Highest payload that made it through unfragmented → [mtu] = payload + 28. */
    data class Found(val mtu: Int, val payload: Int, val host: String) : MtuProbe
    /** The device's `ping` binary has no "don't fragment" flag — cannot discover. */
    data class Unsupported(val host: String) : MtuProbe
    /** No ICMP reply at all (host down or ICMP filtered). */
    data class Failed(val host: String) : MtuProbe
}

/**
 * Finds the real path MTU by sending ICMP echo requests with the Don't-Fragment bit set and
 * binary-searching the largest payload that still gets a reply. Uses the phone's own `ping`
 * binary, so it reflects the exact path the phone uses (router → ISP). No root required.
 *
 * MTU = ICMP payload + 28 bytes of IPv4 (20) + ICMP (8) headers.
 */
object MtuTester {
    private const val HDR = 28
    private const val LO = 1200          // payload → 1228 MTU, always safe
    private const val HI = 1472          // payload → 1500 MTU, the IPv4 ceiling

    private val pingBin = listOf("/system/bin/ping", "/system/xbin/ping")
        .firstOrNull { File(it).exists() } ?: "/system/bin/ping"

    private data class R(val exit: Int, val out: String) {
        fun unsupported() = exit != 0 && listOf("invalid", "unrecognized", "unknown option", "usage")
            .any { out.contains(it, ignoreCase = true) }
    }

    private fun ping(host: String, size: Int, df: Boolean): R = try {
        val cmd = mutableListOf(pingBin, "-c", "1", "-W", "3", "-w", "5")
        if (df) { cmd += "-M"; cmd += "do" }
        cmd += "-s"; cmd += size.toString(); cmd += host
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        R(p.exitValue(), out)
    } catch (e: Exception) {
        R(-1, e.message ?: "")
    }

    suspend fun probe(host: String = "1.1.1.1"): MtuProbe = withContext(Dispatchers.IO) {
        val first = ping(host, LO, df = true)
        if (first.unsupported()) return@withContext MtuProbe.Unsupported(host)
        if (first.exit != 0) return@withContext MtuProbe.Failed(host)

        // If even the IPv4 maximum fits, the path is a full 1500.
        if (ping(host, HI, df = true).exit == 0) return@withContext MtuProbe.Found(HI + HDR, HI, host)

        // Binary-search the boundary: lo always succeeds, hi always fails.
        var lo = LO
        var hi = HI
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (ping(host, mid, df = true).exit == 0) lo = mid else hi = mid
        }
        MtuProbe.Found(lo + HDR, lo, host)
    }
}
