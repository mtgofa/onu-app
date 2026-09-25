package com.hg8145v5.manager.net

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Result of a control write (setajax.cgi). */
sealed class WriteResult {
    object Success : WriteResult()
    data class Denied(val code: String) : WriteResult()   // node ACL denial, e.g. 0x1
    data class ParamError(val code: String) : WriteResult() // permission passed, params wrong
    data class Failed(val reason: String) : WriteResult()   // network / session
}

/** A connected LAN device parsed from GetLanUserDevInfo.asp */
data class Device(
    val name: String,
    val ip: String,
    val mac: String,
    val conn: String,     // WIFI / LAN / SSID band
    val online: Boolean
)

/** A device from the router's full connection history (<hostsInstance> in hw_ctree.xml) —
 *  ANY device that ever connected (Wi-Fi or LAN), even after the live table forgot it.
 *  Read from a read-only config download (never uploaded, so no reboot). */
data class Host(
    val mac: String,
    val name: String,
    val conn: String,     // "2.4G" / "5G" / "LAN" / raw
    val online: Boolean
)

/** Live link stats for one device, as the WebUI's "Details" dialog shows them. */
data class DevStat(
    val mac: String,
    val rssiDbm: Int?,        // null when offline / wired
    val rateMbps: Int?,       // negotiated PHY rate
    val onlineMinutes: Int,   // how long it has been connected
    val port: String          // SSID1 / SSID5 / LAN…
) {
    val band: String get() = when {
        port.equals("SSID5", true) -> "5G"
        port.startsWith("SSID", true) -> "2.4G"
        else -> port
    }
    /** 0..4 bars, the usual Wi-Fi thresholds. */
    val bars: Int get() = when (val r = rssiDbm) {
        null -> 0
        else -> when {
            r >= -55 -> 4
            r >= -65 -> 3
            r >= -75 -> 2
            else -> 1
        }
    }
}

/** One Wi-Fi radio exactly as the router's own WlanBasic page describes it.
 *  Read live from WlanBasic.asp, so a write can echo back every field the
 *  firmware expects instead of guessing. */
data class WlanRadio(
    val domain: String,        // InternetGatewayDevice.LANDevice.1.WLANConfiguration.N
    val band: String,          // "2.4G" / "5G"
    val ifName: String,        // ath0 / ath4
    val ssid: String,
    val enabled: Boolean,
    val advertised: Boolean,   // false = "Hide the network"
    val deviceNum: String,     // X_HW_AssociateNum
    val wmm: String,
    val beaconType: String,    // WPAand11i / 11i / WPA ...
    val authMode: String,      // PSKAuthentication ...
    val encModes: String,      // TKIPandAESEncryption ...
    val rekey: String,         // X_HW_GroupRekey seconds
    val password: String       // current PSK (the page ships it in clear)
) {
    val instance: String get() = domain.substringAfterLast('.')
    val pskNode: String get() = "$domain.PreSharedKey.1"
}

/**
 * Low-level client for the Huawei HG8145V5 (TEDATA firmware).
 * Verified live: login flow, session cookie, .asp reads, setajax.cgi writes, cfgfiledown.
 * Session is short-lived; callers should relogin on [SessionExpired].
 */
class RouterApi(
    val host: String = "192.168.100.1",
    private var username: String = "admin",
    private var password: String = ""
) {
    private val base = "https://$host"
    private var sessionCookie: String? = null   // e.g. "Cookie=sid=<hash>:Language:english:id=1"

    // Sockets can be pinned to the Wi-Fi network; otherwise Android may route our requests to
    // the router over mobile data (especially while the router's Wi-Fi has no internet).
    @Volatile private var boundSockets: javax.net.SocketFactory? = null
    private var client: OkHttpClient = buildTrustAllClient(8, 20)
    // short-timeout client used only while waiting for the router to come back after a reboot
    private var quickClient: OkHttpClient = buildTrustAllClient(3, 6)

    /** Pin all traffic to this socket factory (the Wi-Fi network), or null for system routing. */
    fun useSockets(sf: javax.net.SocketFactory?) {
        if (sf === boundSockets) return
        boundSockets = sf
        client = buildTrustAllClient(8, 20)
        quickClient = buildTrustAllClient(3, 6)
    }

    fun setCredentials(user: String, pass: String) { username = user; password = pass }

    /** Usable without an active session: the router's login page publishes the model in plain JS. */
    fun productName(): String? {
        val req = Request.Builder().url("$base/").get().build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                Regex("""(?i)ProductName\s*=\s*['\"]([^'\"]+)['\"]""")
                    .find(resp.body?.string().orEmpty())?.groupValues?.get(1)?.trim()
            }
        }.getOrNull()
    }
    fun hasSession() = sessionCookie != null
    fun clearSession() { sessionCookie = null }

    class SessionExpired : Exception("router session expired (403/Waiting)")

    /** Cheap reachability probe — true when the router's web server answers at all (any status).
     *  Used during reboot wait to avoid hammering login.cgi every few seconds. */
    fun probeUp(): Boolean {
        val req = Request.Builder().url("$base/asp/GetRandCount.asp").get().build()
        return runCatching {
            quickClient.newCall(req).execute().use { true }
        }.getOrDefault(false)
    }

    // ---- login (3-step challenge) ----
    /** @return true on success (Set-Cookie received). */
    fun login(): Boolean {
        sessionCookie = null
        val token = post("/asp/GetRandCount.asp", body = FormBody.Builder().build(),
            cookie = "Cookie=body:english:id=-1", raw = true)
            ?.let { stripBom(it).trim() } ?: return false
        if (token.length < 16) return false

        val pwB64 = Base64.getEncoder().encodeToString(password.toByteArray(Charsets.UTF_8))
        val form = FormBody.Builder()
            .add("UserName", username)
            .add("PassWord", pwB64)
            .add("Language", "english")
            .add("x.X_HW_Token", token)
            .build()

        val req = Request.Builder()
            .url("$base/login.cgi")
            .header("Cookie", "Cookie=body:english:id=-1")
            .post(form)
            .build()
        client.newCall(req).execute().use { resp ->
            val setCookie = resp.headers("Set-Cookie").firstOrNull { it.contains("sid=") }
            if (setCookie != null) {
                sessionCookie = setCookie.substringBefore(';').trim()
                return true
            }
        }
        return false
    }

    // ---- logout (real server-side logout, frees the admin slot) ----
    /** Ends the admin session on the router via logout.cgi (same call the WebUI "Sign out" does).
     *  Dropping the app cookie alone leaves the router thinking the admin is still logged in. */
    fun logoutNow(): Boolean {
        val token = runCatching {
            val req = Request.Builder().url("$base/html/ssmp/common/GetRandToken.asp")
                .header("Cookie", sessionCookie ?: return false).get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.code != 200) null else resp.body?.string()?.trim()
            }
        }.getOrNull()?.takeIf { it != null && it.isNotBlank() } ?: return false
        post("/logout.cgi?RequestFile=html/logout.html",
            body = FormBody.Builder().add("x.X_HW_Token", token).build(),
            cookie = sessionCookie, raw = true)
        clearSession()
        return true
    }

    // ---- reads ----
    /** GET a session page; throws SessionExpired if the 588-byte "Waiting" page / 403 comes back. */
    fun getPage(path: String): String {
        val req = Request.Builder().url("$base/$path")
            .header("Cookie", sessionCookie ?: throw SessionExpired())
            .header("Referer", "$base/html/amp/wlanbasic/WlanBasic.asp?2G")
            .get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 403 || body.contains("Waiting...")) throw SessionExpired()
            return body
        }
    }

    private var lastFullDevFetch = 0L

    /** All known LAN devices — live + history, deduped by MAC (exact TEDATA format).
     *  Live rows come from GetLanUserDevInfo.asp; previously-connected-but-now-forgotten
     *  devices are filled in from <hostsInstance> in a read-only config download (cached). */
    fun getDevices(): List<Device> {
        val out = LinkedHashMap<String, Device>()
        for (d in parseDevicePage("html/bbsp/common/GetLanUserDevInfo.asp")) out[d.mac] = d
        // The router's live table only remembers ~8 recent rows. Its full connection history
        // lives in <hostsInstance> inside hw_ctree.xml — merge those in (best-effort) so
        // previously-connected devices stay visible even after the router forgets them.
        // The 207KB config is expensive: re-download it at most every 5 min.
        val now = System.currentTimeMillis()
        if (now - lastFullDevFetch > 300_000) {
            lastFullDevFetch = now
            try {
                for (h in hostsFromConfig())
                    if (h.mac !in out) out[h.mac] = Device(h.name, "", h.mac, h.conn, h.online)
            } catch (e: SessionExpired) {
                throw e                       // let withSession re-login and retry
            } catch (e: Exception) { /* history is best-effort */ }
        }
        return out.values.toList()
    }

    /**
     * RSSI / negotiated rate / online duration for every device — the numbers behind the
     * WebUI's "Details" button. One page holds them all:
     *
     *   new USERDev(Domain, IpAddr, MacAddr, PortID, IpType, DevType, DevStatus, PortType,
     *               time, HostName, RSSI, NegotiatedRate)
     *
     * `time` is "H:MM" of connection time; offline rows carry RSSI/rate "0".
     * Keyed by upper-case MAC.
     */
    fun deviceStats(): Map<String, DevStat> {
        val html = getPage(DEV_DETAIL_PAGE)
        val out = LinkedHashMap<String, DevStat>()
        for (m in Regex("""new\s+USERDev\((.*?)\)""").findAll(html)) {
            val a = splitJsArgs(m.groupValues[1]).map { unescapeHx(it) }
            if (a.size < 12) continue
            val mac = a[2].uppercase().takeIf { it.isNotBlank() } ?: continue
            val online = a[6].equals("Online", true)
            val rssi = a[10].toIntOrNull()?.takeIf { online && it != 0 }
            val rate = a[11].toIntOrNull()?.takeIf { online && it != 0 }
            val dur = a[8].split(":").let { p ->
                val h = p.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                val mi = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                h * 60 + mi
            }
            out[mac] = DevStat(mac, rssi, rate, dur, a[3])   // the page repeats the array; last wins
        }
        return out
    }

    private fun parseDevicePage(page: String): List<Device> {
        val html = runCatching { getPage(page) }.getOrElse { return emptyList() }
        val out = LinkedHashMap<String, Device>()   // dedupe by MAC
        val rx = Regex("""new\s+USERDevice(?:New)?\((.*?)\)""")
        for (m in rx.findAll(html)) {
            val args = splitJsArgs(m.groupValues[1]).map { unescapeHx(it) }
            if (args.size < 10) continue
            val ip = args[1]; val mac = args[2].uppercase(); val portType = args[7]
            val status = args[6]; val host = args[9]; val alias = args.getOrElse(13) { "" }
            if (mac.isBlank()) continue
            val name = when {
                alias.isNotBlank() -> alias
                host.isNotBlank() && host != "--" -> host
                else -> mac
            }
            out[mac] = Device(name, ip, mac, portType, status.equals("Online", true))
        }
        return out.values.toList()
    }

    // ---- token for writes ----
    /** Fetch a fresh single-use onttoken from a feature page. */
    fun tokenFrom(page: String): String? {
        val html = getPage(page)
        val m = Regex("""id="hwonttoken"[^>]*value="([0-9a-fA-F]+)"""").find(html)
            ?: Regex("""name="onttoken"[^>]*value="([0-9a-fA-F]+)"""").find(html)
        return m?.groupValues?.get(1)
    }

    // ---- control write (setajax.cgi) ----
    /**
     * Replicates a WebUI page's own save call. [node] is the x= value, [page] the RequestFile
     * (also where the fresh token comes from), [params] the exact addParameter() names/values.
     */
    fun setAjax(node: String, page: String, params: Map<String, String>): WriteResult {
        val token = tokenFrom(page) ?: return WriteResult.Failed("no token")
        val fb = FormBody.Builder()
        params.forEach { (k, v) -> fb.add(k, v) }
        fb.add("x.X_HW_Token", token)
        val url = "$base/setajax.cgi?x=$node&RequestFile=$page"
        val req = Request.Builder().url(url)
            .header("Cookie", sessionCookie ?: return WriteResult.Failed("no session"))
            .header("Referer", "$base/$page")
            .post(fb.build()).build()
        client.newCall(req).execute().use { resp ->
            val body = unescapeHx(resp.body?.string().orEmpty())
            if (body.contains("Waiting...")) return WriteResult.Failed("session")
            val result = Regex(""""result"\s*:\s*(\d+)""").find(body)?.groupValues?.get(1)
            val error = Regex(""""error"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1)
            return when {
                result == "0" -> WriteResult.Success
                error == "0x1" -> WriteResult.Denied(error)
                error != null && error != "0x0" -> WriteResult.ParamError(error)
                result == "0" || error == "0x0" -> WriteResult.Success
                else -> WriteResult.Failed(body.take(80))
            }
        }
    }

    /** set.cgi / add.cgi variant (returns an HTML page, not JSON). Used by MAC filter etc. */
    fun htmlWrite(cgi: String, node: String, page: String, params: Map<String, String>): WriteResult {
        val token = tokenFrom(page) ?: return WriteResult.Failed("no token")
        val fb = FormBody.Builder()
        params.forEach { (k, v) -> fb.add(k, v) }
        fb.add("x.X_HW_Token", token)
        val url = "$base/$cgi?x=$node&RequestFile=$page"
        val req = Request.Builder().url(url)
            .header("Cookie", sessionCookie ?: return WriteResult.Failed("no session"))
            .header("Referer", "$base/$page")
            .post(fb.build()).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (body.contains("Waiting...")) return WriteResult.Failed("session")
            return when {
                body.contains("""ErrCode = "0x1"""") -> WriteResult.Denied("0x1")
                Regex("""ErrCode\s*=\s*"(0x[0-9a-fA-F]+)"""").find(body)
                    ?.groupValues?.get(1)?.let { it != "0x0" } == true ->
                    WriteResult.ParamError(Regex("""ErrCode\s*=\s*"([^"]+)"""").find(body)!!.groupValues[1])
                resp.code == 200 -> WriteResult.Success
                else -> WriteResult.Failed("http ${resp.code}")
            }
        }
    }

    /**
     * Like [htmlWrite] but the caller supplies the whole query string, because some
     * WebUI pages address several nodes at once (the WLAN save uses y / k / c1 / c2 / w).
     */
    fun cgiWrite(
        cgi: String,
        query: String,
        page: String,
        params: Map<String, String>,
        requestFile: String = page.substringBefore('?')   // ?2G in RequestFile => 404 after the write
    ): WriteResult {
        val token = tokenFrom(page) ?: return WriteResult.Failed("no token")
        val fb = FormBody.Builder()
        params.forEach { (k, v) -> fb.add(k, v) }
        fb.add("x.X_HW_Token", token)
        val req = Request.Builder()
            .url(if (query.isBlank()) "$base/$cgi?RequestFile=$requestFile"
                 else "$base/$cgi?$query&RequestFile=$requestFile")
            .header("Cookie", sessionCookie ?: return WriteResult.Failed("no session"))
            .header("Referer", "$base/$page")
            .post(fb.build()).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (body.contains("Waiting...")) throw SessionExpired()
            val err = Regex("""ErrCode\s*=\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
            return when {
                err == null -> if (resp.code == 200) WriteResult.Success
                              else WriteResult.Failed("http ${resp.code}")
                err == "0x0" -> WriteResult.Success
                err == "0x1" -> WriteResult.Denied(err)
                else -> WriteResult.ParamError(err)
            }
        }
    }

    /** One entry of the router's MAC filter list. */
    data class MacEntry(val domain: String, val mac: String, val alias: String)

    /** The MAC-filter entries currently on the router (the real "blocked" list). */
    fun macFilterList(): List<MacEntry> {
        val html = getPage(MAC_FILTER_PAGE)
        return Regex("""new\s+stMacFilter\((.*?)\)""").findAll(html).mapNotNull { m ->
            val a = splitJsArgs(m.groupValues[1]).map { unescapeHx(it) }
            if (a.size >= 3) MacEntry(a[0], a[1].uppercase(), a[2]) else null
        }.toList()
    }

    /**
     * Remove one MAC-filter entry (un-block). The WebUI posts to del.cgi with a form field
     * *named* after the instance domain and an empty value.
     */
    fun delMacFilter(domain: String): WriteResult {
        val token = tokenFrom(MAC_FILTER_PAGE) ?: return WriteResult.Failed("no token")
        val fb = FormBody.Builder().add(domain, "").add("x.X_HW_Token", token)
        val req = Request.Builder()
            .url("$base/del.cgi?RequestFile=$MAC_FILTER_PAGE")
            .header("Cookie", sessionCookie ?: return WriteResult.Failed("no session"))
            .header("Referer", "$base/$MAC_FILTER_PAGE")
            .post(fb.build()).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (body.contains("Waiting...")) return WriteResult.Failed("session")
            if (body.contains("""ErrCode = "0x1"""")) return WriteResult.Denied("0x1")
            return if (resp.code == 200) WriteResult.Success else WriteResult.Failed("http ${resp.code}")
        }
    }

    /** Read the MAC-filter mode so we never risk a whitelist lock-out. true = whitelist. */
    fun macFilterIsWhitelist(): Boolean {
        val html = runCatching { getPage("html/bbsp/macfilter/macfilter.asp") }.getOrNull() ?: return false
        val m = Regex("""var\s+WhiteList\s*=\s*(\d)""").find(html)?.groupValues?.get(1)
        return m == "1"
    }

    /** Upload an edited hw_ctree.xml (reboots the router). true if accepted. */
    /**
     * Upload an edited hw_ctree.xml. VERIFIED against the device — the router is picky:
     *  - the "onttoken" part MUST come BEFORE the file part, and
     *  - the file part must be sent as text/xml (octet-stream is rejected).
     * With the wrong recipe it answers HTTP 200 with the error page s1012
     * ("Failed to update the configuration file"), which is why this used to fail silently.
     * Success answers with s131a ("Successfully updated ... restarting").
     */
    fun uploadConfig(xml: String): Boolean {
        // the WebUI prepares the file operation first; harmless and keeps us in step with it
        runCatching { getPage("html/ssmp/common/StartFileLoad.asp") }
        val token = tokenFrom("html/ssmp/cfgfile/cfgfile.asp") ?: return false

        // The router's multipart parser is primitive: OkHttp's MultipartBody adds a
        // "Content-Length" header INSIDE each part, and that alone makes the router reject the
        // file (s1012). curl doesn't send it — so we build the body by hand, byte for byte.
        val b = "----------------------------OnuBoundary7391"
        val head = ("--$b\r\n" +
            "Content-Disposition: form-data; name=\"onttoken\"\r\n\r\n" +
            "$token\r\n" +
            "--$b\r\n" +
            "Content-Disposition: form-data; name=\"browse\"; filename=\"hw_ctree.xml\"\r\n" +
            "Content-Type: text/xml\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        val tail = "\r\n--$b--\r\n".toByteArray(Charsets.ISO_8859_1)
        val full = head + xml.toByteArray(Charsets.UTF_8) + tail
        val multipart = full.toRequestBody("multipart/form-data; boundary=$b".toMediaType())

        val req = Request.Builder()
            .url("$base/cfgfileupload.cgi?RequestFile=html/ssmp/reset/reset.asp&FileType=config")
            .header("Cookie", sessionCookie ?: return false)
            .header("Referer", "$base/html/ssmp/cfgfile/cfgfile.asp")
            .post(multipart).build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    body.contains("s131a") -> true          // accepted, router is restarting
                    body.contains("s1012") -> false         // rejected
                    else -> resp.code in 200..399
                }
            }
        } catch (e: Exception) { true /* connection drops as the router reboots */ }
    }

    /** Optical power: Pair(rxDbm, txDbm) as strings, from opticinfo.asp stOpticInfo(...). */
    fun optic(): Pair<String, String>? {
        val html = runCatching { getPage("html/amp/opticinfo/opticinfo.asp") }.getOrNull() ?: return null
        val m = Regex("""new\s+stOpticInfo\((.*?)\)""").find(html) ?: return null
        val a = splitJsArgs(m.groupValues[1]).map { unescapeHx(it).trim() }
        if (a.size < 4) return null
        return a[3] to a[2]   // rx, tx
    }

    /** Uptime in seconds, from deviceinfo.asp (uptime = 'N'). */
    fun uptimeSeconds(): Long {
        val html = runCatching { getPage("html/ssmp/deviceinfo/deviceinfo.asp") }.getOrNull() ?: return -1
        return Regex("""uptime\s*=\s*'(\d+)'""").find(html)?.groupValues?.get(1)?.toLongOrNull() ?: -1
    }

    /** Device serial number from deviceinfo.asp (SerialNumber var or stDeviceInfo arg2). */
    fun serialNumber(): String? {
        val html = runCatching { getPage("html/ssmp/deviceinfo/deviceinfo.asp") }.getOrNull() ?: return null
        Regex("""SerialNumber\s*=\s*"([^"]+)"""").find(html)?.let { return unescapeHx(it.groupValues[1]) }
        Regex("""new\s+stDeviceInfo\("InternetGatewayDevice\.DeviceInfo","([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(html)?.let { return unescapeHx(it.groupValues[1]) }
        return null
    }

    /** WAN totals: Pair(downBytes, upBytes) from waninfo.asp WANStats(...).
     *  Argument order on this firmware (rev_wan.asp): WANStats(domain,
     *  txPackets, txBytes, txErrors, txDiscarded, rxPackets, rxBytes, rxErrors, rxDiscarded)
     *  → a[2] is UPLOAD (txBytes), a[6] is DOWNLOAD (rxBytes). */
    fun wanBytes(): Pair<Long, Long>? {
        val html = runCatching { getPage("html/bbsp/waninfo/waninfo.asp") }.getOrNull() ?: return null
        var best: Pair<Long, Long>? = null
        for (m in Regex("""new\s+WANStats\((.*?)\)""").findAll(html)) {
            val a = splitJsArgs(m.groupValues[1]).map { unescapeHx(it).trim() }
            if (a.size < 7) continue
            val down = a[6].toLongOrNull() ?: 0L   // rxBytes = received = download
            val up = a[2].toLongOrNull() ?: 0L     // txBytes = transmitted = upload
            // pick the connection with the most traffic (the active internet WAN)
            if (best == null || (down + up) > (best!!.first + best!!.second)) best = down to up
        }
        return best
    }

    /** Router Wi-Fi names (SSIDs), read live from the basic/info pages. */
    fun ssidList(): List<String> {
        val out = LinkedHashSet<String>()
        val pages = listOf(
            "html/amp/wlaninfo/wlaninfo.asp",
            "html/amp/wlanbasic/WlanBasic.asp?2G",
            "html/amp/wlanbasic/WlanBasic.asp?5G",
            "html/bbsp/wlaninfo/wlaninfo.asp",
            "html/amp/wlanbasic/WlanBasic.asp"
        )
        for (page in pages) {
            val html = runCatching { getPage(page) }.getOrNull() ?: continue
            // 1) JavaScript stWlan / stWlanInfo / stWlanBasic object constructors
            Regex("""new\s+stWlan(?:Basic|Info)?\((.*?)\)""").findAll(html).forEach { m ->
                val args = splitJsArgs(m.groupValues[1]).map { unescapeHx(it).trim() }
                for (arg in args) {
                    if (arg.length in 2..32 && !arg.startsWith("InternetGatewayDevice") &&
                        !arg.startsWith("LANDevice") && !arg.all { it.isDigit() } &&
                        !arg.contains("=") && !arg.contains(";") && !arg.contains("/")) {
                        out += arg
                    }
                }
            }
            // 2) explicit var SSID = "..."
            Regex("""(?:var\s+)?SSID\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                m.groupValues[1].takeIf { it.isNotBlank() && it != "--" }?.let { out += unescapeHx(it) }
            }
            // 3) input tag values
            Regex("""(?:id|name)="ssid\d*"[^>]*value="([^"]+)"""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                m.groupValues[1].takeIf { it.isNotBlank() && it != "--" }?.let { out += unescapeHx(it) }
            }
        }
        return out.toList()
    }

    // ---- Wi-Fi: live read + live write (no config file, no reboot) ----
    /**
     * Both radios as the WebUI sees them. WlanBasic.asp ships them as a JS array:
     *
     *   var WlanArr = new Array(new stWlan("…WLANConfiguration.1","ath0","1","WE_A8C715","1",
     *                 "32","1","WPAand11i",…), new stWlan("…WLANConfiguration.5","ath4",…), null);
     *   var wpaPskKey = new Array(new stPreSharedKey("…WLANConfiguration.1.PreSharedKey.1",
     *                 "<psk>",""), …);
     *
     * Field order comes from the page's own stWlan() constructor.
     */
    fun wlanRadios(): List<WlanRadio> {
        val html = getPage(WLAN_PAGE + "?2G")
        val keys = Regex("""new\s+stPreSharedKey\((.*?)\)""").findAll(html).mapNotNull { m ->
            val a = splitJsArgs(m.groupValues[1]).map { unescapeHx(it) }
            if (a.size >= 2) a[0] to a[1] else null
        }.toMap()
        return Regex("""new\s+stWlan\((.*?)\)""").findAll(html).mapNotNull { m ->
            val a = splitJsArgs(m.groupValues[1]).map { unescapeHx(it) }
            if (a.size < 24) return@mapNotNull null
            val domain = a[0]
            if (!domain.contains("WLANConfiguration.")) return@mapNotNull null
            WlanRadio(
                domain = domain,
                // LowerLayers ends in WiFi.Radio.1 (2.4 GHz) or .2 (5 GHz)
                band = if (a[23].endsWith(".2")) "5G" else "2.4G",
                ifName = a[1],
                ssid = a[3],
                enabled = a[2] == "1",
                advertised = a[4] == "1",
                deviceNum = a[5],
                wmm = a[6],
                beaconType = a[7],
                authMode = when (a[7]) {
                    "11i" -> a[15]                     // IEEE11iAuthenticationMode
                    "WPA" -> a[13]                     // WPAAuthenticationMode
                    else -> a[17]                      // X_HW_WPAand11iAuthenticationMode
                },
                encModes = when (a[7]) {
                    "11i" -> a[14]
                    "WPA" -> a[12]
                    else -> a[16]
                },
                rekey = a[18],
                password = keys["$domain.PreSharedKey.1"].orEmpty()
            )
        }.distinctBy { it.domain }.toList()
    }

    /**
     * Change a radio's name / password live — the same call the WebUI's Save button makes,
     * so it applies immediately and the router does NOT reboot (only that radio re-associates).
     *
     * The page (CfgMode TEDATA2, HiLinkRoll=1) addresses five nodes at once:
     *   y  = the WLANConfiguration instance          k  = its PreSharedKey.1
     *   w  = WifiCoverSetWlanBasic (mesh sync)       c1/c2 = WLANConfigAction begin/commit
     * Security fields are echoed back from [radio] so nothing else changes.
     */
    fun setWifi(
        radio: WlanRadio,
        ssid: String = radio.ssid,
        password: String = radio.password,
        advertised: Boolean = radio.advertised,
        enabled: Boolean = radio.enabled
    ): WriteResult {
        val d = radio.domain
        val query = buildString {
            append("c1=").append(CFG_ACTION)
            append("&w=").append(WIFI_COVER_NODE)
            append("&y=").append(d)
            append("&k=").append(radio.pskNode)
            append("&c2=").append(CFG_ACTION)
        }
        val params = linkedMapOf(
            "y.Enable" to if (enabled) "1" else "0",
            "y.SSID" to ssid,
            "y.SSIDAdvertisementEnabled" to if (advertised) "1" else "0",
            "y.WMMEnable" to radio.wmm,
            "y.X_HW_AssociateNum" to radio.deviceNum,
            "y.BeaconType" to radio.beaconType,
            "y.X_HW_GroupRekey" to radio.rekey,
            "k.PreSharedKey" to password,
            "c1.ActionType" to "0",
            "c2.ActionType" to "1",
            "c2.SSIDList" to radio.instance
        )
        // the auth/encryption pair is named after the beacon type
        when (radio.beaconType) {
            "11i" -> {
                params["y.IEEE11iAuthenticationMode"] = radio.authMode
                params["y.IEEE11iEncryptionModes"] = radio.encModes
            }
            "WPA" -> {
                params["y.WPAAuthenticationMode"] = radio.authMode
                params["y.WPAEncryptionModes"] = radio.encModes
            }
            else -> {
                params["y.X_HW_WPAand11iAuthenticationMode"] = radio.authMode
                params["y.X_HW_WPAand11iEncryptionModes"] = radio.encModes
            }
        }
        return cgiWrite("set.cgi", query, "$WLAN_PAGE?2G", params)
    }

    // ---- extra / guest SSIDs ----
    /**
     * Add a second network on [band] — this firmware (TEDATA2) has no Guest Wi-Fi page of its
     * own, so a guest network here is an extra WLANConfiguration instance with client
     * isolation turned on: its clients reach the internet but not each other or the LAN.
     *
     * addcfg.cgi addresses the *container*, and the router picks the next free instance
     * (2..4 on 2.4 GHz, 6..8 on 5 GHz). It carries no PreSharedKey alias, so the password is
     * set by a normal [setWifi] on the new instance right afterwards.
     */
    fun addSsid(band: String, ssid: String, isolate: Boolean = true): WriteResult {
        val radio = if (band == "5G") "InternetGatewayDevice.LANDevice.1.WiFi.Radio.2"
                    else "InternetGatewayDevice.LANDevice.1.WiFi.Radio.1"
        val params = linkedMapOf(
            "y.Enable" to "1",
            "y.SSID" to ssid,
            "y.SSIDAdvertisementEnabled" to "1",
            "y.WMMEnable" to "1",
            "y.LowerLayers" to radio,
            "y.X_HW_AssociateNum" to "32",
            "y.BeaconType" to "11i",
            "y.IEEE11iAuthenticationMode" to "PSKAuthentication",
            "y.IEEE11iEncryptionModes" to "AESEncryption",
            "y.X_HW_GroupRekey" to "3600",
            "y.IsolationEnable" to if (isolate) "1" else "0"
        )
        return cgiWrite("addcfg.cgi",
            "y=InternetGatewayDevice.LANDevice.1.WLANConfiguration", "$WLAN_PAGE?2G", params)
    }

    /**
     * Flip the per-SSID extras that live on its X_HW_AttachConf child. The firmware keeps the
     * real guest switch there — every WLANConfigurationInstance in hw_ctree.xml carries
     *
     *   <X_HW_AttachConf … X_HW_IsolationEnable="0" X_HW_PortIsolation="0"
     *                      X_HW_GuestNetwork="0" GuestValidTime="0" GuestRestTime="0" …/>
     *
     * The WebUI addresses this node as q=<domain>.X_HW_AttachConf and already writes
     * q.X_HW_IsolationEnable / q.X_HW_UpRateLimit to it, so the path is the vendor's own —
     * X_HW_GuestNetwork is simply an attribute its page never exposes.
     *
     * [validMinutes]/[restMinutes] are the firmware's guest time limits; 0 = no limit.
     */
    fun setAttachConf(
        domain: String,
        guestNetwork: Boolean? = null,
        isolate: Boolean? = null,
        validMinutes: Int? = null,
        restMinutes: Int? = null,
        upKbps: Int? = null,
        downKbps: Int? = null
    ): WriteResult {
        val params = linkedMapOf<String, String>()
        guestNetwork?.let { params["q.X_HW_GuestNetwork"] = if (it) "1" else "0" }
        isolate?.let {
            params["q.X_HW_IsolationEnable"] = if (it) "1" else "0"
            params["q.X_HW_PortIsolation"] = if (it) "1" else "0"
        }
        validMinutes?.let { params["q.GuestValidTime"] = it.toString() }
        restMinutes?.let { params["q.GuestRestTime"] = it.toString() }
        upKbps?.let { params["q.X_HW_UpRateLimit"] = it.toString() }
        downKbps?.let { params["q.X_HW_DownRateLimit"] = it.toString() }
        if (params.isEmpty()) return WriteResult.Failed("nothing to set")
        return cgiWrite("set.cgi", "q=$domain.X_HW_AttachConf", "$WLAN_PAGE?2G", params)
    }

    /**
     * Remove SSID instances. The WebUI does this in two steps and only the pair works:
     *   1. set.cgi ParallelDelSsid with y.DeleteInstArray = "2,6"  (tell the radios)
     *   2. del.cgi with one empty field *named* after each instance domain (do the delete)
     * Sending only the first leaves the SSID in place, which is why Remove looked dead.
     */
    fun deleteSsids(domains: List<String>): WriteResult {
        if (domains.isEmpty()) return WriteResult.Failed("nothing to delete")
        val instances = domains.map { it.substringAfterLast('.') }
        val step1 = cgiWrite("set.cgi",
            "y=InternetGatewayDevice.X_HW_DEBUG.AMP.ParallelDelSsid", "$WLAN_PAGE?2G",
            mapOf("y.DeleteInstArray" to instances.joinToString(",")))
        if (step1 is WriteResult.Denied) return step1
        return cgiWrite("del.cgi", "", "$WLAN_PAGE?2G",
            domains.associateWith { "" })
    }

    /** Reboot — VERIFIED node from reset.asp (Reboot() -> set.cgi ResetBoard, token only). */
    fun reboot(): Boolean = try {
        htmlWrite("set.cgi", "InternetGatewayDevice.X_HW_DEBUG.SMP.DM.ResetBoard",
            "html/ssmp/reset/reset.asp", emptyMap())
        true
    } catch (e: Exception) { true } // connection usually drops as the router goes down

    /** Lightweight liveness/session check. */
    fun pingAlive(): Boolean = try { getPage("html/get_swm_status.asp"); true } catch (e: Exception) { false }

    // ---- config file download (for options with no WebUI page) ----
    fun downloadConfig(): String {
        val token = tokenFrom("html/ssmp/cfgfile/cfgfile.asp") ?: throw SessionExpired()
        val fb = FormBody.Builder().add("x.X_HW_Token", token).build()
        val req = Request.Builder()
            .url("$base/cfgfiledown.cgi?&RequestFile=html/ssmp/cfgfile/cfgfile.asp")
            .header("Cookie", sessionCookie ?: throw SessionExpired())
            .post(fb).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code != 200 || !body.contains("<InternetGatewayDevice")) throw SessionExpired()
            return body
        }
    }

    private var configCache: String? = null
    private var configCacheAt = 0L

    /** Config download shared + cached briefly across read-only callers (never modifies router). */
    fun getConfigCached(): String {
        val now = System.currentTimeMillis()
        if (configCache == null || now - configCacheAt > CONFIG_CACHE_TTL) {
            configCache = downloadConfig()
            configCacheAt = now
        }
        return configCache ?: throw SessionExpired()
    }

    /** Full device history from <hostsInstance> in the config — every device that ever connected. */
    fun hostsFromConfig(): List<Host> {
        val xml = getConfigCached()
        return Regex("""<hostsInstance\b([^>]*)/?>""").findAll(xml).mapNotNull { m ->
            val attrs = Regex("""(\w+)="([^"]*)"""").findAll(m.groupValues[1])
                .associate { it.groupValues[1] to it.groupValues[2] }
            val rawMac = attrs["MACAddress"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val mac = rawMac.chunked(2).joinToString(":").uppercase()
            val dhcp = attrs["DhcpName"].orEmpty().trim()
            val alias = attrs["Name"].orEmpty().trim()
            val name = when {
                dhcp.isNotBlank() -> dhcp
                alias.isNotBlank() -> alias
                else -> mac
            }
            val ifc = attrs["ConnectInterface"].orEmpty()
            val conn = when {
                ifc.startsWith("SSID1") -> "2.4G"
                ifc.startsWith("SSID5") -> "5G"
                ifc.startsWith("LAN") -> "LAN"
                else -> ifc
            }
            Host(mac, name, conn, attrs["IsOnlineFlag"] == "1")
        }.distinctBy { it.mac }.toList()
    }

    // ---- low level POST helper (used by login) ----
    private fun post(path: String, body: okhttp3.RequestBody, cookie: String?, raw: Boolean): String? {
        val b = Request.Builder().url("$base$path").post(body)
        if (cookie != null) b.header("Cookie", cookie)
        return try {
            client.newCall(b.build()).execute().use { it.body?.string() }
        } catch (e: Exception) { null }
    }

    // ---- helpers ----
    private fun stripBom(s: String) = s.removePrefix("﻿").let {
        if (it.startsWith("ï»¿")) it.substring(3) else it
    }

    /** Split a comma-separated list of "quoted" JS args, honoring quotes. */
    private fun splitJsArgs(s: String): List<String> {
        val rx = Regex(""""((?:[^"\\]|\\.)*)"""")
        return rx.findAll(s).map { it.groupValues[1] }.toList()
    }

    /** Decode Huawei's \xNN escaping (e.g. \x2e -> '.', \x3a -> ':'). */
    private fun unescapeHx(s: String): String {
        if (!s.contains("\\x")) return s
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            if (i + 3 < s.length && s[i] == '\\' && s[i + 1] == 'x') {
                val code = s.substring(i + 2, i + 4).toIntOrNull(16)
                if (code != null) { sb.append(code.toChar()); i += 4; continue }
            }
            sb.append(s[i]); i++
        }
        return sb.toString()
    }

    private fun buildTrustAllClient(connect: Long, read: Long): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), SecureRandom())
        }
        return OkHttpClient.Builder()
            .apply { boundSockets?.let { socketFactory(it) } }
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .connectTimeout(connect, TimeUnit.SECONDS)
            .readTimeout(read, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        const val MAC_FILTER_PAGE = "html/bbsp/macfilter/macfilter.asp"
        const val WLAN_PAGE = "html/amp/wlanbasic/WlanBasic.asp"
        const val DEV_DETAIL_PAGE = "html/bbsp/userdevinfo/userdetdevinfo.asp"
        private const val CFG_ACTION = "InternetGatewayDevice.X_HW_DEBUG.WLANConfigAction"
        private const val WIFI_COVER_NODE =
            "InternetGatewayDevice.X_HW_DEBUG.AMP.WifiCoverSetWlanBasic"
        private const val CONFIG_CACHE_TTL = 5 * 60 * 1000L
    }
}
