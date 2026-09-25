@file:OptIn(ExperimentalMaterial3Api::class)

package com.hg8145v5.manager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hg8145v5.manager.R
import com.hg8145v5.manager.CrashLog
import com.hg8145v5.manager.BuildConfig
import com.hg8145v5.manager.net.Device
import com.hg8145v5.manager.vm.Conn
import com.hg8145v5.manager.vm.RouterViewModel
import kotlinx.coroutines.delay

/* sheet models */
private sealed interface Sheet
private data class Confirm(
    val title: String, val msg: String, val rows: List<Pair<String, String>>,
    val confirm: String, val danger: Boolean, val onOk: () -> Unit
) : Sheet
private data class DeviceCtl(val d: Device) : Sheet

/** Speed preset: [key] is stable/persisted, [label] is only for display. */
private data class SpeedOpt(val key: String, val label: String, val ds: Int, val us: Int)
private data class SsidEdit(val current: String) : Sheet

/** Smart-suggestion card. [kind] picks the accent: good / warn / bad / accent / info. */
private data class Tip(
    val icon: ImageVector, val kind: String, val title: String, val body: String,
    val actionLabel: String? = null, val run: (() -> Unit)? = null
)

@Composable
private fun tk() = LocalTokens.current

/** Real versionName from the package manager (e.g. "2.5", or "2.5-debug" in debug builds). */
@Composable
private fun appVersion(): String {
    val ctx = LocalContext.current
    return remember {
        runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
}

/** Small version label ("v2.5"). Shown on the splash, login and the bottom of every page. */
@Composable
private fun VersionTag(tk: Tokens, size: TextUnit = 10.sp, color: Color? = null) {
    val v = appVersion()
    if (v.isBlank()) return
    Text("v$v", fontFamily = tk.mono, fontSize = size, color = color ?: tk.ink3)
}

/** Centered version footer placed at the end of each page's scrollable content. */
@Composable
private fun VersionFooter() {
    Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
        VersionTag(tk())
    }
}

@Composable
fun OnuApp(vm: RouterViewModel = viewModel()) {
    val tk = tk()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var splash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1700); splash = false }
    // one silent update check per app open (no login needed)
    LaunchedEffect(Unit) { vm.checkUpdate(auto = true) }
    // auto keep-alive: reconnect/re-login if the connection drops while idle
    LaunchedEffect(vm.loggedIn) {
        while (vm.loggedIn) { delay(15000); vm.heartbeat() }
    }
    // back: close the sheet first, then fall back to Home, then let the system exit
    BackHandler(enabled = sheet != null || (vm.loggedIn && vm.screen != "home")) {
        if (sheet != null) sheet = null else vm.screen = "home"
    }
    Box(Modifier.fillMaxSize().background(tk.surface2)) {
        if (!vm.loggedIn) LoginScreen(vm) else MainScaffold(vm) { sheet = it }

        // toast
        vm.toast?.let { msg ->
            LaunchedEffect(msg) { delay(2600); vm.clearToast() }
            ToastBar(msg, Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp, start = 18.dp, end = 18.dp))
        }
        // bottom sheet
        SheetHost(sheet, vm) { sheet = it }
        // restart overlay
        if (vm.restarting) RestartOverlay(vm)
        // splash
        AnimatedVisibility(visible = splash, exit = fadeOut(tween(400))) { SplashScreen() }
    }
}

@Composable
private fun SplashScreen() {
    val tk = tk()
    val a by rememberInfiniteTransition("sp").animateFloat(.94f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "sp")
    Box(Modifier.fillMaxSize().background(tk.panel), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.scale(a)) { RouterLogo(84.dp) }
            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.brand_short), fontFamily = tk.disp, fontWeight = FontWeight.W800, fontSize = 34.sp, color = tk.ink)
            Spacer(Modifier.height(8.dp))
            Text("Your router, under control", fontFamily = tk.ui, fontSize = 13.sp, color = tk.ink2)
            Spacer(Modifier.height(14.dp))
            VersionTag(tk, size = 12.sp)
        }
    }
}

/* ============ LOGIN ============ */
@Composable
private fun LoginScreen(vm: RouterViewModel) {
    val tk = tk()
    val saved = remember { vm.savedCreds }
    var ip by remember { mutableStateOf(saved?.ip ?: "192.168.100.1") }
    var user by remember { mutableStateOf(saved?.user ?: "admin") }
    var pass by remember { mutableStateOf(saved?.pass ?: "") }
    var show by remember { mutableStateOf(false) }
    var fast by remember { mutableStateOf(vm.fastLogin) }
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)

    LaunchedEffect(Unit) { if (fast && saved != null) vm.login(saved.ip, saved.user, saved.pass) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) { LangButton(vm) }
        Spacer(Modifier.height(24.dp))
        RouterLogo(72.dp)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.brand_short), fontFamily = tk.disp, fontWeight = FontWeight.W800, fontSize = 28.sp, color = tk.ink)
        Spacer(Modifier.height(4.dp))
        Text(s("Sign in with your admin account", "ادخل ببيانات حساب admin"),
            fontFamily = tk.ui, fontSize = 13.sp, color = tk.ink2)
        Spacer(Modifier.height(26.dp))

        Field(vm, s("Router address", "عنوان الراوتر"), ip, { ip = it }, mono = true)
        Field(vm, s("Username", "اسم المستخدم"), user, { user = it }, mono = true)
        Field(vm, s("Password", "كلمة المرور"), pass, { pass = it }, mono = true,
            password = !show,
            keyboard = KeyboardType.Password,
            trailing = {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) WIcon.eyeOff else WIcon.eye, null, tint = tk.ink3)
                }
            })
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SmallSwitch(checked = fast, onCheckedChange = { v ->
                fast = v
                if (v) { vm.setFast(true); vm.login(ip.trim(), user.trim(), pass) }
                else { vm.setFast(false); vm.clearSaved() }
            })
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s("Remember for fast login", "حفظ للدخول السريع"), fontFamily = tk.ui, fontSize = 13.sp, color = tk.ink2)
                Text(s("Saves your password — logs in instantly next time.", "يحفظ الباسورد - يدخل فوراً المرة الجاية."),
                    fontFamily = tk.ui, fontSize = 10.sp, color = tk.ink3)
            }
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            text = if (vm.conn == Conn.Connecting) s("Connecting…", "جاري الاتصال…") else s("Connect", "اتصال"),
            icon = null, loading = vm.conn == Conn.Connecting,
            enabled = vm.conn != Conn.Connecting
        ) { vm.login(ip.trim(), user.trim(), pass) }
        Spacer(Modifier.height(20.dp))
        Text(s("Direct local HTTPS connection to your router", "اتصال محلي مباشر بالراوتر عبر HTTPS"),
            fontFamily = tk.ui, fontSize = 11.sp, color = tk.ink3, textAlign = TextAlign.Center)
        val ctx = LocalContext.current
        val lastCrash = remember { CrashLog.lastCrash(ctx.applicationContext) }
        if (lastCrash != null) {
            var show by remember { mutableStateOf(true) }
            if (show) {
                Spacer(Modifier.height(10.dp))
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = tk.badSoft) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(s("Last crash — please send this to the developer:", "آخر عطل — ابعته للمطور من فضلك:"),
                                fontFamily = tk.ui, fontSize = 11.sp, fontWeight = FontWeight.W600, color = tk.bad)
                            Spacer(Modifier.height(4.dp))
                            Text(lastCrash, fontFamily = tk.mono, fontSize = 8.5.sp, lineHeight = 10.sp, color = tk.ink2)
                        }
                        TextButton(onClick = { CrashLog.clear(ctx.applicationContext); show = false }) {
                            Text(s("OK", "تمام"), fontFamily = tk.ui, fontSize = 11.sp, color = tk.bad)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        VersionTag(tk)
        Spacer(Modifier.height(24.dp))
    }
}

/* ============ SCAFFOLD ============ */
@Composable
private fun MainScaffold(vm: RouterViewModel, setSheet: (Sheet?) -> Unit) {
    val tk = tk()
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)
    Column(Modifier.fillMaxSize()) {
        // app bar
        Row(
            Modifier.fillMaxWidth().background(tk.surface2).statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (vm.screen) {
                        "devices" -> s("Devices", "الأجهزة"); "protect" -> s("Protection", "الحماية")
                        "more" -> s("More", "المزيد"); "tips" -> s("Suggestions", "نصائح")
                        "speed" -> s("Browsing check", "فحص التصفح")
                        else -> s("Home", "الرئيسية")
                    }, fontFamily = tk.ui, fontWeight = FontWeight.W700, fontSize = 19.sp, color = tk.ink
                )
                Text("HG8145V5 · 192.168.100.1", fontFamily = tk.mono, fontSize = 11.sp, color = tk.ink2, maxLines = 1)
            }
            ConnPill(vm)
        }
        // content
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (vm.screen) {
                "devices" -> DevicesScreen(vm, setSheet)
                "protect" -> ProtectionScreen(vm, setSheet)
                "more" -> MoreScreen(vm, setSheet)
                "tips" -> TipsScreen(vm)
                "speed" -> SpeedScreen(vm)
                else -> HomeScreen(vm, setSheet)
            }
        }
        BottomNav(vm)
    }
}

@Composable
private fun BottomNav(vm: RouterViewModel) {
    val tk = tk()
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)
    Column(Modifier.fillMaxWidth().background(tk.panel)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(tk.line))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)
        ) {
            NavItem(vm, "home", WIcon.home, s("Home", "الرئيسية"))
            NavItem(vm, "devices", WIcon.devices, s("Devices", "الأجهزة"))
            NavItem(vm, "protect", WIcon.shield, s("Protection", "الحماية"))
            NavItem(vm, "more", WIcon.more, s("More", "المزيد"))
        }
    }
}

@Composable
private fun RowScope.NavItem(vm: RouterViewModel, id: String, icon: ImageVector, label: String) {
    val tk = tk()
    val on = vm.screen == id
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
            .clickable { vm.screen = id; if (id == "home" || id == "devices") vm.refresh() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (on) tk.accentSoft else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 3.dp)
        ) { Icon(icon, null, tint = if (on) tk.accent else tk.ink3, modifier = Modifier.size(23.dp)) }
        Spacer(Modifier.height(3.dp))
        Text(label, fontFamily = tk.ui, fontSize = 10.5.sp, fontWeight = FontWeight.W600,
            color = if (on) tk.accent else tk.ink3)
    }
}

/* ============ HOME ============ */
@Composable
private fun HomeScreen(vm: RouterViewModel, setSheet: (Sheet?) -> Unit) {
    val tk = tk()
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)
    LaunchedEffect(Unit) { vm.refresh() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // hero
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(tk.accent, Color(0xFF0891B2), tk.indigo)))
                .padding(18.dp)
        ) {
            Column {
                Text(s("Internet — Fiber (GPON)", "الإنترنت — الألياف الضوئية"),
                    color = Color.White.copy(alpha = .9f), fontFamily = tk.ui, fontSize = 12.sp)
                Text(
                    if (vm.conn == Conn.Connected) s("Connected", "متصل") else s("Checking…", "جاري الفحص…"),
                    color = Color.White, fontFamily = tk.disp, fontWeight = FontWeight.W800, fontSize = 28.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    HeroStat(s("Signal (Rx)", "الإشارة (Rx)"), vm.rxDbm?.let { "$it dBm" } ?: "", vm.rxDbm == null, tk)
                    HeroStat(s("Uptime", "مدة التشغيل"), uptimeHuman(vm.uptimeSec, vm.lang), vm.uptimeSec < 0, tk)
                    HeroStat(s("Signal (Tx)", "الإرسال (Tx)"), vm.txDbm?.let { "$it dBm" } ?: "", vm.txDbm == null, tk)
                }
            }
        }
        // stat tiles: Download / Upload (cumulative since boot) in one box, Devices, Signal
        UsageBox(vm)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Modifier.weight(1f).clickable { vm.screen = "devices"; vm.refresh() }, WIcon.devices, s("Devices", "الأجهزة"), "${vm.deviceCount}", false, tk.good, tk.goodSoft)
            StatTile(Modifier.weight(1f), WIcon.router, s("Model", "الموديل"), "HG8145V5", false, tk.warn, tk.warnSoft)
        }
        SectionHeader(s("Quick actions", "إجراءات سريعة"), null)
        PanelCard {
            RowAction(WIcon.shield, s("Block ads & malware (DNS)", "حجب الإعلانات والفيروسات"),
                s("For all devices", "لكل الأجهزة"), tk.accent, tk.accentSoft) { vm.screen = "protect" }
            Divider2()
            RowAction(WIcon.block, s("Block or limit a device", "بلوك أو تحديد سرعة جهاز"),
                s("Per-device control", "تحكّم لكل جهاز"), tk.bad, tk.badSoft) { vm.screen = "devices"; vm.refresh() }
            Divider2()
            RowAction(WIcon.restart, s("Restart router", "إعادة تشغيل الراوتر"),
                s("~2 minutes", "~دقيقتين"), tk.warn, tk.warnSoft) { setSheet(rebootConfirm(vm)) }
            Divider2()
            RowAction(WIcon.bulb, s("Smart suggestions", "نصائح ذكية"),
                if (vm.recommendationCount > 0) "${vm.recommendationCount} " + s("recommendations", "توصيات")
                else s("Network analysis", "تحليل الشبكة"),
                tk.accent, tk.accentSoft) { vm.screen = "tips" }
        }
        Spacer(Modifier.height(4.dp))
        VersionFooter()
    }
}

/* ============ SMART SUGGESTIONS ============ */
@Composable
private fun TipsScreen(vm: RouterViewModel) {
    val tk = tk()
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)
    LaunchedEffect(Unit) { vm.loadConfigInfo() }

    val tips = buildList {
        if (vm.conn != Conn.Connected) add(Tip(
            WIcon.wifi, "info",
            s("Not connected to the router", "غير متصل بالراوتر"),
            s("Sign in or tap refresh so the analysis reads live data.",
                "سجّل الدخول أو حدّث الصفحة لكي يعمل التحليل على بيانات حية.")
        ))
        // MTU recommendation — only after a real DF-ping sweep finds the link's true limit.
        vm.probedMtu?.let { real ->
            val cur = vm.mtu
            if (cur != null && real != cur) add(Tip(
                WIcon.download, "warn",
                s("Set MTU to $real", "اضبط الـ MTU على $real"),
                s("The ping test found your line carries packets up to $real bytes. Current setting is $cur — larger packets get dropped, which slows browsing and can stall pages. Apply $real.",
                    "فحص الـ ping لقى إن خطك بيمرّر رزم لحد $real بايت. الإعداد الحالي $cur — الرزم الأكبر بتتكسّر وتتوه فيبطؤ التصفح. طبّق $real."),
                s("Apply $real", "تطبيق $real")
            ) { vm.applyMtu(real) })
        }
        if (vm.probeState == "unsupported" && vm.mtu != null && vm.mtu != 1450) add(Tip(
            WIcon.info, "info",
            s("Apply the measured MTU (1450)", "طبّق الـ MTU المقيس (1450)"),
            s("This phone's ping can't run the DF sweep. Based on our earlier measurement on your line, the safe value is 1450 bytes.",
                "فحص الـ ping على الموبايل ده مش بيدعم قياس DF. بناءً على القياس اللي عملناه قبل كده على خطك، القيمة الآمنة 1450 بايت."),
            s("Apply 1450", "تطبيق 1450")
        ) { vm.applyMtu(1450) })
        if (vm.probeState == "failed") add(Tip(
            WIcon.info, "info",
            s("Ping test got no reply", "فحص الـ ping مافيش رد"),
            s("No ICMP reply from the test host. Try again, or make sure the internet is up — then rerun the check.",
                "مفيش رد ICMP من السرفر التجريبي. جرّب تاني أو تأكد إن النت شغال — وبعدين أعد الفحص.")
        ))
        if (vm.uptimeSec > 7 * 86400L) add(Tip(
            WIcon.restart, "warn",
            s("Running for a long time", "يعمل منذ مدة طويلة"),
            s("Uptime is ${uptimeHuman(vm.uptimeSec, vm.lang)} — routers leak memory over time. A periodic restart keeps Wi-Fi fast and stable.",
                "مدة التشغيل ${uptimeHuman(vm.uptimeSec, vm.lang)} — الراوتر بيفقد الذاكرة مع الوقت. إعادة تشغيل دورية بتخلّي الواي فاي أسرع وأثبت."),
            s("Restart", "إعادة تشغيل")
        ) { vm.reboot() })
        vm.rxDbm?.toIntOrNull()?.let { if (it < -22) add(Tip(
            WIcon.router, "warn",
            s("Weak optical signal", "إشارة الألياف ضعيفة"),
            s("Received light is ${vm.rxDbm} dBm — below the healthy ~-22 dBm. Check the fiber joint/connector.",
                "استقبال الضوء ${vm.rxDbm} dBm — أقل من الحد الصحي ~-22 dBm. افحص موصل الألياف والوصلات."),
        )) }
        if (isEmpty() && vm.conn == Conn.Connected) add(Tip(
            WIcon.bulb, "good",
            s("All good", "كل شيء تمام"),
            s("No changes needed right now. Run the MTU check to be sure, and nothing else looks off.",
                "لا حاجة لتغييرات حالياً. شغّل فحص MTU للتأكد، ولا شيء آخر غير طبيعي.")
        ))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(s("Network analysis", "تحليل الشبكة")) {
            if (vm.conn == Conn.Connected && vm.recommendationCount > 0)
                Badge("${vm.recommendationCount} " + s("suggestions", "توصيات"), tk.accent, tk.accentSoft)
        }
        MtuCheckCard(vm)
        PanelCard {
            RowAction(WIcon.wifi, s("Browsing speed check", "فحص سرعة التصفح"),
                s("DNS · latency · loss · bufferbloat · IPv6 · TCP · Wi-Fi", "DNS · التأخير · الفقد · Bufferbloat · IPv6 · TCP · الواي فاي"),
                tk.accent, tk.accentSoft) { vm.screen = "speed"; vm.runSpeedCheck() }
        }
        tips.forEach { TipCard(it) }
        Text(
            s("Suggestions come from live readings of your router (ping/MTU, uptime, signal). The MTU test pings with the “don't fragment” flag to find the real limit of your line — apply only restarts the router when a setting changes.",
                "التوصيات مبنية على قراءات حية من الراوتر (ping/MTU، مدة التشغيل، الإشارة). فحص MTU بيبعت ping بعلامة «لا تُجزّأ» لمعرفة الحد الحقيقي لخطك — والتطبيق بيعيد تشغيل الراوتر فقط عند تغيير إعداد."),
            fontFamily = tk.ui, fontSize = 11.5.sp, color = tk.ink3
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable private fun MtuCheckCard(vm: RouterViewModel) {
    val tk = tk()
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)
    val running = vm.probeState == "running"
    PanelCard {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(tk.accentSoft), Alignment.Center) {
                    Icon(WIcon.download, null, tint = tk.accent, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s("MTU check (ping)", "فحص MTU (ping)"),
                        fontFamily = tk.ui, fontWeight = FontWeight.W700, fontSize = 14.sp, color = tk.ink)
                    Text(
                        when (vm.probeState) {
                            "done" -> s("Effective MTU: ${vm.probedMtu}", "الحد الفعلي: ${vm.probedMtu}")
                            "unsupported" -> s("Not supported on this phone", "غير مدعوم على الموبايل ده")
                            "failed" -> s("No reply from test host", "مافيش رد من السرفر")
                            "running" -> s("Testing packet sizes…", "جاري اختبار أحجام الرزم…")
                            else -> s("Find the real packet size your line carries", "اعرف أكبر حجم رزمة بيمرّ على خطك")
                        },
                        fontFamily = tk.ui, fontSize = 12.sp, color = tk.ink2
                    )
                }
                if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = tk.accent)
            }
            Spacer(Modifier.height(12.dp))
            if (!running) PrimaryButton(
                if (vm.probeState == "idle") s("Run MTU check", "تشغيل فحص MTU") else s("Run again", "أعد الفحص"),
                null, false, true
            ) { vm.runMtuProbe() }
        }
        VersionFooter()
    }
}

/* ============ BROWSING SPEED CHECK ============ */
@Composable
private fun SpeedScreen(vm: RouterViewModel) {
    val tk = tk()
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)
    LaunchedEffect(Unit) { if (vm.speedState == "idle") vm.runSpeedCheck() }
    val running = vm.speedState == "running"

    val toneColor: (String) -> Color = { tone ->
        when (tone) {
            "good" -> tk.good; "warn" -> tk.warn; "bad" -> tk.bad; "accent" -> tk.accent; else -> tk.ink
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(s("Browsing speed check", "فحص سرعة التصفح")) {
            if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = tk.accent)
            else PrimaryButton(s("Rerun", "إعادة"), null, false, true) { vm.runSpeedCheck() }
        }
        Text(
            s("All tests run from this phone through your Wi-Fi and line — no router login needed. Sweep below and apply what's worth it.",
                "كل الفحوصات بتتم من الموبايل عبر الواي فاي والخط — من غير تسجيل دخول للراوتر. شوف تحت وطبّق اللي يستحق."),
            fontFamily = tk.ui, fontSize = 11.5.sp, color = tk.ink3
        )

        // ---- DNS ----
        val best = vm.dnsResults.firstOrNull()
        val second = vm.dnsResults.getOrNull(1)
        val bestMs = best?.ms
        val secondMs = second?.ms
        CheckCard(WIcon.router, s("DNS resolution", "استجابة الـ DNS"),
            bestMs?.let { "$it ms · ${best!!.server}" } ?: "—",
            when { bestMs == null -> "muted"; bestMs < 40 -> "good"; bestMs < 70 -> "warn"; else -> "bad" },
            when {
                bestMs == null && !running -> s("Could not reach any DNS server.", "مقدرناش نوصل لأي سيرفر DNS.")
                bestMs != null && secondMs != null && (secondMs - bestMs) < 15 -> s("All servers are within a few ms — changing DNS won't make browsing faster. Do it only to block ads (AdGuard/NextDNS).",
                    "كل السيرفرات متقاربة في أجزاء من المللي ثانية — تغيير الـ DNS مش هيسرّع التصفح. غيّره بس عشان حجب الإعلانات (AdGuard/NextDNS).")
                bestMs != null -> s("Fastest now: ${best!!.server} (${best.label}). A switch saves only ~${secondMs?.minus(bestMs) ?: 0} ms.",
                    "الأسرع حالياً: ${best!!.server} (${best.label}). التغيير بيوفّر ~${secondMs?.minus(bestMs) ?: 0} مللي ثانية بس.")
                else -> null
            }, null, toneColor, vm.dnsResults.isNotEmpty()) {
            vm.dnsResults.forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${d.server}  ${d.label}", Modifier.weight(1f), fontFamily = tk.mono, fontSize = 11.5.sp, color = tk.ink2)
                    Text(d.ms?.let { "$it ms" } ?: "—", fontFamily = tk.mono, fontSize = 11.5.sp,
                        color = if (d == best) tk.good else tk.ink3, fontWeight = if (d == best) FontWeight.W700 else FontWeight.W400)
                }
            }
        }

        // ---- MTU ----
        CheckCard(WIcon.download, s("MTU (packet size)", "MTU (حجم الرزمة)"),
            vm.probedMtu?.toString() ?: "—",
            if (vm.probedMtu != null && vm.mtu != null && vm.probedMtu != vm.mtu) "warn" else "muted",
            when {
                vm.probedMtu != null && vm.mtu != null && vm.probedMtu != vm.mtu ->
                    s("Router is set to ${vm.mtu} but the line only carries ${vm.probedMtu}. Apply it in Suggestions.", "الراوتر على ${vm.mtu} لكن الخط بيمرّر ${vm.probedMtu} بس. طبّقه من نصائح.")
                vm.probedMtu != null -> s("Matches the router setting.", "مطابق لإعداد الراوتر.")
                else -> s("Run the DF-ping sweep to find your line's real limit.", "شغّل فحص الـ ping بعلامة DF لمعرفة حد خطك الحقيقي.")
            }, s("Run MTU check", "فحص MTU") to { vm.runMtuProbe() }, toneColor, vm.probedMtu != null)

        // ---- Latency / loss / jitter ----
        val p = vm.pingStats
        CheckCard(WIcon.wifi, s("Latency & packet loss", "التأخير وفقد الرزم"),
            p?.let { "${it.avgMs.toInt()} ms" } ?: "—",
            when { p == null -> "muted"; p.lossPct > 1 || p.avgMs > 90 -> "bad"; p.avgMs > 60 || p.mdevMs > 20 -> "warn"; else -> "good" },
            p?.let {
                val jitter = if (it.mdevMs > 20) s(" · jitter ${it.mdevMs.toInt()} ms", " · تذبذب ${it.mdevMs.toInt()} مللي") else ""
                if (it.lossPct > 1) s("${it.lossPct}% packet loss — retries slow every page. Check Wi-Fi signal or the line.", "${it.lossPct}% فقد في الرزم — كل صفحة بتعيد المحاولة فتبطؤ. افحص الإشارة أو الخط.")
                else s("Stable to 1.1.1.1$jitter — this is the minimum delay every request pays.", "مستقر لـ 1.1.1.1$jitter — ده أقل تأخير بيدفعه كل طلب.")
            }, null, toneColor, p != null)

        // ---- Bufferbloat ----
        val b = vm.bloom
        val bBusy = vm.bloomState == "running"
        CheckCard(WIcon.restart, s("Bufferbloat (ping under load)", "Bufferbloat (التأخير تحت الضغط)"),
            b?.let { "${it.grade} · +${it.increaseMs.toInt()} ms" } ?: if (bBusy) "…" else "—",
            when { b == null -> "muted"; b.grade == "A" -> "good"; b.grade == "B" -> "warn"; else -> "bad" },
            when {
                bBusy -> s("Saturating the line for ~4s to measure the worst-case delay…", "بنشبع الخط ~4 ثواني لقياس أسوأ تأخير…")
                b == null -> s("Measures how much latency jumps when the line is busy. Uses ~15–30 MB.", "بيقيس قد إيه التأخير بيزيد وقت الخط مشغول. بيستهلك ~15–30 ميجا.")
                b.grade == "A" -> s("Loaded latency barely rises (${b.idleMs.toInt()}→${b.loadedMs.toInt()} ms). No bufferbloat.", "التأخير تحت الحمل تقريباً ثابت (${b.idleMs.toInt()}→${b.loadedMs.toInt()} مللي). مفيش bufferbloat.")
                b.grade == "B" -> s("Mild rise (${b.idleMs.toInt()}→${b.loadedMs.toInt()} ms). Fine for most use.", "زيادة بسيطة (${b.idleMs.toInt()}→${b.loadedMs.toInt()} مللي). مقبولة لمعظم الاستخدام.")
                else -> s("Big rise (${b.idleMs.toInt()}→${b.loadedMs.toInt()} ms): downloads stall browsing. Cap device speeds so the line never fills.", "زيادة كبيرة (${b.idleMs.toInt()}→${b.loadedMs.toInt()} مللي): التحميل بيخنق التصفح. حدّد سرعة الأجهزة عشان الخط ما يتملاش.")
            }, if (bBusy) null else s("Measure it", "قِسها") to { vm.runBufferbloat() }, toneColor, b != null)

        // ---- IPv6 ----
        val v6 = vm.ipv6Ok
        CheckCard(WIcon.router, s("IPv6", "IPv6"),
            when (v6) { true -> s("Available", "متاح"); false -> s("Not available", "غير متاح"); null -> "—" },
            if (v6 == true) "good" else "muted",
            when {
                v6 == true -> s("Your line has working IPv6 — leave it on; some sites load faster over it.", "خطك فيه IPv6 شغّال — سيبه مفعّل؛ بعض المواقع بتحمّل أسرع عليه.")
                v6 == false -> s("No global IPv6 from the ISP — nothing to optimize here.", "مفيش IPv6 عام من المزوّد — مفيش حاجة تتحسّن هنا.")
                else -> null
            }, null, toneColor, v6 != null)

        // ---- TCP ----
        CheckCard(WIcon.download, s("TCP connect", "اتصال TCP"),
            vm.tcpMs?.let { "$it ms" } ?: "—",
            when { vm.tcpMs == null -> "muted"; vm.tcpMs!! < 120 -> "good"; else -> "warn" },
            vm.tcpMs?.let { t ->
                val rtt = vm.pingStats?.avgMs?.toInt()
                if (rtt != null && t <= rtt + 40) s("Handshake matches the ping time — no TCP-level problem.", "زمن الاتصال مطابق للـ ping — مفيش مشكلة على مستوى TCP.")
                else s("Handshake is slower than the ping — normal with TLS; no router knob fixes it.", "زمن الاتصال أبطأ من الـ ping — طبيعي مع TLS؛ مفيش إعداد في الراوتر بيصلحه.")
            }, null, toneColor, vm.tcpMs != null)

        // ---- Wi-Fi ----
        val w = vm.wifiLink
        CheckCard(WIcon.wifi, s("Wi-Fi link", "رابط الواي فاي"),
            w?.let { "${it.band} · ${it.rssiDbm} dBm" } ?: "—",
            when { w == null -> "muted"; w.rssiDbm >= -60 -> "good"; w.rssiDbm >= -70 -> "warn"; else -> "bad" },
            when {
                w == null -> s("Not on Wi-Fi (or no access to link info).", "مش على الواي فاي (أو مفيش صلاحية لقراءة الرابط).")
                w.rssiDbm >= -60 -> s("Strong signal at ${w.linkMbps} Mbps (${w.band}). Nothing to improve.", "إشارة قوية ${w.linkMbps} Mbps (${w.band}). مفيش حاجة تتحسّن.")
                w.rssiDbm >= -70 -> s("Fair signal — move closer to the router or switch to 5 GHz for a faster, steadier link.", "إشارة متوسطة — قرّب من الراوتر أو اتصل بـ 5GHz لرابط أسرع وأثبت.")
                else -> s("Weak signal (${w.rssiDbm} dBm) — the bottleneck is Wi-Fi, not the internet. Get closer to the router.", "إشارة ضعيفة (${w.rssiDbm} dBm) — عنق الزجاجة في الواي فاي مش النت. قرّب من الراوتر.")
            }, null, toneColor, w != null)

        Text(
            s("These measure the real path this phone uses. Router-side items (MTU/DNS) still need the config change, which restarts the router.",
                "دي بتقيس المسار الحقيقي للموبايل. الحاجات اللي على الراوتر (MTU/DNS) لسه محتاجة تغيير الإعداد، وده بيعيد تشغيل الراوتر."),
            fontFamily = tk.ui, fontSize = 11.5.sp, color = tk.ink3
        )
        Spacer(Modifier.height(4.dp))
        VersionFooter()
    }
}

@Composable
private fun CheckCard(
    icon: ImageVector, title: String, value: String, tone: String, advice: String?,
    action: Pair<String, () -> Unit>?,
    toneOf: (String) -> Color,
    showBody: Boolean,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val tk = tk()
    PanelCard {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(tk.accentSoft), Alignment.Center) {
                    Icon(icon, null, tint = tk.accent, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontFamily = tk.ui, fontWeight = FontWeight.W700, fontSize = 14.sp, color = tk.ink)
                    Text(value, fontFamily = tk.disp, fontWeight = FontWeight.W700, fontSize = 16.sp, color = toneOf(tone))
                }
                action?.let { (label, run) ->
                    TextButton(onClick = run) { Text(label, fontFamily = tk.ui, fontSize = 12.sp, color = tk.accent) }
                }
            }
            if (advice != null) {
                Spacer(Modifier.height(8.dp))
                Text(advice, fontFamily = tk.ui, fontSize = 12.sp, color = tk.ink2)
            }
            if (showBody && content != null) {
                Spacer(Modifier.height(8.dp))
                Divider2()
                Spacer(Modifier.height(6.dp))
                content()
            }
        }
    }
}

@Composable private fun TipCard(t: Tip) {
    val tk = tk()
    val (fg, bg) = when (t.kind) {
        "good" -> tk.good to tk.goodSoft
        "warn" -> tk.warn to tk.warnSoft
        "bad" -> tk.bad to tk.badSoft
        "accent" -> tk.accent to tk.accentSoft
        else -> tk.indigo to tk.indigoSoft
    }
    PanelCard {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(bg), Alignment.Center) {
                    Icon(t.icon, null, tint = fg, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, fontFamily = tk.ui, fontWeight = FontWeight.W700, fontSize = 14.sp, color = tk.ink)
                    Spacer(Modifier.height(3.dp))
                    Text(t.body, fontFamily = tk.ui, fontSize = 12.5.sp, lineHeight = 17.sp, color = tk.ink2)
                }
            }
            if (t.actionLabel != null && t.run != null) {
                Spacer(Modifier.height(12.dp))
                FilledSheetButton(t.actionLabel, Modifier.fillMaxWidth(), fg, Color.White) { t.run() }
            }
        }
    }
}

/* shared reboot confirmation used by Home and More */
private fun rebootConfirm(vm: RouterViewModel): Sheet {
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)
    return Confirm(
        s("Restart router", "إعادة تشغيل الراوتر"),
        s("The internet drops for ~2 minutes until it finishes booting.", "سينقطع الإنترنت ~دقيقتين حتى يكمل الإقلاع."),
        listOf(s("Action", "الإجراء") to s("Restart", "إعادة تشغيل"), s("Expected", "المدة") to "~2 min"),
        s("Restart now", "إعادة التشغيل الآن"), false
    ) { vm.reboot() }
}

private fun bytesHuman(b: Long): String {
    if (b < 0) return "—"
    val u = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble(); var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return (if (v >= 100 || i == 0) "%.0f".format(v) else "%.1f".format(v)) + " " + u[i]
}

private fun uptimeHuman(sec: Long, lang: String): String {
    if (sec < 0) return "—"
    val d = sec / 86400; val h = (sec % 86400) / 3600; val m = (sec % 3600) / 60
    return when {
        d > 0 -> if (lang == "ar") "${d}ي ${h}س" else "${d}d ${h}h"
        h > 0 -> if (lang == "ar") "${h}س ${m}د" else "${h}h ${m}m"
        else -> if (lang == "ar") "${m}د" else "${m}m"
    }
}

@Composable private fun HeroStat(label: String, value: String, loading: Boolean, tk: Tokens) {
    Column {
        Text(label, color = Color.White.copy(alpha = .85f), fontFamily = tk.ui, fontSize = 11.sp)
        if (loading) CircularProgressIndicator(Modifier.padding(top = 3.dp).size(14.dp), strokeWidth = 2.dp, color = Color.White)
        else Text(value, color = Color.White, fontFamily = tk.mono, fontWeight = FontWeight.W600, fontSize = 14.sp)
    }
}

@Composable private fun StatTile(m: Modifier, icon: ImageVector, label: String, value: String, loading: Boolean, fg: Color, bg: Color, sub: String = "") {
    val tk = tk()
    PanelCard(m) {
        Column(Modifier.padding(14.dp)) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(bg), Alignment.Center) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.height(9.dp))
            Text(label, fontFamily = tk.ui, fontSize = 11.5.sp, color = tk.ink2)
            if (loading) Box(Modifier.padding(top = 4.dp)) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = fg) }
            else Text(value, fontFamily = tk.disp, fontWeight = FontWeight.W700, fontSize = 20.sp, color = tk.ink, maxLines = 1)
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(sub, fontFamily = tk.ui, fontSize = 9.5.sp, color = tk.ink3, maxLines = 1)
            }
        }
    }
}

/* Download / Upload cumulative usage since boot, in one box. */
@Composable private fun UsageBox(vm: RouterViewModel) {
    val tk = tk()
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)
    PanelCard {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s("Internet usage", "استهلاك الإنترنت"),
                    fontFamily = tk.ui, fontWeight = FontWeight.W600, fontSize = 12.sp, color = tk.ink2,
                    modifier = Modifier.weight(1f))
                Badge(s("Since last boot", "منذ آخر إقلاع"), tk.accent, tk.accentSoft)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniStat(Modifier.weight(1f), WIcon.download, s("Download", "التنزيل"), bytesHuman(vm.downBytes), tk.accent, tk.accentSoft)
                MiniStat(Modifier.weight(1f), WIcon.upload, s("Upload", "الرفع"), bytesHuman(vm.upBytes), tk.indigo, tk.indigoSoft)
            }
        }
    }
}

@Composable private fun MiniStat(m: Modifier, icon: ImageVector, label: String, value: String, fg: Color, bg: Color) {
    val tk = tk()
    Row(m, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(bg), Alignment.Center) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, fontFamily = tk.ui, fontSize = 11.sp, color = tk.ink2)
            Text(value, fontFamily = tk.disp, fontWeight = FontWeight.W700, fontSize = 19.sp, color = tk.ink, maxLines = 1)
        }
    }
}

@Composable private fun RowAction(icon: ImageVector, title: String, sub: String, fg: Color, bg: Color, onClick: () -> Unit) {
    val tk = tk()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(bg), Alignment.Center) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = tk.ui, fontWeight = FontWeight.W600, fontSize = 14.sp, color = tk.ink)
            Text(sub, fontFamily = tk.ui, fontSize = 12.sp, color = tk.ink2)
        }
        Icon(WIcon.chevron, null, tint = tk.ink3)
    }
}

/* ============ DEVICES ============ */
@Composable
private fun TabPill(m: Modifier, sel: Boolean, label: String, count: Int, fg: Color, bg: Color, line: Color, onClick: () -> Unit) {
    val tk = tk()
    Box(m.clip(RoundedCornerShape(11.dp))
        .background(if (sel) bg else tk.surface3)
        .border(1.dp, if (sel) line else tk.line, RoundedCornerShape(11.dp))
        .clickable(onClick = onClick)
        .padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$count", fontFamily = tk.mono, fontSize = 12.sp, fontWeight = FontWeight.W700, color = if (sel) fg else tk.ink2)
            Text(label, fontFamily = tk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.W600, color = if (sel) fg else tk.ink2)
        }
    }
}

@Composable
private fun DevicesScreen(vm: RouterViewModel, setSheet: (Sheet?) -> Unit) {
    val tk = tk()
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { vm.refresh() }

    val blkOf: (Device) -> Boolean = { vm.blocked.containsKey(it.mac.uppercase()) }
    val cntOnline = vm.devices.count { it.online && !blkOf(it) }
    val cntBlocked = vm.devices.count { blkOf(it) }
    val cntOffline = vm.devices.count { !it.online && !blkOf(it) }
    val active = vm.devices
        .filter { d ->
            val b = blkOf(d)
            when (tab) {
                0 -> d.online && !b
                1 -> b
                else -> !d.online && !b
            }
        }
        .filter {
            val t = query.trim().lowercase()
            t.isEmpty() || it.name.lowercase().contains(t) ||
                it.ip.contains(t) || it.mac.lowercase().contains(t)
        }
        .sortedWith(compareBy({ it.name.lowercase() }))

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionHeader(s("Connected devices", "الأجهزة المتصلة")) {
                Badge("${vm.deviceCount} " + s("online", "متصل"), tk.accent, tk.accentSoft)
                if (vm.devices.size > vm.deviceCount)
                    Badge("${vm.devices.size} " + s("known", "معروف"), tk.ink2, tk.surface3)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabPill(Modifier.weight(1f), tab == 0, s("Online", "متصل"), cntOnline, tk.good, tk.goodSoft, tk.good) { tab = 0 }
                TabPill(Modifier.weight(1f), tab == 1, s("Blocked", "محجوب"), cntBlocked, tk.bad, tk.badSoft, tk.bad) { tab = 1 }
                TabPill(Modifier.weight(1f), tab == 2, s("Offline", "غير متصل"), cntOffline, tk.ink2, tk.surface3, tk.accent) { tab = 2 }
            }
        }
        if (tab == 1 && vm.blocked.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tk.badSoft).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(WIcon.block, null, tint = tk.bad, modifier = Modifier.size(18.dp))
                Text(
                    s("Blocking only cuts internet — devices stay connected. Tap a blocked device to unblock instantly (no restart).",
                        "الحجب يقطع الإنترنت فقط — الأجهزة تظل متصلة. اضغط الجهاز المحجوب لفك الحجب فورًا (بدون إعادة تشغيل)."),
                    fontFamily = tk.ui, fontSize = 12.sp, color = tk.ink
                )
            }
        }
        item {
            if (!searching) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s("Search by name, IP or MAC", "ابحث بالاسم أو IP أو MAC"),
                        modifier = Modifier.weight(1f),
                        fontFamily = tk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.W600, color = tk.ink2)
                    IconBtn(WIcon.search, s("Search", "بحث")) { searching = true }
                }
            } else {
                Field(vm, s("Search by name, IP or MAC", "ابحث بالاسم أو IP أو MAC"), query, { query = it }, mono = true,
                    trailing = { IconBtn(WIcon.close, s("Close search", "إغلاق البحث")) { searching = false; query = "" } })
            }
        }
        if (active.isEmpty()) item {
            Text(
                when {
                    vm.devices.isEmpty() -> s("No devices yet.", "لا أجهزة بعد.")
                    tab == 1 -> s("No blocked devices.", "لا أجهزة محجوبة.")
                    query.isNotBlank() -> s("No device matches your search.", "لا يوجد جهاز مطابق للبحث.")
                    else -> s("No devices in this tab.", "لا توجد أجهزة في هذا التبويب.")
                },
                fontFamily = tk.ui, fontSize = 13.sp, color = tk.ink2
            )
        }
        items(active) { d ->
            val label = vm.speedLabel[d.mac.uppercase()] ?: "Max"
            val isBlocked = vm.blocked.containsKey(d.mac.uppercase())
            val isMax = label == "Max"
            val frac = if (isMax) 1f else (label.toFloatOrNull() ?: 20f) / 20f
            PanelCard(Modifier.fillMaxWidth().clickable { setSheet(DeviceCtl(d)) }) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(tk.surface3), Alignment.Center) {
                            val wifi = d.conn.contains("WIFI", true) || d.conn == "2.4G" || d.conn == "5G"
                            Icon(if (wifi) WIcon.wifi else WIcon.device, null,
                                tint = tk.ink2, modifier = Modifier.size(19.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontFamily = tk.ui, fontWeight = FontWeight.W600, fontSize = 14.sp, color = tk.ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (d.ip.isBlank()) s("— no IP (offline)", "— بدون IP (غير متصل)") else d.ip,
                                fontFamily = tk.mono, fontSize = 11.5.sp, color = tk.ink2, maxLines = 1)
                            Text(d.mac, fontFamily = tk.mono, fontSize = 11.5.sp, color = tk.ink2, maxLines = 1)
                        }
                        ChipLabel(
                            when {
                                isBlocked -> s("Blocked", "محجوب") + " · " +
                                    if (d.online) s("online", "متصل") else s("offline", "غير متصل")
                                d.online -> s("Online", "متصل")
                                else -> s("Offline", "غير متصل")
                            },
                            when { isBlocked -> tk.bad; d.online -> tk.good; else -> tk.ink3 },
                            when { isBlocked -> tk.badSoft; d.online -> tk.goodSoft; else -> tk.surface3 }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(tk.surface3)) {
                            Box(Modifier.fillMaxWidth(frac).height(6.dp).clip(CircleShape)
                                .background(Brush.horizontalGradient(listOf(tk.accent, tk.indigo))))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(if (isMax) s("Max", "بلا حدود") else "$label Mbps",
                            fontFamily = tk.mono, fontSize = 11.sp, color = tk.ink2)
                    }
                }
            }
        }
        item {
            Text(s("Tap a device to block it or set a speed limit.", "اضغط جهاز للحظر أو تحديد سرعته."),
                fontFamily = tk.ui, fontSize = 12.sp, color = tk.ink2, modifier = Modifier.padding(top = 4.dp))
        }
        item { VersionFooter() }
    }
}

/* ============ PROTECTION ============ */
private data class DnsOpt(val name: String, val ip: String, val useEn: String, val useAr: String, val p: String, val s: String)

@Composable private fun DnsCard(o: DnsOpt, on: Boolean, lang: String, m: Modifier, onClick: () -> Unit) {
    val tk = tk()
    Column(
        m.clip(RoundedCornerShape(16.dp))
            .background(if (on) tk.accentSoft else tk.panel)
            .border(1.5.dp, if (on) tk.accent else tk.line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(10.dp)
    ) {
        Text(o.name, fontFamily = tk.ui, fontWeight = FontWeight.W700, fontSize = 14.sp, color = tk.ink)
        Spacer(Modifier.height(4.dp))
        Text(o.p, fontFamily = tk.mono, fontSize = 11.sp, lineHeight = 12.sp, color = tk.ink2)
        Text(o.s, fontFamily = tk.mono, fontSize = 11.sp, lineHeight = 12.sp, color = tk.ink2)
        Spacer(Modifier.height(6.dp))
        Text(if (lang == "ar") o.useAr else o.useEn, fontFamily = tk.ui, fontSize = 10.5.sp,
            color = if (on) tk.accent else tk.ink3)
    }
}

@Composable
private fun ProtectionScreen(vm: RouterViewModel, setSheet: (Sheet?) -> Unit) {
    val tk = tk()
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)
    val opts = listOf(
        DnsOpt("Google", "8.8.8.8", "Current · no blocking", "الحالي · بلا حجب", "8.8.8.8", "8.8.4.4"),
        DnsOpt("AdGuard", "94.140.14.14", "Ad blocking", "حجب الإعلانات", "94.140.14.14", "94.140.15.15"),
        DnsOpt("Quad9", "9.9.9.9", "Malware blocking", "حجب الفيروسات", "9.9.9.9", "149.112.112.112"),
        DnsOpt("NextDNS", "custom", "Full control", "تحكّم كامل", "45.90.28.0", "45.90.30.0"),
    )
    var sel by remember { mutableIntStateOf(0) }
    var mtuVal by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadMtu() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(s("Block ads & malware", "حجب الإعلانات والفيروسات")) { Badge(s("Restarts", "يعيد التشغيل"), tk.warn, tk.warnSoft) }
        Text(s("Pick a DNS — applied to every device.", "اختر DNS — يُطبَّق على كل الأجهزة."),
            fontFamily = tk.ui, fontSize = 12.sp, color = tk.ink2)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            opts.chunked(2).forEach { rowOpts ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowOpts.forEach { o ->
                        val i = opts.indexOf(o)
                        DnsCard(o, sel == i, vm.lang, Modifier.weight(1f)) { sel = i }
                    }
                }
            }
        }
        PrimaryButton(s("Apply DNS", "تطبيق DNS"), null, false, true) {
            val o = opts[sel]
            setSheet(Confirm(
                s("Change DNS server", "تغيير خادم DNS"),
                s("Applied to all devices. The router will restart.", "يُطبَّق على كل الأجهزة. سيعيد الراوتر التشغيل."),
                listOf("DNS" to o.name, s("Address", "العنوان") to "${o.p}, ${o.s}"),
                s("Apply & restart", "تطبيق وإعادة تشغيل"), false
            ) { vm.applyDns(o.p, o.s) })
        }
        Spacer(Modifier.height(6.dp))
        SectionHeader(s("Block specific sites", "حجب مواقع بعينها")) { Badge(s("Via DNS", "عبر DNS"), tk.accent, tk.accentSoft) }
        PanelCard {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(tk.accentSoft), Alignment.Center) {
                    Icon(WIcon.shield, null, tint = tk.accent, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(s("Block domains across all devices", "حجب الدومينات على كافة الأجهزة"),
                        fontFamily = tk.ui, fontWeight = FontWeight.W600, fontSize = 13.5.sp, color = tk.ink)
                    Spacer(Modifier.height(4.dp))
                    Text(s("To block specific sites (social media, adult content, or custom domains), choose NextDNS or AdGuard in the DNS list above. NextDNS gives you a private dashboard to block any custom domain instantly.",
                        "لحجب مواقع معينة (كالتواصل الاجتماعي أو محتوى محدد أو دومين مخصص)، اختر NextDNS أو AdGuard من قائمة DNS بالأعلى. يتيح لك NextDNS لوحة تحكم مجانية لحظر أي نطاق تختاره فوراً."),
                        fontFamily = tk.ui, fontSize = 12.sp, color = tk.ink2)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        SectionHeader(s("MTU size", "حجم MTU")) { if (vm.mtu != null) Badge("${vm.mtu} B", tk.accent, tk.accentSoft) }
        Text(s("Max transmission unit of your internet link — it affects browsing and speed. Wrong values can drop the connection.",
            "أكبر حجم لرزمة البيانات على خط الإنترنت — يؤثر على التصفح والسرعة. قيم خاطئة قد تقطع الإنترنت."),
            fontFamily = tk.ui, fontSize = 12.sp, color = tk.ink2)
        LaunchedEffect(vm.mtu) { if (vm.mtu != null && mtuVal.isBlank()) mtuVal = vm.mtu!!.toString() }
        val presets = intArrayOf(1400, 1450, 1472, 1492, 1500)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { p ->
                val on = mtuVal.toIntOrNull() == p
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (on) tk.accentSoft else tk.surface3)
                        .border(1.5.dp, if (on) tk.accent else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { mtuVal = p.toString() },
                    Alignment.Center
                ) { Text(p.toString(), fontFamily = tk.mono, fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.W700 else FontWeight.W500,
                    color = if (on) tk.accent else tk.ink2) }
            }
        }
        Field(vm, s("MTU (bytes)", "حجم MTU (بايت)"), mtuVal, { mtuVal = it },
            mono = true, keyboard = KeyboardType.Number)
        PrimaryButton(s("Save MTU", "حفظ MTU"), null, false,
            (mtuVal.toIntOrNull() ?: 0) in 576..1500 && (mtuVal.toIntOrNull() ?: 0) != vm.mtu) {
            val v = mtuVal.toIntOrNull() ?: return@PrimaryButton
            setSheet(Confirm(
                s("Change MTU", "تغيير MTU"),
                s("Applied to the internet link of the router, then it will restart.",
                    "يُطبَّق على خط الإنترنت الخاص بالراوتر، ثم سيعيد التشغيل."),
                listOf("MTU" to "$v", s("Current", "الحالي") to (vm.mtu?.toString() ?: "—")),
                s("Apply & restart", "تطبيق وإعادة تشغيل"), false
            ) { vm.applyMtu(v) })
        }
        WifiRadioSection(vm, setSheet)
        Spacer(Modifier.height(4.dp))
        VersionFooter()
    }
}

@Composable
private fun WifiRadioSection(vm: RouterViewModel, setSheet: (Sheet?) -> Unit) {
    val tk = tk()
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)
    val radios = vm.wifiRadios
    if (radios.isEmpty()) return

    // default to the band this phone is actually on, else the first radio
    var band by remember { mutableStateOf("") }
    LaunchedEffect(radios) {
        if (band.isBlank() || radios.none { it.band == band }) {
            val live = vm.wifiLink?.let { if (it.freqMhz in 1 until 2500) "2.4GHz" else "5GHz" }
            band = if (live != null && radios.any { it.band == live }) live else radios.first().band
        }
    }
    val radio = radios.firstOrNull { it.band == band } ?: return

    var auto by remember { mutableStateOf(true) }
    var chan by remember { mutableIntStateOf(0) }
    var width by remember { mutableIntStateOf(0) }
    var power by remember { mutableIntStateOf(100) }
    LaunchedEffect(radio) { auto = radio.autoChannel; chan = radio.channel; width = radio.width; power = radio.power }

    val is24 = band.startsWith("2.4")
    val channelOpts = (if (is24) (1..13).toList()
        else listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161))
        .map { it to "$it" }
    val widthCodes = listOf(0, 1, 2)
    val widthOpts = widthCodes.map {
        it to when (it) {
            0 -> s("Auto", "تلقائي")
            1 -> "20 MHz"
            else -> "40 MHz"
        }
    }
    val powerOpts = listOf(20, 40, 60, 80, 100).map { it to "$it%" }
    val changed = auto != radio.autoChannel || (!auto && chan != radio.channel) || width != radio.width || power != radio.power

    SectionHeader(s("Wi-Fi radio", "راديو الواي فاي")) { Badge(s("Restarts", "يعيد التشغيل"), tk.warn, tk.warnSoft) }
    Text(s("Channel and width. Auto / 40 MHz gives the best rate at typical 5 GHz signal — an 80 MHz channel can be slower and won't raise speed when the line is the limit.",
        "القناة والعرض. تلقائي / 40 ميجاهرتز بيدي أفضل معدل عند إشارة 5GHz المعتادة — عرض 80 ميجاهرتز ممكن يبقى أبطأ ومش بيزوّد السرعة لما يكون الخط هو الحد."),
        fontFamily = tk.ui, fontSize = 12.sp, color = tk.ink2)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        radios.map { it.band }.forEach { b ->
            val on = band == b
            Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp))
                .background(if (on) tk.accentSoft else tk.surface3)
                .border(1.5.dp, if (on) tk.accent else Color.Transparent, RoundedCornerShape(10.dp))
                .clickable { band = b }, Alignment.Center) {
                Text(b, fontFamily = tk.ui, fontSize = 12.5.sp,
                    fontWeight = if (on) FontWeight.W700 else FontWeight.W500, color = if (on) tk.accent else tk.ink2)
            }
        }
    }

    Text(s("Channel", "القناة"), fontFamily = tk.ui, fontSize = 12.sp, color = tk.ink2)
    OptChipRow(listOf(-1 to s("Auto", "تلقائي")) + channelOpts, if (auto) -1 else chan, tk) { v ->
        if (v == -1) auto = true else { auto = false; chan = v }
    }

    Text(s("Channel width", "عرض القناة"), fontFamily = tk.ui, fontSize = 12.sp, color = tk.ink2)
    OptChipRow(widthOpts, width, tk) { width = it }

    Text(s("Transmit power", "قوة الإرسال"), fontFamily = tk.ui, fontSize = 12.sp, color = tk.ink2)
    OptChipRow(powerOpts, power, tk) { power = it }

    PrimaryButton(s("Apply Wi-Fi settings", "تطبيق إعدادات الواي فاي"), null, false, changed) {
        setSheet(Confirm(
            s("Change Wi-Fi radio", "تغيير راديو الواي فاي"),
            s("Applied to $band, then the router will restart.", "يُطبَّق على $band، ثم سيعيد الراوتر التشغيل."),
            listOf(
                s("Channel", "القناة") to (if (auto) s("Auto", "تلقائي") else "$chan"),
                s("Width", "العرض") to (widthOpts.firstOrNull { it.first == width }?.second ?: "$width"),
                s("Power", "القوة") to "$power%",
            ),
            s("Apply & restart", "تطبيق وإعادة تشغيل"), false
        ) { vm.applyWifiBand(band, if (chan == 0) 1 else chan, auto, width, power) })
    }
}

@Composable
private fun OptChipRow(options: List<Pair<Int, String>>, selected: Int, tk: Tokens, onSel: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (v, label) ->
            val on = selected == v
            Box(
                Modifier.height(36.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (on) tk.accentSoft else tk.surface3)
                    .border(1.5.dp, if (on) tk.accent else Color.Transparent, RoundedCornerShape(9.dp))
                    .clickable { onSel(v) }.padding(horizontal = 12.dp),
                Alignment.Center
            ) {
                Text(label, fontFamily = tk.mono, fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.W700 else FontWeight.W500, color = if (on) tk.accent else tk.ink2)
            }
        }
    }
}

/* ============ MORE ============ */
@Composable
private fun MoreScreen(vm: RouterViewModel, setSheet: (Sheet?) -> Unit) {
    val tk = tk()
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)
    var led by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(s("Wi-Fi & settings", "الواي فاي والإعدادات"), null)
        PanelCard {
            Row(
                Modifier.fillMaxWidth().clickable { setSheet(SsidEdit(vm.ssidMain)) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(tk.indigoSoft), Alignment.Center) {
                    Icon(WIcon.wifi, null, tint = tk.indigo, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s("Wi-Fi name (SSID)", "اسم الواي فاي"), fontFamily = tk.ui, fontWeight = FontWeight.W600, fontSize = 14.sp, color = tk.ink)
                    Text(vm.ssidMain.ifEmpty { "—" }, fontFamily = tk.mono, fontSize = 12.sp, color = tk.ink2)
                }
                Badge(s("Edit", "تعديل"), tk.accent, tk.accentSoft)
            }
            Divider2()
            InfoRow(WIcon.lock, s("Wi-Fi password", "باسورد الواي فاي"),
                s("Protected by router", "محمي بفيرموير الراوتر"), tk.ink3, tk.surface3) { Badge(s("Encrypted", "مشفّر"), tk.ink3, tk.surface3) }
            Divider2()
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(tk.goodSoft), Alignment.Center) {
                    Icon(WIcon.bulb, null, tint = tk.good, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s("Device LEDs", "لمبات الجهاز"), fontFamily = tk.ui, fontWeight = FontWeight.W600, fontSize = 14.sp, color = tk.ink)
                    Text(s("Verified live control", "تحكّم لحظي مؤكَّد"), fontFamily = tk.ui, fontSize = 12.sp, color = tk.ink2)
                }
                SmallSwitch(checked = led, onCheckedChange = { led = it; vm.setLed(off = !it) })
            }
        }
        SectionHeader(s("Appearance", "المظهر"), null)
        PanelCard { Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChip(vm, "system", WIcon.auto, s("System", "النظام"), Modifier.weight(1f))
                ThemeChip(vm, "light", WIcon.sun, s("Light", "فاتح"), Modifier.weight(1f))
                ThemeChip(vm, "dark", WIcon.moon, s("Dark", "داكن"), Modifier.weight(1f))
            }
        } }

        SectionHeader(s("Language", "اللغة"), null)
        PanelCard { Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LangChip(vm, "en", WIcon.globe, "English", Modifier.weight(1f))
                LangChip(vm, "ar", WIcon.globe, "العربية", Modifier.weight(1f))
            }
        } }

        SectionHeader(s("About & updates", "حول والتحديثات"), null)
        PanelCard {
            val up = vm.updateAvailable
            val checking = vm.updateChecking
            val downloading = vm.updateDownloading
            Row(
                Modifier.fillMaxWidth().clickable {
                    if (up) vm.installUpdate() else vm.checkUpdate()
                }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (up) tk.accentSoft else tk.surface3), Alignment.Center) {
                    Icon(WIcon.download, null, tint = if (up) tk.accent else tk.ink2, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            downloading -> s("Downloading…", "جاري التنزيل…")
                            checking -> s("Checking…", "جاري الفحص…")
                            up -> s("Update available", "توجد نسخة جديدة")
                            else -> s("Check for updates", "التحقق من التحديثات")
                        },
                        fontFamily = tk.ui, fontWeight = FontWeight.W600, fontSize = 14.sp, color = tk.ink)
                    Text(
                        when {
                            vm.updateMsg.isNotBlank() -> vm.updateMsg
                            else -> "v" + BuildConfig.VERSION_NAME
                        },
                        fontFamily = tk.mono, fontSize = 12.sp,
                        color = when { up -> tk.accent; checking -> tk.ink2; else -> tk.ink3 }, maxLines = 2)
                }
                if (up) Badge(s("Install", "تثبيت"), tk.accent, tk.accentSoft)
                else Icon(WIcon.chevron, null, tint = tk.ink3)
            }
        }

        SectionHeader(s("Device info", "معلومات الجهاز")) { Badge(s("Live", "مباشر"), tk.accent, tk.accentSoft) }
        PanelCard {
            KV(s("Model", "الموديل"), "HG8145V5"); Divider2()
            KV(s("Firmware", "الفيرموير"), "V5R022C10S203"); Divider2()
            KV(s("Serial number", "الرقم التسلسلي"), vm.serial ?: "—")
        }
        WarnButton(s("Restart router", "إعادة تشغيل الراوتر")) { setSheet(rebootConfirm(vm)) }
        DangerButton(s("Sign out", "تسجيل الخروج")) { vm.logout() }
        Spacer(Modifier.height(4.dp))
        VersionFooter()
    }
}

@Composable private fun ThemeChip(vm: RouterViewModel, mode: String, icon: ImageVector, label: String, m: Modifier) {
    val tk = tk()
    val on = vm.themeMode == mode
    Column(
        m.clip(RoundedCornerShape(12.dp))
            .background(if (on) tk.accentSoft else tk.surface3)
            .border(1.5.dp, if (on) tk.accent else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable { vm.setTheme(mode) }.padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = if (on) tk.accent else tk.ink2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(5.dp))
        Text(label, fontFamily = tk.ui, fontSize = 12.sp, fontWeight = FontWeight.W600, color = if (on) tk.accent else tk.ink2)
    }
}

@Composable private fun LangChip(vm: RouterViewModel, code: String, icon: ImageVector, label: String, m: Modifier) {
    val tk = tk()
    val on = vm.lang == code
    Column(
        m.clip(RoundedCornerShape(12.dp))
            .background(if (on) tk.accentSoft else tk.surface3)
            .border(1.5.dp, if (on) tk.accent else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable { vm.chooseLang(code) }.padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = if (on) tk.accent else tk.ink2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(5.dp))
        Text(label, fontFamily = tk.ui, fontSize = 12.sp, fontWeight = FontWeight.W600, color = if (on) tk.accent else tk.ink2)
    }
}

@Composable private fun KV(k: String, v: String) {
    val tk = tk()
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k, fontFamily = tk.ui, fontSize = 13.sp, color = tk.ink2, modifier = Modifier.weight(1f))
        Text(v, fontFamily = tk.mono, fontSize = 12.5.sp, color = tk.ink)
    }
}
@Composable private fun InfoRow(icon: ImageVector, title: String, value: String, fg: Color, bg: Color, trailing: @Composable () -> Unit) {
    val tk = tk()
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(bg), Alignment.Center) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = tk.ui, fontWeight = FontWeight.W600, fontSize = 14.sp, color = tk.ink)
            Text(value, fontFamily = tk.mono, fontSize = 11.5.sp, color = tk.ink2)
        }
        trailing()
    }
}

/* ============ SHEET HOST ============ */
@Composable
private fun SheetHost(sheet: Sheet?, vm: RouterViewModel, setSheet: (Sheet?) -> Unit) {
    val tk = tk()
    val open = sheet != null
    // scrim
    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color(0x80060C14)).clickable { setSheet(null) })
    }
    AnimatedVisibility(
        open, modifier = Modifier.fillMaxSize(),
        enter = slideInVertically(initialOffsetY = { it }), exit = slideOutVertically(targetOffsetY = { it })
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(tk.panel).navigationBarsPadding().padding(20.dp)
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 14.dp)
                    .size(width = 38.dp, height = 4.dp).clip(CircleShape).background(tk.line2))
                when (val sh = sheet) {
                    is Confirm -> ConfirmBody(vm, sh, setSheet)
                    is DeviceCtl -> DeviceBody(vm, sh.d, setSheet)
                    is SsidEdit -> SsidEditBody(vm, sh, setSheet)
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SsidEditBody(vm: RouterViewModel, sh: SsidEdit, setSheet: (Sheet?) -> Unit) {
    val tk = tk()
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)
    var name by remember { mutableStateOf(sh.current) }
    Box(Modifier.align(Alignment.CenterHorizontally).size(52.dp).clip(RoundedCornerShape(16.dp))
        .background(tk.indigoSoft), Alignment.Center) {
        Icon(WIcon.wifi, null, tint = tk.indigo, modifier = Modifier.size(26.dp))
    }
    Spacer(Modifier.height(14.dp))
    Text(s("Change Wi-Fi Name (SSID)", "تعديل اسم شبكة الواي فاي"), fontFamily = tk.ui, fontWeight = FontWeight.W700, fontSize = 19.sp, color = tk.ink,
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(s("Changing Wi-Fi name requires the router to restart.", "تعديل اسم الشبكة يتطلب إعادة تشغيل الراوتر لتطبيقه."),
        fontFamily = tk.ui, fontSize = 13.sp, color = tk.ink2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(14.dp))
    Field(vm, s("New Wi-Fi name", "اسم الشبكة الجديد"), name, { name = it }, mono = true)
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton(s("Cancel", "إلغاء"), Modifier.weight(1f)) { setSheet(null) }
        FilledSheetButton(s("Save & Restart", "حفظ وإعادة تشغيل"), Modifier.weight(1f), tk.accent, Color.White) {
            if (name.isNotBlank() && name != sh.current) {
                setSheet(null)
                vm.applySsid(name.trim())
            }
        }
    }
}

@Composable
private fun ColumnScope.ConfirmBody(vm: RouterViewModel, sh: Confirm, setSheet: (Sheet?) -> Unit) {
    val tk = tk()
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)
    Box(Modifier.align(Alignment.CenterHorizontally).size(52.dp).clip(RoundedCornerShape(16.dp))
        .background(if (sh.danger) tk.badSoft else tk.warnSoft), Alignment.Center) {
        Icon(if (sh.danger) WIcon.block else WIcon.restart, null,
            tint = if (sh.danger) tk.bad else tk.warn, modifier = Modifier.size(26.dp))
    }
    Spacer(Modifier.height(14.dp))
    Text(sh.title, fontFamily = tk.ui, fontWeight = FontWeight.W700, fontSize = 19.sp, color = tk.ink,
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(sh.msg, fontFamily = tk.ui, fontSize = 13.sp, color = tk.ink2, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth())
    if (sh.rows.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tk.surface3).padding(horizontal = 15.dp, vertical = 8.dp)) {
            sh.rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(k, fontFamily = tk.ui, fontSize = 13.sp, color = tk.ink2)
                    Text(v, fontFamily = tk.mono, fontSize = 13.sp, color = tk.ink)
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton(s("Cancel", "إلغاء"), Modifier.weight(1f)) { setSheet(null) }
        FilledSheetButton(sh.confirm, Modifier.weight(1f), if (sh.danger) tk.bad else tk.warn, Color.White) {
            setSheet(null); sh.onOk()
        }
    }
}

@Composable
private fun ColumnScope.DeviceBody(vm: RouterViewModel, d: Device, setSheet: (Sheet?) -> Unit) {
    val tk = tk()
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)
    Text(d.name, fontFamily = tk.ui, fontWeight = FontWeight.W700, fontSize = 18.sp, color = tk.ink,
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(d.ip, fontFamily = tk.mono, fontSize = 12.sp, color = tk.ink2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(d.mac, fontFamily = tk.mono, fontSize = 11.sp, color = tk.ink3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(16.dp))

    val macKey = d.mac.uppercase()
    // NOTE: the stored value is a STABLE key ("5"/"10"/"20"/"Max"), never a translated label,
    // otherwise switching language would make the lookup fail.
    val opts = listOf(
        SpeedOpt("5", "5", 5000, 2000),
        SpeedOpt("10", "10", 10000, 4000),
        SpeedOpt("20", "20", 20000, 8000),
        SpeedOpt("Max", s("Max", "بلا حدود"), 0, 0)
    )
    val cur = (vm.speedLabel[macKey] ?: "Max").let { saved -> if (opts.any { it.key == saved }) saved else "Max" }
    var sel by remember(macKey) { mutableStateOf(cur) }
    val changed = sel != cur
    val opt = opts.firstOrNull { it.key == sel } ?: opts.last()

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(s("Speed limit (download)", "تحديد السرعة (تنزيل)"),
            fontFamily = tk.ui, fontWeight = FontWeight.W600, fontSize = 13.sp, color = tk.ink,
            modifier = Modifier.weight(1f))
        Text("≈ " + (if (opt.ds <= 0) "∞" else "%.1f MB/s".format(opt.ds / 8000.0)),
            fontFamily = tk.mono, fontSize = 11.sp, color = tk.ink2)
    }
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        opts.forEach { o ->
            SpeedChip(o.label, selected = sel == o.key, m = Modifier.weight(1f)) { sel = o.key }
        }
    }
    Text(s("Save restarts the router → ~2 min.",
            "الحفظ يعيد تشغيل الراوتر ← ~دقيقتين."),
        fontFamily = tk.ui, fontSize = 11.sp, color = tk.ink3)
    Spacer(Modifier.height(16.dp))

    if (changed) {
        FilledSheetButton(s("Save speed limit", "حفظ السرعة"), Modifier.fillMaxWidth(), tk.warn, Color.White) {
            setSheet(Confirm(
                s("Apply speed limit?", "تطبيق السرعة؟"),
                s("The router will restart (~2 minutes) to apply it.",
                        "سيُعاد تشغيل الراوتر (~دقيقتين) للتطبيق."),
                listOf(
                    s("Device", "الجهاز") to d.name,
                    s("Limit", "الحد") to (if (opt.key == "Max") s("Max — no limit", "بلا حدود") else "${opt.key} Mbps")
                ),
                s("Apply & restart", "تطبيق وإعادة تشغيل"), false
            ) { vm.setSpeed(d.mac, opt.ds, opt.us, opt.key) })
        }
    } else if (vm.blocked.containsKey(macKey)) {
        FilledSheetButton(s("Unblock this device", "فك حجب الجهاز"), Modifier.fillMaxWidth(), tk.good, Color.White) {
            setSheet(Confirm(
                s("Unblock this device?", "فك حجب الجهاز؟"),
                s("Its internet access will be restored immediately.", "هيرجع له الإنترنت فوراً."),
                listOf(s("Device", "الجهاز") to d.name, "MAC" to d.mac),
                s("Unblock now", "فك الحجب الآن"), false
            ) { vm.unblockDevice(d.mac) })
        }
    } else {
        FilledSheetButton(s("Block this device", "حظر هذا الجهاز"), Modifier.fillMaxWidth(), tk.bad, Color.White) {
            setSheet(Confirm(
                s("Block this device?", "حجب الجهاز؟"),
                s("Its internet will be cut immediately.", "هيتقطع عنه الإنترنت فوراً."),
                listOf(s("Device", "الجهاز") to d.name, "MAC" to d.mac),
                s("Block now", "حجب الآن"), true
            ) { vm.blockDevice(d.mac, d.name) })
        }
    }
    Spacer(Modifier.height(8.dp))
    GhostButton(s("Cancel", "إلغاء"), Modifier.fillMaxWidth()) { setSheet(null) }
}

@Composable private fun SpeedChip(label: String, selected: Boolean, m: Modifier, onClick: () -> Unit) {
    val tk = tk()
    Box(
        m.clip(RoundedCornerShape(12.dp))
            .background(if (selected) tk.accent else tk.surface3)
            .clickable(onClick = onClick).padding(vertical = 12.dp),
        Alignment.Center
    ) {
        Text(label, fontFamily = tk.disp, fontWeight = FontWeight.W700, fontSize = 15.sp,
            color = if (selected) Color.White else tk.ink)
    }
}

/* ============ RESTART OVERLAY ============ */
@Composable
private fun RestartOverlay(vm: RouterViewModel) {
    val tk = tk()
    val done = vm.restartStep >= 2 && vm.conn == Conn.Connected
    Box(Modifier.fillMaxSize().background(tk.surface2.copy(alpha = .97f)).clickable(enabled = false) {}, Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(30.dp)) {
            if (done) Icon(Icons.Filled.CheckCircle, null, tint = tk.good, modifier = Modifier.size(72.dp))
            else CircularProgressIndicator(Modifier.size(72.dp), strokeWidth = 4.dp, color = tk.accent, trackColor = tk.surface3)
            Spacer(Modifier.height(22.dp))
            Text(vm.restartTitle, fontFamily = tk.ui, fontWeight = FontWeight.W700, fontSize = 20.sp, color = tk.ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(vm.restartMsg, fontFamily = tk.ui, fontSize = 13.sp, color = tk.ink2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { i ->
                    Box(Modifier.size(8.dp).clip(CircleShape)
                        .background(if (i <= vm.restartStep) tk.accent else tk.line2))
                }
            }
        }
    }
}

/* ============ SHARED WIDGETS ============ */
@Composable private fun IconBtn(icon: ImageVector, cd: String, onClick: () -> Unit) {
    val tk = tk()
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(tk.panel)
        .border(1.dp, tk.line, RoundedCornerShape(9.dp)).clickable(onClick = onClick), Alignment.Center) {
        Icon(icon, cd, tint = tk.ink2, modifier = Modifier.size(16.dp))
    }
}

@Composable private fun LangButton(vm: RouterViewModel) {
    val tk = tk()
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(tk.panel)
        .border(1.dp, tk.line, RoundedCornerShape(9.dp)).clickable { vm.toggleLang() }, Alignment.Center) {
        Text(if (vm.lang == "ar") "EN" else "ع", fontFamily = tk.disp, fontWeight = FontWeight.W700, fontSize = 12.sp, color = tk.ink2)
    }
}

@Composable private fun ConnPill(vm: RouterViewModel) {
    val tk = tk()
    fun s(en: String, ar: String) = tr(vm.lang, en, ar)
    val (c, label) = when (vm.conn) {
        Conn.Connected -> tk.good to s("Connected", "متصل")
        Conn.Connecting -> tk.warn to s("Connecting", "جاري الاتصال")
        Conn.Disconnected -> tk.bad to s("Disconnected", "منقطع")
    }
    val a by rememberInfiniteTransition("p").animateFloat(.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Row(
        Modifier.clip(CircleShape).background(c.copy(alpha = .12f)).border(1.dp, c.copy(alpha = .35f), CircleShape)
            .clickable { vm.cycleConn() }.padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(c.copy(alpha = if (vm.conn == Conn.Connecting) a else 1f)))
        Spacer(Modifier.width(6.dp))
        Text(label, color = c, fontFamily = tk.ui, fontSize = 12.sp, fontWeight = FontWeight.W600, maxLines = 1)
    }
}

@Composable private fun SectionHeader(title: String, trailing: (@Composable () -> Unit)?) {
    val tk = tk()
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontFamily = tk.ui, fontWeight = FontWeight.W700, fontSize = 14.5.sp, color = tk.ink, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable private fun Badge(text: String, fg: Color, bg: Color) {
    val tk = tk()
    Row(Modifier.clip(CircleShape).background(bg).padding(horizontal = 9.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(fg)); Spacer(Modifier.width(5.dp))
        Text(text, color = fg, fontFamily = tk.ui, fontSize = 11.sp, fontWeight = FontWeight.W600)
    }
}

@Composable private fun ChipLabel(text: String, fg: Color, bg: Color) {
    val tk = tk()
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, color = fg, fontFamily = tk.ui, fontSize = 10.5.sp, fontWeight = FontWeight.W600)
    }
}

@Composable private fun PanelCard(m: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val tk = tk()
    Column(
        m.shadow(6.dp, RoundedCornerShape(18.dp), clip = false, ambientColor = Color(0x14101E2D), spotColor = Color(0x14101E2D))
            .clip(RoundedCornerShape(18.dp)).background(tk.panel).border(1.dp, tk.line, RoundedCornerShape(18.dp)),
        content = content
    )
}

@Composable private fun Divider2() { val tk = tk(); Box(Modifier.fillMaxWidth().height(1.dp).background(tk.line)) }

@Composable private fun Field(
    vm: RouterViewModel, label: String, value: String, onChange: (String) -> Unit,
    mono: Boolean = false, password: Boolean = false, keyboard: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null
) {
    val tk = tk()
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(label, fontFamily = tk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.W600, color = tk.ink2, modifier = Modifier.padding(bottom = 6.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = trailing,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = if (mono) tk.mono else tk.ui, fontSize = 14.sp, color = tk.ink),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = tk.accent, unfocusedBorderColor = tk.line,
                focusedContainerColor = tk.panel, unfocusedContainerColor = tk.panel,
                cursorColor = tk.accent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable private fun PrimaryButton(text: String, icon: ImageVector?, loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tk = tk()
    Box(
        Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
            .background(if (enabled) tk.accent else tk.line2).clickable(enabled = enabled, onClick = onClick),
        Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = tk.accentInk); Spacer(Modifier.width(8.dp)) }
            else if (icon != null) { Icon(icon, null, tint = tk.accentInk); Spacer(Modifier.width(8.dp)) }
            Text(text, color = tk.accentInk, fontFamily = tk.ui, fontWeight = FontWeight.W700, fontSize = 15.sp)
        }
    }
}

@Composable private fun WarnButton(text: String, onClick: () -> Unit) {
    val tk = tk()
    Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(tk.warn).clickable(onClick = onClick), Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(WIcon.restart, null, tint = Color.White); Spacer(Modifier.width(8.dp))
            Text(text, color = Color.White, fontFamily = tk.ui, fontWeight = FontWeight.W700, fontSize = 15.sp)
        }
    }
}

@Composable private fun DangerButton(text: String, onClick: () -> Unit) {
    val tk = tk()
    Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(tk.badSoft).clickable(onClick = onClick), Alignment.Center) {
        Text(text, color = tk.bad, fontFamily = tk.ui, fontWeight = FontWeight.W700, fontSize = 15.sp)
    }
}

@Composable private fun GhostButton(text: String, m: Modifier, onClick: () -> Unit) {
    val tk = tk()
    Box(m.height(48.dp).clip(RoundedCornerShape(14.dp)).background(tk.surface3).clickable(onClick = onClick), Alignment.Center) {
        Text(text, color = tk.ink, fontFamily = tk.ui, fontWeight = FontWeight.W700, fontSize = 15.sp)
    }
}

@Composable private fun FilledSheetButton(text: String, m: Modifier, bg: Color, fg: Color, onClick: () -> Unit) {
    val tk = tk()
    Box(m.height(48.dp).clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick), Alignment.Center) {
        Text(text, color = fg, fontFamily = tk.ui, fontWeight = FontWeight.W700, fontSize = 15.sp)
    }
}

@Composable private fun ToastBar(msg: String, modifier: Modifier) {
    val tk = tk()
    Box(modifier.clip(RoundedCornerShape(13.dp)).background(tk.ink).padding(horizontal = 16.dp, vertical = 13.dp)) {
        Text(msg, color = tk.surface2, fontFamily = tk.ui, fontSize = 13.sp, fontWeight = FontWeight.W600)
    }
}

@Composable private fun RouterLogo(size: androidx.compose.ui.unit.Dp) {
    val tk = tk()
    Box(Modifier.size(size).clip(RoundedCornerShape(20.dp))
        .background(Brush.linearGradient(listOf(tk.accent2, tk.indigo))), Alignment.Center) {
        Icon(WIcon.router, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable private fun swColors(tk: Tokens) = androidx.compose.material3.SwitchDefaults.colors(
    checkedThumbColor = Color.White, checkedTrackColor = tk.accent,
    uncheckedThumbColor = Color.White, uncheckedTrackColor = tk.line2, uncheckedBorderColor = Color.Transparent
)

@Composable private fun SmallSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val tk = tk()
    Switch(checked = checked, onCheckedChange = onCheckedChange, colors = swColors(tk),
        modifier = Modifier.scale(.82f))
}
