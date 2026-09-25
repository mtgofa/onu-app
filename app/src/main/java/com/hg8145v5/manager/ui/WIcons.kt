package com.hg8145v5.manager.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Icons built from the EXACT SVG path data used in the original web design,
 * so the app renders the same glyphs, not Material look-alikes. Stroke-based; Icon() tints them.
 */
object WIcon {
    private fun ic(vararg strokes: String, fills: Array<String> = arrayOf(), sw: Float = 2f): ImageVector {
        val b = ImageVector.Builder(
            name = "w", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        )
        strokes.forEach {
            b.addPath(
                PathParser().parsePathString(it).toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = sw,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            )
        }
        fills.forEach { b.addPath(PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.Black)) }
        return b.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    // router with two antennas + LEDs (logo & brand)
    val router = ic(
        "M6.5 13.2L4.2 3.6", "M17.5 13.2L19.8 3.6",
        "M4.7 13.2h14.6a2.2 2.2 0 0 1 2.2 2.2v2.6a2.2 2.2 0 0 1 -2.2 2.2h-14.6a2.2 2.2 0 0 1 -2.2 -2.2v-2.6a2.2 2.2 0 0 1 2.2 -2.2z",
        "M13.5 16.7h5",
        fills = arrayOf(circle(6f, 16.7f, .75f), circle(8.6f, 16.7f, .75f)), sw = 1.7f
    )
    val home = ic("M3 11l9-8 9 8", "M5 10v10h14V10")
    val devices = ic("M6 4h12a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2H6a2 2 0 0 1 -2 -2V6a2 2 0 0 1 2 -2z", "M8 20h8", "M12 16v4")
    val shield = ic("M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z")
    val more = ic(fills = arrayOf(circle(5f, 12f, 1.6f), circle(12f, 12f, 1.6f), circle(19f, 12f, 1.6f)))
    val wifi = ic("M5 13a10 10 0 0 1 14 0", "M8.5 16.5a5 5 0 0 1 7 0", fills = arrayOf(circle(12f, 20f, 1.4f)))
    val device = ic("M6 3h12a1 1 0 0 1 1 1v16a1 1 0 0 1 -1 1H6a1 1 0 0 1 -1 -1V4a1 1 0 0 1 1 -1z", "M11 18h2")
    val download = ic("M12 20V10", "M18 20V4", "M6 20v-4")     // web "download" (bars)
    val upload = ic("M12 19V5", "M5 12l7-7 7 7")
    val block = ic("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0 -20", "M4.9 4.9l14.2 14.2")
    val restart = ic("M21 12a9 9 0 1 1 -3 -6.7", "M21 3v5h-5")
    val lock = ic("M3 11h18v10H3z", "M7 11V7a5 5 0 0 1 10 0v4")
    val chevron = ic("M9 18l6-6-6-6")
    val eye = ic("M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z", fills = arrayOf(circle(12f, 12f, 3f)))
    val eyeOff = ic("M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z", "M3 3l18 18", fills = arrayOf(circle(12f, 12f, 3f)))
    val plus = ic("M12 5v14", "M5 12h14")
    val bulb = ic("M9 18h6", "M10 21h4", "M12 3a6 6 0 0 0 -4 10c1 1 1 2 1 3h6c0-1 0-2 1-3a6 6 0 0 0 -4 -10z")
    val sun = ic("M12 3v2", "M12 19v2", "M3 12h2", "M19 12h2", "M5.6 5.6l1.4 1.4", "M17 17l1.4 1.4", "M5.6 18.4l1.4-1.4", "M17 7l1.4-1.4", fills = arrayOf(circle(12f, 12f, 4f)))
    val moon = ic("M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z")
    val auto = ic("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0 -18", "M12 8v8", "M9 12h6")
    val search = ic("M10.5 3a7.5 7.5 0 0 1 5.6 12.6l4.2 4.4", "M3 10.5a7.5 7.5 0 1 1 14 0", sw = 1.8f)
    val close = ic("M5 5l14 14", "M19 5L5 19", sw = 1.8f)
    val info = ic("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0 -20", "M12 11v6", fills = arrayOf(circle(12f, 7.4f, 1.1f)))
    val globe = ic("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0 -20", "M2 12h20", "M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1 -4 10 15.3 15.3 0 0 1 -4 -10 15.3 15.3 0 0 1 4 -10")
}
