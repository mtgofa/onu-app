package com.hg8145v5.manager.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.hg8145v5.manager.R

/* ---- fonts (bundled IBM Plex + Sora) ---- */
val Sora = FontFamily(
    Font(R.font.sora_semibold, FontWeight.W600),
    Font(R.font.sora_bold, FontWeight.W700),
    Font(R.font.sora_extrabold, FontWeight.W800),
)
val PlexLatin = FontFamily(
    Font(R.font.plex_regular, FontWeight.W400),
    Font(R.font.plex_semibold, FontWeight.W600),
    Font(R.font.plex_bold, FontWeight.W700),
)
val PlexArabic = FontFamily(
    Font(R.font.plexar_regular, FontWeight.W400),
    Font(R.font.plexar_semibold, FontWeight.W600),
    Font(R.font.plexar_bold, FontWeight.W700),
)
val PlexMono = FontFamily(
    Font(R.font.plexmono_regular, FontWeight.W400),
    Font(R.font.plexmono_medium, FontWeight.W500),
)

/* ---- exact palette tokens (mirrors the web CSS variables) ---- */
@Immutable
data class Tokens(
    val bg: Color, val panel: Color, val surface2: Color, val surface3: Color,
    val ink: Color, val ink2: Color, val ink3: Color, val line: Color, val line2: Color,
    val accent: Color, val accent2: Color, val accentInk: Color, val accentSoft: Color,
    val indigo: Color, val indigoSoft: Color,
    val good: Color, val goodSoft: Color, val warn: Color, val warnSoft: Color,
    val bad: Color, val badSoft: Color,
    val ui: FontFamily, val disp: FontFamily, val mono: FontFamily,
    val dark: Boolean,
)

private val Light = { ui: FontFamily ->
    Tokens(
        bg = Color(0xFFE9EDF3), panel = Color(0xFFFFFFFF), surface2 = Color(0xFFF4F7FA), surface3 = Color(0xFFEDF1F6),
        ink = Color(0xFF0E1620), ink2 = Color(0xFF54636F), ink3 = Color(0xFF8493A1),
        line = Color(0xFFE1E7EE), line2 = Color(0xFFD2DAE3),
        accent = Color(0xFF0AA79A), accent2 = Color(0xFF0BB8A6), accentInk = Color(0xFFFFFFFF), accentSoft = Color(0xFFDCF3F0),
        indigo = Color(0xFF3D5AF1), indigoSoft = Color(0xFFE4E8FE),
        good = Color(0xFF1F9D57), goodSoft = Color(0xFFDDF3E6), warn = Color(0xFFD98211), warnSoft = Color(0xFFFBEBD3),
        bad = Color(0xFFDA3B3B), badSoft = Color(0xFFFBE1E1),
        ui = ui, disp = Sora, mono = PlexMono, dark = false,
    )
}
private val Dark = { ui: FontFamily ->
    Tokens(
        bg = Color(0xFF080B11), panel = Color(0xFF0E141D), surface2 = Color(0xFF0F161F), surface3 = Color(0xFF182230),
        ink = Color(0xFFE8EEF4), ink2 = Color(0xFF9DABBA), ink3 = Color(0xFF6C7C8C),
        line = Color(0xFF1E2A38), line2 = Color(0xFF28374A),
        accent = Color(0xFF15C9B6), accent2 = Color(0xFF1AD8C4), accentInk = Color(0xFF04211E), accentSoft = Color(0xFF0C2E2B),
        indigo = Color(0xFF6E86FF), indigoSoft = Color(0xFF141E3C),
        good = Color(0xFF39C578), goodSoft = Color(0xFF0E2A1C), warn = Color(0xFFF0A93C), warnSoft = Color(0xFF31240E),
        bad = Color(0xFFF0605F), badSoft = Color(0xFF331616),
        ui = ui, disp = Sora, mono = PlexMono, dark = true,
    )
}

val LocalTokens = staticCompositionLocalOf { Light(PlexLatin) }

@Composable
fun OnuTheme(dark: Boolean, lang: String, content: @Composable () -> Unit) {
    val uiFamily = if (lang == "ar") PlexArabic else PlexLatin
    val tokens = if (dark) Dark(uiFamily) else Light(uiFamily)
    val scheme = if (dark) darkColorScheme(
        primary = tokens.accent, onPrimary = tokens.accentInk, secondary = tokens.indigo,
        background = tokens.surface2, surface = tokens.panel, onSurface = tokens.ink, error = tokens.bad
    ) else lightColorScheme(
        primary = tokens.accent, onPrimary = tokens.accentInk, secondary = tokens.indigo,
        background = tokens.surface2, surface = tokens.panel, onSurface = tokens.ink, error = tokens.bad
    )
    CompositionLocalProvider(LocalTokens provides tokens) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
