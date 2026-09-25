package com.hg8145v5.manager.vm

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hg8145v5.manager.BuildConfig
import com.hg8145v5.manager.data.CredStore
import com.hg8145v5.manager.data.Creds
import com.hg8145v5.manager.net.AppRelease
import com.hg8145v5.manager.net.Bloom
import com.hg8145v5.manager.net.DevStat
import com.hg8145v5.manager.net.Device
import com.hg8145v5.manager.net.DnsResult
import com.hg8145v5.manager.net.MtuProbe
import com.hg8145v5.manager.net.MtuTester
import com.hg8145v5.manager.net.NetworkMonitor
import com.hg8145v5.manager.net.PingStats
import com.hg8145v5.manager.net.RouterApi
import com.hg8145v5.manager.net.SpeedCheck
import com.hg8145v5.manager.net.WifiLink
import com.hg8145v5.manager.net.WlanRadio
import com.hg8145v5.manager.net.WriteResult
import com.hg8145v5.manager.net.UpdateCheck
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class Conn { Disconnected, Connecting, Connected }

/**
 * One radio (2.4/5 GHz) as read from the router config. [width] is the Huawei `X_HW_HT20` code:
 * 0 = auto 20/40, 1 = 20 MHz, 2 = 40 MHz, 3 = auto 20/40/80, 6 = 80 MHz.
 */
data class WifiRadio(
    val band: String, val channel: Int, val autoChannel: Boolean, val width: Int, val power: Int
)

class RouterViewModel(app: Application) : AndroidViewModel(app) {

    private val store = CredStore(app)
    private var api = RouterApi()
    var routerHost by mutableStateOf("")
    var routerModel by mutableStateOf("")
    private val net = NetworkMonitor(app)

    /** bumped every time Wi-Fi comes back, so waiting loops can retry immediately */
    private var netEpoch = 0

    var lang by mutableStateOf(store.lang); private set
    var themeMode by mutableStateOf(store.themeMode); private set
    var conn by mutableStateOf(Conn.Disconnected); private set
    var loggedIn by mutableStateOf(false); private set
    var screen by mutableStateOf("home")

    val devices = mutableStateListOf<Device>()
    var deviceCount by mutableStateOf(0); private set
    val speedLabel = mutableStateMapOf<String, String>()   // mac(upper) -> "5"/"10"/"20"/"Max"
    val deviceAlias = mutableStateMapOf<String, String>()  // mac(upper) -> user's custom name (app-only)

    // last successful device fetch — gates how often refresh() actually re-poll the router
    private var lastDevicesFetch = 0L

    // ---- auto-update ----
    var updateChecking by mutableStateOf(false); private set
    var updateAvailable by mutableStateOf(false); private set
    var updateDownloading by mutableStateOf(false); private set
    var updateRelease by mutableStateOf<AppRelease?>(null); private set
    var updateMsg by mutableStateOf(""); private set
    /** the "new version" popup - set as soon as a check finds one, so nobody misses an update */
    var updatePrompt by mutableStateOf(false); private set

    // live status (read directly from the router, no computation)
    var rxDbm by mutableStateOf<String?>(null); private set
    var txDbm by mutableStateOf<String?>(null); private set
    var uptimeSec by mutableStateOf(-1L); private set
    var downBytes by mutableStateOf(-1L); private set
    var upBytes by mutableStateOf(-1L); private set
    var serial by mutableStateOf<String?>(null); private set
    var ssids by mutableStateOf(listOf<String>()); private set
    val ssidMain: String get() = ssids.firstOrNull() ?: ""

    var fastLogin by mutableStateOf(store.fastLogin); private set
    fun setFast(v: Boolean) {
        fastLogin = v; store.fastLogin = v
        if (!v) store.clear()
    }
    fun clearSaved() { store.clear(); fastLogin = false }

    fun setTheme(m: String) { themeMode = m; store.themeMode = m }

    var toast by mutableStateOf<String?>(null)
    var restarting by mutableStateOf(false); private set
    var restartTitle by mutableStateOf(""); private set
    var restartMsg by mutableStateOf(""); private set
    var restartStep by mutableStateOf(0); private set

    val savedCreds: Creds? get() = store.load()

    init {
        // Pin traffic to Wi-Fi and react the moment it returns (e.g. after the router reboots).
        net.start { up ->
            api.useSockets(net.socketFactory())
            if (up) {
                netEpoch++
                if (loggedIn && !restarting && conn != Conn.Connected) heartbeat()
            } else if (!restarting) {
                conn = Conn.Disconnected
            }
        }

        // restore saved per-device speed limits
        store.speeds.split(',').filter { it.contains('=') }.forEach {
            val (m, l) = it.split('=', limit = 2); if (m.isNotBlank()) speedLabel[m] = l
        }
        // restore custom device names (app-only aliases)
        store.aliases.split(',').filter { it.contains('=') }.forEach {
            val (m, n) = it.split('=', limit = 2); if (m.isNotBlank()) deviceAlias[m] = n
        }

        // show the last known devices instantly (stored locally — no router round-trip),
        // the background polling refreshes them while the user is on the page.
        decodeDevices(store.deviceCache).takeIf { it.isNotEmpty() }?.let {
            devices.addAll(it); deviceCount = it.count { d -> d.online }
        }
    }

    fun toggleLang() { lang = if (lang == "ar") "en" else "ar"; store.lang = lang }
    fun chooseLang(l: String) { lang = l; store.lang = l }
    private fun t(en: String, ar: String) = if (lang == "ar") ar else en
    fun clearToast() { toast = null }
    private fun toast(s: String) { toast = s }

    // ---- session ----
    fun login(ip: String, user: String, pass: String) {
        val save = store.fastLogin
        conn = Conn.Connecting
        api = RouterApi(ip, user, pass)
        routerHost = ip
        api.useSockets(net.socketFactory())
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { api.login() }.getOrDefault(false) }
            if (ok) {
                if (save) store.save(Creds(ip, user, pass))
                loggedIn = true; conn = Conn.Connected; refresh()
            } else { conn = Conn.Disconnected; toast(t("Login failed — check credentials", "فشل الدخول — راجع البيانات")) }
        }
    }

    fun logout() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { if (api.hasSession()) api.logoutNow() } }
            api.clearSession(); loggedIn = false; conn = Conn.Disconnected; devices.clear(); screen = "home"
            store.clear(); fastLogin = false
            store.deviceCache = ""; lastDevicesFetch = 0L
        }
    }

    private suspend fun <T> withSession(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: RouterApi.SessionExpired) { if (api.login()) block() else throw e }
    }

    /** MAC(upper) -> MacFilter instance domain. The real blocked list, read from the router. */
    val blocked = mutableStateMapOf<String, String>()

    /**
     * Pull devices from the router and MERGE into what's shown, replacing atomically:
     *  - an empty/partial read never wipes the current list,
     *  - devices that were known before but aren't in the live table stay visible (as offline),
     *  - the merged snapshot is persisted locally so it survives restarts.
     * State is only touched when the data actually changed — identical polls leave the UI alone
     * (no recomposition, no disk writes), which keeps scrolling smooth.
     */
    private suspend fun fetchDevices(): Boolean = try {
        val list = withSession { api.getDevices() }
        if (list.isNotEmpty()) {
            val prev = devices.toList().associateBy { it.mac.uppercase() }
            val merged = LinkedHashMap<String, Device>()
            for (d in list) merged[d.mac.uppercase()] = d
            for ((mac, d) in prev) if (mac !in merged) merged[mac] = d.copy(online = false)
            val final = merged.values.toList()
            lastDevicesFetch = System.currentTimeMillis()
            if (final != devices) {
                devices.clear(); devices.addAll(final)
                deviceCount = list.count { it.online }
                store.deviceCache = encodeDevices(final)
            }
        }
        // blocked list is live state — refresh separately; failures keep the previous view
        runCatching { withSession { api.macFilterList() } }.getOrNull()?.let { l ->
            val fresh = l.associate { it.mac to it.domain }
            if (fresh != blocked.toMap()) {
                blocked.clear(); fresh.forEach { (m, dom) -> blocked[m] = dom }
            }
        }
        true
    } catch (e: Exception) { false }

    /** Light entrada gate: skip the network when we just fetched (<1 min ago) so switching
     *  Devices tabs / pages never re-downloads the config or blanks the list. */
    fun refresh() {
        if (conn == Conn.Connected && System.currentTimeMillis() - lastDevicesFetch < 60_000) return
        viewModelScope.launch {
            val newConn = if (fetchDevices()) Conn.Connected else Conn.Disconnected
            if (newConn != conn) conn = newConn
        }
        loadStatus()
        refreshDevStats()
    }

    private fun loadStatus() {
        viewModelScope.launch {
            runCatching { withSession { api.optic() } }.getOrNull()?.let { rxDbm = it.first; txDbm = it.second }
            runCatching { withSession { api.uptimeSeconds() } }.getOrNull()?.let { if (it >= 0) uptimeSec = it }
            runCatching { withSession { api.wanBytes() } }.getOrNull()?.let { downBytes = it.first; upBytes = it.second }
            runCatching { withSession { api.serialNumber() } }.getOrNull()?.let { if (!it.isNullOrBlank()) serial = it }
            runCatching { api.productName() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { routerModel = it }
        }
        loadConfigInfo()
    }

    // ---- per-device link stats (RSSI / rate / online duration) ----
    /** MAC(upper) -> live link stats, refreshed in the background. */
    var devStats by mutableStateOf(mapOf<String, DevStat>()); private set
    private var lastStatsFetch = 0L

    /**
     * Pull RSSI / negotiated rate / online time for every device.
     *
     * Runs on its own coroutine and never blocks the device list: the list renders from
     * [devices] as soon as it arrives and the numbers fill in a moment later. Throttled to
     * one fetch per 20 s so scrolling or tab switches never hit the router.
     */
    fun refreshDevStats(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastStatsFetch < 20_000) return
        lastStatsFetch = now
        viewModelScope.launch {
            runCatching { withSession { api.deviceStats() } }
                .onSuccess { if (it.isNotEmpty()) devStats = it }
                .onFailure { lastStatsFetch = 0L }      // let the next beat retry
        }
    }

    private var beat = 0

    /**
     * Periodic keep-alive: one light device poll; re-logs in automatically when the session
     * or the connection dropped. The heavier status reads run only every 4th beat (~1 min)
     * so we don't hammer the router.
     */
    fun heartbeat() {
        if (!loggedIn || restarting) return
        viewModelScope.launch {
            val ok = fetchDevices()
            conn = if (ok) Conn.Connected else Conn.Disconnected
            if (ok && (++beat % 4 == 0)) loadStatus()
            refreshDevStats()          // cheap, and off the device-list path
        }
    }

    fun cycleConn() {
        when (conn) {
            Conn.Connected -> { conn = Conn.Disconnected; toast(t("Disconnected", "انقطع الاتصال")) }
            else -> { conn = Conn.Connecting; refresh() }
        }
    }

    // ---- auto-update ----
    private fun updateCheck() = UpdateCheck(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO, BuildConfig.VERSION_CODE)

    /** Check GitHub Releases for a newer build. [auto] keeps it silent (no error text). */
    fun checkUpdate(auto: Boolean = false) {
        if (updateChecking) return
        val chk = updateCheck()
        if (!chk.configured()) return                       // not wired to a repo yet — nothing to do
        updateChecking = true
        if (!auto) updateMsg = t("Checking for updates…", "جاري التحقق من التحديثات…")
        if (auto) updateMsg = ""
        viewModelScope.launch {
            when (val r = withContext(Dispatchers.IO) { chk.fetch() }) {
                UpdateCheck.Result.NotConfigured -> { /* silent */ }
                UpdateCheck.Result.UpToDate -> if (!auto)
                    updateMsg = t("You're on the latest version", "أنت على أحدث إصدار")
                is UpdateCheck.Result.Available -> {
                    updateAvailable = true; updateRelease = r.rel
                    updateMsg = t("Update ", "يوجد تحديث ") + r.rel.tagName
                    // a silent check is only silent about errors — a real update must always speak up
                    updatePrompt = true
                }
                is UpdateCheck.Result.Failed -> if (!auto)
                    updateMsg = t("Update check failed: ", "فشل فحص التحديث: ") + r.reason
            }
            updateChecking = false
        }
    }

    /** "Later": hide the popup until the app is opened again (About still shows the update). */
    fun laterUpdate() { updatePrompt = false }

    /** Download the new APK (SHA-256 verified), then hand it to the system installer. */
    fun installUpdate() {
        val rel = updateRelease ?: return
        if (updateDownloading) return
        updateDownloading = true
        updateMsg = t("Downloading update…", "جاري تنزيل التحديث…")
        viewModelScope.launch {
            val app = getApplication<Application>()
            val file = withContext(Dispatchers.IO) {
                updateCheck().download(rel, File(app.cacheDir, "updater"))
            }
            if (file == null) {
                updateDownloading = false
                // keep the update "available" and the popup open so the reason is readable and retryable
                updateAvailable = true
                updateMsg = t("Download or signature check failed", "فشل التنزيل أو فحص التوقيع")
            } else {
                updateDownloading = false
                updateMsg = t("Installing…", "جاري التثبيت…")
                updatePrompt = false
                val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
                val launch = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { app.startActivity(launch) }
                    .onFailure { updateMsg = t("Could not open installer", "تعذّر فتح المثبّت") }
            }
        }
    }

    // ---- instant control ----
    private fun instant(applying: String, ok: String, work: suspend () -> WriteResult) {
        viewModelScope.launch {
            toast(applying)
            val r = try { withSession { work() } } catch (e: Exception) { WriteResult.Failed(e.message ?: "error") }
            when (r) {
                is WriteResult.Success -> toast("$ok ✓")
                // the router answered with an error code — never report this as success
                is WriteResult.ParamError -> toast(t("Router rejected it (", "الراوتر رفضها (") + r.code + ")")
                is WriteResult.Denied -> toast(t("Not permitted (", "مرفوض (") + r.code + ")")
                is WriteResult.Failed -> if (r.reason == "whitelist")
                    toast(t("MAC filter is in whitelist mode — change it in the router first",
                            "فلتر الـMAC في وضع whitelist — غيّره من الراوتر أولاً"))
                    else toast(t("Failed: ", "فشل: ") + r.reason)
            }
        }
    }

    fun setLed(off: Boolean) = instant(t("Applying…", "جاري التطبيق…"),
        t(if (off) "LEDs off" else "LEDs on", if (off) "إطفاء اللمبات" else "تشغيل اللمبات")) {
        api.setAjax("InternetGatewayDevice.X_HW_SSMPPDT.Deviceinfo", "html/ssmp/ledcfg/ledcfg.asp",
            mapOf("x.X_HW_LedSwitch" to if (off) "1" else "0"))
    }

    fun blockDevice(mac: String, name: String) {
        instant(t("Blocking…", "جاري الحجب…"), t("Device blocked", "تم حجب الجهاز")) {
            if (api.macFilterIsWhitelist()) WriteResult.Failed("whitelist")
            else {
                api.htmlWrite("set.cgi", "InternetGatewayDevice.X_HW_Security", MAC_PAGE,
                    mapOf("x.MacFilterRight" to "1"))
                api.htmlWrite("add.cgi", "InternetGatewayDevice.X_HW_Security.MacFilter", MAC_PAGE,
                    mapOf("x.SourceMACAddress" to mac, "x.DeviceAlias" to name))
            }
        }
        viewModelScope.launch { delay(1500); refresh() }
    }

    /** Un-block a device: delete its MAC-filter entry on the router. */
    fun unblockDevice(mac: String) {
        val key = mac.uppercase()
        instant(t("Unblocking…", "جاري فك الحجب…"), t("Device unblocked", "تم فك الحجب")) {
            val dom = blocked[key]
                ?: api.macFilterList().firstOrNull { it.mac == key }?.domain
                ?: return@instant WriteResult.Failed("not found")
            api.delMacFilter(dom)
        }
        viewModelScope.launch { delay(1200); refresh() }
    }


    // ---- config-path (restart) actions ----
    fun applyDns(primary: String, secondary: String) {
        configThenReboot(t("Applying DNS…", "جاري تطبيق DNS…"), t("DNS applied", "تم تطبيق DNS")) { xml ->
            // replace every populated DNSServers entry (WAN + DHCP), whatever it currently is,
            // so switching between providers works every time — not just from the factory value.
            xml.replace(Regex("""DNSServers="[^"]+""""), """DNSServers="$primary,$secondary"""")
        }
    }

    var mtu by mutableStateOf<Int?>(null); private set
    private var lastCfgLoad = 0L

    /** Pulls MTU + Wi-Fi names from the config in one download, cached for 5 minutes. */
    fun loadConfigInfo(force: Boolean = false) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (!force && mtu != null && ssids.isNotEmpty() && now - lastCfgLoad < 300_000) return@launch
            val xml = runCatching { withSession { api.downloadConfig() } }.getOrNull() ?: return@launch
            lastCfgLoad = now
            mtuFromXml(xml)?.let { if (it > 0) mtu = it }
            ssidsFromXml(xml).takeIf { it.isNotEmpty() }?.let { ssids = it }
            wifiRadiosFromXml(xml).takeIf { it.isNotEmpty() }?.let { wifiRadios = it }
        }
    }

    fun loadMtu() = loadConfigInfo()

    // ---- Wi-Fi radio (channel / width / power), read from the same config as MTU ----
    var wifiRadios by mutableStateOf(listOf<WifiRadio>()); private set

    private fun wifiRadiosFromXml(xml: String): List<WifiRadio> =
        Regex("""<WLANConfigurationInstance\b[^>]*>""").findAll(xml).mapNotNull { m ->
            val tag = m.value
            fun attr(n: String) = Regex("""\b$n="([^"]*)"""").find(tag)?.groupValues?.get(1)
            val band = attr("X_HW_RFBand") ?: attr("X_HW_Band") ?: return@mapNotNull null
            if (attr("Enable") != "1" || attr("SSID").isNullOrBlank()) return@mapNotNull null
            WifiRadio(
                band = band,
                channel = attr("Channel")?.toIntOrNull() ?: 0,
                autoChannel = attr("AutoChannelEnable") == "1",
                width = attr("X_HW_HT20")?.toIntOrNull() ?: 0,
                power = attr("TransmitPower")?.toIntOrNull() ?: 100,
            )
        }.distinctBy { it.band }.toList()

    /** Writes channel/width/power for one band into the config, then restarts — same as MTU. */
    fun applyWifiBand(band: String, channel: Int, autoChannel: Boolean, width: Int, power: Int) {
        configThenReboot(t("Applying Wi-Fi settings…", "جاري تطبيق إعدادات الواي فاي…"),
            t("Wi-Fi radio updated", "تم تحديث إعدادات الواي فاي")) { xml ->
            var touched = false
            val out = Regex("""<WLANConfigurationInstance\b[^>]*>""").replace(xml) { m ->
                val tag = m.value
                fun attr(n: String) = Regex("""\b$n="([^"]*)"""").find(tag)?.groupValues?.get(1)
                val b = attr("X_HW_RFBand") ?: attr("X_HW_Band")
                if (b == band && attr("Enable") == "1" && !attr("SSID").isNullOrBlank()) {
                    touched = true
                    var t2 = tag
                    t2 = setAttr(t2, "Channel", if (autoChannel) "0" else channel.toString())
                    t2 = setAttr(t2, "AutoChannelEnable", if (autoChannel) "1" else "0")
                    t2 = setAttr(t2, "X_HW_HT20", width.toString())
                    t2 = setAttr(t2, "TransmitPower", power.toString())
                    t2
                } else tag
            }
            if (touched) {
                wifiRadios = wifiRadios.map {
                    if (it.band == band) WifiRadio(band, if (autoChannel) 0 else channel, autoChannel, width, power) else it
                }
                out
            } else xml
        }
    }

    /** The internet WAN's tag — found regardless of the order its attributes appear in. */
    private fun internetWanTag(xml: String): String? {
        val tags = Regex("""<WAN(?:PPP|IP)ConnectionInstance\b[^>]*>""").findAll(xml).map { it.value }.toList()
        return tags.firstOrNull { it.contains("TR069_INTERNET") && it.contains("MaxMTUSize=") }
            ?: tags.firstOrNull { it.contains("TR069_INTERNET") }
            ?: tags.firstOrNull { it.contains("MaxMTUSize=") }
    }

    private fun mtuFromXml(xml: String): Int? {
        val tag = internetWanTag(xml) ?: return null
        return Regex("""\bMaxMTUSize="(\d+)"""").find(tag)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\bMTU="(\d+)"""").find(tag)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Wi-Fi names straight from the config — page scraping used to pick up "ath0". */
    private fun ssidsFromXml(xml: String): List<String> =
        Regex("""<WLANConfigurationInstance\b[^>]*>""").findAll(xml).mapNotNull { m ->
            val tag = m.value
            val enabled = Regex("""\bEnable="([^"]*)"""").find(tag)?.groupValues?.get(1) == "1"
            val ssid = Regex("""\bSSID="([^"]*)"""").find(tag)?.groupValues?.get(1)
            if (enabled && !ssid.isNullOrBlank()) ssid else null
        }.distinct().toList()

    fun applyMtu(newMtu: Int) {
        configThenReboot(t("Applying MTU…", "جاري تطبيق MTU…"), t("MTU updated", "تم تحديث MTU")) { xml ->
            val tag = internetWanTag(xml)
            val rx = Regex("""(\bMaxMTUSize=")\d+(")""")
            if (tag == null || !rx.containsMatchIn(tag)) xml
            else {
                mtu = newMtu
                xml.replace(tag, rx.replace(tag) { m -> m.groupValues[1] + newMtu + m.groupValues[2] })
            }
        }
    }

    /**
     * How many recommendations apply right now (pure rules over live state — no text here,
     * strings are rendered by the UI). Drives the badge on the Home quick action.
     */
    val recommendationCount: Int
        get() {
            var n = 0
            if (mtu != null) {
                val target = probedMtu ?: if (probeState == "unsupported") 1450 else null
                if (target != null && target != mtu) n++
            }
            if (uptimeSec > 7 * 86400L) n++
            rxDbm?.toIntOrNull()?.let { if (it < -22) n++ }            // weak optical RX
            return n
        }

    // ---- MTU discovery (DF ping sweep from the phone) ----
    var probeState by mutableStateOf("idle"); private set   // idle | running | done | unsupported | failed
    var probedMtu by mutableStateOf<Int?>(null); private set

    fun runMtuProbe(host: String = "1.1.1.1") {
        if (probeState == "running") return
        probeState = "running"; probedMtu = null
        viewModelScope.launch {
            when (val r = MtuTester.probe(host)) {
                is MtuProbe.Found -> { probedMtu = r.mtu; probeState = "done" }
                is MtuProbe.Unsupported -> probeState = "unsupported"
                is MtuProbe.Failed -> probeState = "failed"
            }
        }
    }

    // ---- browsing speed check (all measured from this phone, no router login needed) ----
    var speedState by mutableStateOf("idle"); private set   // idle | running | done
    var dnsResults by mutableStateOf(listOf<DnsResult>()); private set
    var pingStats by mutableStateOf<PingStats?>(null); private set
    var tcpMs by mutableStateOf<Int?>(null); private set
    var ipv6Ok by mutableStateOf<Boolean?>(null); private set
    var bloom by mutableStateOf<Bloom?>(null); private set
    var bloomState by mutableStateOf("idle"); private set // idle | running
    var wifiLink by mutableStateOf<WifiLink?>(null); private set

    private val dnsServers = listOf(
        "1.1.1.1" to "Cloudflare",
        "8.8.8.8" to "Google",
        "94.140.14.14" to "AdGuard",
        "45.90.28.0" to "NextDNS",
        "9.9.9.9" to "Quad9",
    )

    private fun readWifi() {
        val wm = getApplication<Application>().getSystemService(android.content.Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager ?: return
        @Suppress("DEPRECATION") val info = runCatching { wm.connectionInfo }.getOrNull() ?: return
        @Suppress("DEPRECATION") val mbps = runCatching { info.linkSpeed }.getOrDefault(0)
        @Suppress("DEPRECATION") val rssi = runCatching { info.rssi }.getOrDefault(0)
        @Suppress("DEPRECATION") val freq = runCatching { info.frequency }.getOrDefault(0)
        @Suppress("DEPRECATION") val ssid = runCatching { info.ssid }.getOrNull()?.takeIf { it != "<unknown ssid>" }
        @Suppress("DEPRECATION") val bssid = runCatching { info.bssid }.getOrNull()
        if (freq > 0) wifiLink = WifiLink(ssid, freq, rssi, mbps, bssid)
    }

    /** Fast (no heavy download): DNS + latency/loss + TCP + IPv6 + Wi-Fi. */
    fun runSpeedCheck() {
        if (speedState == "running") return
        speedState = "running"
        readWifi()
        viewModelScope.launch {
            val dns = dnsServers.map { (s, l) -> DnsResult(s, l, SpeedCheck.dns(s)) }
            dnsResults = dns.sortedWith(compareBy(nullsLast()) { it.ms })
            pingStats = SpeedCheck.ping("1.1.1.1", 12)
            tcpMs = SpeedCheck.tcpConnect("1.1.1.1")
            ipv6Ok = SpeedCheck.ipv6Reachable()
            speedState = "done"
        }
    }

    /** Heavy: saturates the line for ~4s to measure bufferbloat (uses ~15-30 MB). */
    fun runBufferbloat() {
        if (bloomState == "running") return
        bloomState = "running"; bloom = null
        viewModelScope.launch {
            bloom = SpeedCheck.bufferbloat("1.1.1.1")
            bloomState = "idle"
        }
    }

    // ---- Wi-Fi name / password: live, no config file, no reboot ----
    /** Both radios as the router reports them right now (empty until [loadWlanRadios]). */
    var wlanLive by mutableStateOf(listOf<WlanRadio>()); private set

    /** True while a live Wi-Fi write is in flight. */
    var wifiBusy by mutableStateOf(false); private set

    /** Refresh [wlanLive] from WlanBasic.asp. Cheap enough to call when the sheet opens. */
    fun loadWlanRadios(onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            runCatching { withSession { api.wlanRadios() } }
                .onSuccess { if (it.isNotEmpty()) wlanLive = it }
            onDone?.invoke()
        }
    }

    /**
     * Change the Wi-Fi name and/or password on the router straight away.
     *
     * This is the WebUI's own Save call (set.cgi on the WLANConfiguration instance), so it
     * applies in a second or two and the router does NOT reboot — only the radios you touch
     * drop their clients. Pass [bands] = null for every enabled radio.
     *
     * The old route (download hw_ctree.xml, edit, upload, reboot) is kept in [applySsidViaConfig]
     * as a fallback for firmware that refuses the live write.
     */
    fun applyWifi(
        ssid: String? = null,
        password: String? = null,
        hidden: Boolean? = null,
        bands: Set<String>? = null
    ) {
        viewModelScope.launch {
            wifiBusy = true
            val res = runCatching {
                withSession {
                    val radios = api.wlanRadios().also { if (it.isNotEmpty()) wlanLive = it }
                    val targets = radios.filter { it.enabled && (bands == null || it.band in bands) }
                    if (targets.isEmpty()) return@withSession "no-radio"
                    var failure: String? = null
                    for (r in targets) {
                        val w = api.setWifi(
                            radio = r,
                            ssid = ssid?.trim()?.takeIf { it.isNotBlank() } ?: r.ssid,
                            password = password?.takeIf { it.isNotBlank() } ?: r.password,
                            advertised = hidden?.let { !it } ?: r.advertised
                        )
                        if (w !is WriteResult.Success) {
                            failure = when (w) {
                                is WriteResult.Denied -> "denied ${w.code}"
                                is WriteResult.ParamError -> "rejected ${w.code}"
                                is WriteResult.Failed -> w.reason
                                else -> "error"
                            }
                            break
                        }
                    }
                    failure
                }
            }.getOrElse { it.message ?: "error" }
            wifiBusy = false
            if (res == null) {
                toast(t("Wi-Fi updated — reconnect with the new settings",
                        "تم تحديث الواي فاي — أعد الاتصال بالإعدادات الجديدة"))
                ssid?.trim()?.takeIf { it.isNotBlank() }?.let { ssids = listOf(it) }
                loadWlanRadios()
            } else {
                toast(t("Could not apply", "تعذّر التطبيق") + " ($res)")
            }
        }
    }

    // ---- guest network ----
    /**
     * The guest network, if one exists. This firmware has no Guest Wi-Fi feature, so "guest"
     * means an extra SSID next to the main one on the same radio, with client isolation on.
     * Instance 1 (2.4 GHz) and 5 (5 GHz) are the primaries; anything else is extra.
     */
    val guestRadios: List<WlanRadio> get() = wlanLive.filter { it.instance !in setOf("1", "5") }

    /** The one to show when a single radio is enough (name, password…). */
    val guestRadio: WlanRadio? get() = guestRadios.firstOrNull()

    /** Bands that already have a guest network. */
    val guestBands: Set<String> get() = guestRadios.map { it.band }.toSet()

    /** True once we've actually read the radios — until then "no guest" is just "unknown". */
    val guestKnown: Boolean get() = wlanLive.isNotEmpty()

    /** Create the guest network: add the SSID, then set its password on the new instance. */
    fun createGuest(name: String, password: String, bands: Set<String> = setOf("2.4G")) {
        viewModelScope.launch {
            wifiBusy = true
            var isolationRefused = false
            val err = runCatching {
                withSession {
                    for (band in bands.sorted()) {                 // 2.4G first, then 5G
                        if (band in guestBands) continue           // already there
                        val before = api.wlanRadios().map { it.domain }.toSet()
                        val add = api.addSsid(band, name.trim(), isolate = true)
                        if (add !is WriteResult.Success) return@withSession writeError(add)
                        // the new instance has no key yet — a normal save puts one on it
                        val fresh = api.wlanRadios().also { wlanLive = it }
                        val made = fresh.firstOrNull { it.domain !in before }
                            ?: return@withSession "created, but not visible yet"
                        if (password.isNotBlank()) {
                            val set = api.setWifi(made, ssid = name.trim(), password = password)
                            if (set !is WriteResult.Success) return@withSession writeError(set)
                        }
                        // the firmware's own guest switch lives on the SSID's X_HW_AttachConf child
                        if (api.setAttachConf(made.domain, guestNetwork = true, isolate = true)
                                !is WriteResult.Success) isolationRefused = true
                    }
                    null
                }
            }.getOrElse { it.message ?: "error" }
            wifiBusy = false
            when {
                err != null -> toast(t("Could not create", "تعذّر الإنشاء") + " ($err)")
                isolationRefused -> toast(t("Created, but guest isolation was refused",
                                            "اتعملت، بس عزل الضيوف اترفض"))
                else -> toast(t("Guest network created", "تم إنشاء شبكة الضيوف"))
            }
            loadWlanRadios()
        }
    }

    /** Turn the guest network on or off without deleting it. */
    fun setGuestEnabled(on: Boolean) {
        val gs = guestRadios.ifEmpty { return }
        viewModelScope.launch {
            wifiBusy = true
            val err = runCatching {
                withSession { gs.firstNotNullOfOrNull { writeError(api.setWifi(it, enabled = on)) } }
            }.getOrElse { it.message ?: "error" }
            wifiBusy = false
            if (err == null) {
                toast(if (on) t("Guest network on", "شبكة الضيوف مفعّلة")
                      else t("Guest network off", "شبكة الضيوف متوقفة"))
                loadWlanRadios()
            } else toast(t("Could not apply", "تعذّر التطبيق") + " ($err)")
        }
    }

    /** Change the guest network's name / password. */
    fun updateGuest(name: String?, password: String?) {
        val gs = guestRadios.ifEmpty { return }
        viewModelScope.launch {
            wifiBusy = true
            val err = runCatching {
                withSession {
                    gs.firstNotNullOfOrNull { g ->
                        writeError(api.setWifi(g,
                            ssid = name?.trim()?.takeIf { it.isNotBlank() } ?: g.ssid,
                            password = password?.takeIf { it.isNotBlank() } ?: g.password))
                    }
                }
            }.getOrElse { it.message ?: "error" }
            wifiBusy = false
            if (err == null) { toast(t("Guest network updated", "تم تحديث شبكة الضيوف")); loadWlanRadios() }
            else toast(t("Could not apply", "تعذّر التطبيق") + " ($err)")
        }
    }

    /** Remove the guest network entirely. */
    fun deleteGuest(band: String? = null) {
        val gs = guestRadios.filter { band == null || it.band == band }.ifEmpty { return }
        viewModelScope.launch {
            wifiBusy = true
            val err = runCatching {
                withSession { writeError(api.deleteSsids(gs.map { it.domain })) }
            }.getOrElse { it.message ?: "error" }
            wifiBusy = false
            if (err == null) { toast(t("Guest network removed", "تم حذف شبكة الضيوف")); loadWlanRadios() }
            else toast(t("Could not remove", "تعذّر الحذف") + " ($err)")
        }
    }

    private fun writeError(w: WriteResult): String? = when (w) {
        is WriteResult.Success -> null
        is WriteResult.Denied -> "denied ${w.code}"
        is WriteResult.ParamError -> "rejected ${w.code}"
        is WriteResult.Failed -> w.reason
    }

    /** Rename the Wi-Fi network (all enabled radios get the same name), then restart.
     *  Legacy fallback — [applyWifi] does the same thing live, without a reboot. */
    fun applySsidViaConfig(newName: String) {
        configThenReboot(t("Applying Wi-Fi name…", "جاري تطبيق اسم الشبكة…"),
            t("Wi-Fi name updated", "تم تحديث اسم الشبكة")) { xml ->
            var touched = false
            val out = Regex("""<WLANConfigurationInstance\b[^>]*>""").replace(xml) { m ->
                val tag = m.value
                val enabled = Regex("""\bEnable="([^"]*)"""").find(tag)?.groupValues?.get(1) == "1"
                val ssid = Regex("""\bSSID="([^"]*)"""").find(tag)?.groupValues?.get(1)
                if (enabled && !ssid.isNullOrBlank()) {
                    touched = true
                    Regex("""(\bSSID=")[^"]*(")""").replace(tag) { g -> g.groupValues[1] + newName + g.groupValues[2] }
                } else tag
            }
            if (touched) { ssids = listOf(newName); out } else xml
        }
    }

    fun setSpeed(mac: String, kbpsDown: Int, kbpsUp: Int, label: String) {
        speedLabel[mac.uppercase()] = label
        store.speeds = speedLabel.entries.joinToString(",") { "${it.key}=${it.value}" }
        configThenReboot(t("Applying speed limit…", "جاري تطبيق السرعة…"), t("Speed limit applied", "تم تطبيق السرعة")) { xml ->
            editAttachControl(xml, mac.uppercase(), kbpsUp, kbpsDown)
        }
    }

    /** Custom device name shown in the app only (never written to the router). */
    fun renameDevice(mac: String, alias: String) {
        val key = mac.uppercase()
        if (alias.isBlank()) deviceAlias.remove(key) else deviceAlias[key] = alias.trim()
        store.aliases = deviceAlias.entries.joinToString(",") { "${it.key}=${it.value}" }
    }

    private fun configThenReboot(applyMsg: String, okMsg: String, edit: (String) -> String) {
        viewModelScope.launch {
            restarting = true; restartStep = 0
            restartTitle = applyMsg; restartMsg = t("Preparing…", "جاري التحضير…"); conn = Conn.Connecting
            var why = ""
            // NOTE: uploadConfig() returns false when the router rejects the file — comparing the
            // result to null treated that rejection as success, so failures looked like they worked.
            val ok = (try {
                withSession {
                    val xml = api.downloadConfig()
                    val out = edit(xml)
                    // safety: never upload something that isn't a sane, complete config
                    val sane = out != xml &&
                        out.startsWith("<InternetGatewayDevice") &&
                        out.length > xml.length * 4 / 5 && out.length < xml.length * 6 / 5
                    if (!sane) { why = "no-change"; false } else {
                        val r = api.uploadConfig(out)
                        if (!r) why = "rejected"
                        r
                    }
                }
            } catch (e: Exception) { why = e.message ?: "error"; false }) == true
            if (!ok) {
                restarting = false; conn = Conn.Connected
                toast(t("Could not apply", "تعذّر التطبيق") + if (why.isNotBlank()) " ($why)" else "")
                return@launch
            }
            rebootWait(okMsg)
        }
    }

    fun reboot() {
        viewModelScope.launch {
            restarting = true; restartStep = 0
            restartTitle = t("Sending restart…", "جاري إرسال الأمر…"); restartMsg = t("Sending to the router…", "جاري الإرسال…")
            conn = Conn.Connecting
            withContext(Dispatchers.IO) { runCatching { withSession { api.reboot() } } }
            rebootWait(t("Router is back — connected", "عاد الراوتر — متصل"))
        }
    }

    /** True while the user is still waiting for the router to come back. */
    fun cancelRestart() { restarting = false; conn = Conn.Disconnected }

    private suspend fun rebootWait(okMsg: String) {
        delay(1300); restartStep = 1
        restartTitle = t("Router is restarting…", "الراوتر يعيد التشغيل…")
        restartMsg = t("Wi-Fi will drop and come back. Reconnect to it and the app continues on its own.",
            "الواي فاي هيفصل ويرجع. اتصل بيه والتطبيق هيكمّل لوحده.")
        conn = Conn.Disconnected

        var back = false
        var epochSeen = netEpoch
        val deadline = System.currentTimeMillis() + 5 * 60_000   // patient: up to 5 minutes
        while (restarting && System.currentTimeMillis() < deadline) {
            delay(2000)
            // Wi-Fi just came back -> rebind sockets and try right away
            if (netEpoch != epochSeen) {
                epochSeen = netEpoch
                api.useSockets(net.socketFactory())
                restartMsg = t("Wi-Fi is back — reconnecting…", "الواي فاي رجع — جاري إعادة الاتصال…")
            }
            val ok = withContext(Dispatchers.IO) {
                if (!runCatching { api.probeUp() }.getOrDefault(false)) return@withContext false
                runCatching { api.login() }.getOrDefault(false)
            }
            if (ok) { back = true; break }
        }
        if (!restarting) return          // user closed the screen

        restartStep = 2; restartTitle = t("Reconnecting…", "جاري إعادة الاتصال…"); delay(600)
        if (back) { conn = Conn.Connected; refresh() } else conn = Conn.Disconnected
        restartMsg = if (back) okMsg
            else t("Still waiting for the router. Reconnect to Wi-Fi and pull to refresh.",
                   "لسه مستني الراوتر. اتصل بالواي فاي وحدّث الصفحة.")
        delay(1200); restarting = false
    }


    // ---- AttachControl XML edit (per-device speed) ----
    private fun editAttachControl(xml: String, mac: String, us: Int, ds: Int): String {
        var out = xml.replace(Regex("""AttachBandLimitEnable="0""""), """AttachBandLimitEnable="1"""")
        val control = if (us == 0 && ds == 0) "0" else "1"
        val instRx = Regex("""<AttachControlInstance\b[^>]*MACAddress="$mac"[^>]*/>""", RegexOption.IGNORE_CASE)
        val existing = instRx.find(out)
        if (existing != null) {
            var inst = existing.value
            inst = setAttr(inst, "UsBandwidth", us.toString())
            inst = setAttr(inst, "DsBandwidth", ds.toString())
            inst = setAttr(inst, "ControlStatus", control)
            inst = setAttr(inst, "InternetAccessRight", "1")
            out = out.replaceRange(existing.range, inst)
        } else if (out.contains("</AttachControl>")) {
            val newInst = """<AttachControlInstance InstanceID="99" MACAddress="$mac" InternetAccessRight="1" """ +
                """StorageAccessRight="1" UsBandwidth="$us" DsBandwidth="$ds" UsGuaranteeBandwidth="0" """ +
                """DsGuaranteeBandwidth="0" InternetPolicy="2" ControlStatus="$control"/>"""
            out = out.replaceFirst("</AttachControl>", "$newInst</AttachControl>")
            out = Regex("""(<AttachControl\b[^>]*NumberOfInstances=")(\d+)(")""").replace(out) {
                "${it.groupValues[1]}${(it.groupValues[2].toIntOrNull() ?: 0) + 1}${it.groupValues[3]}"
            }
        }
        return out
    }
    private fun setAttr(tag: String, name: String, value: String): String {
        // \b so "Channel" never matches inside "X_HW_Channel", "TransmitPower" not inside
        // "TransmitPowerSupported", etc. — the router only expects the exact attribute.
        val rx = Regex("""\b$name="[^"]*"""")
        return if (rx.containsMatchIn(tag)) rx.replace(tag, """$name="$value"""")
        else tag.replaceFirst("/>", """ $name="$value"/>""")
    }

    companion object { private const val MAC_PAGE = "html/bbsp/macfilter/macfilter.asp" }

    // JSON cache of the last good device list (android's built-in org.json — no new deps)
    private fun encodeDevices(list: List<Device>): String {
        val a = JSONArray()
        list.forEach { d ->
            a.put(JSONObject().put("n", d.name).put("i", d.ip).put("m", d.mac).put("c", d.conn).put("o", d.online))
        }
        return a.toString()
    }

    private fun decodeDevices(s: String): List<Device> {
        if (s.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Device(o.getString("n"), o.getString("i"), o.getString("m"), o.getString("c"), o.getBoolean("o"))
            }
        }.getOrDefault(emptyList())
    }
}
