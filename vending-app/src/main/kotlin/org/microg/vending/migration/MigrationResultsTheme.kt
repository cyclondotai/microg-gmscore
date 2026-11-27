/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.android.vending.R

/**
 * Transferred apps (CYC-3525) wears Transfer Phone v3's Cyclon OS look (cyclon: android/android-switch/companion
 * TransferTheme.kt), so "App installs" on Transfer Phone's result screen opens into the same app. Dark: black ground,
 * white ink, charcoal cards with a top-lit edge. Light: warm paper with white cards. Ember is only ever light that moves.
 */
internal class ResultsColors(
    val dark: Boolean,
    val ground: Color, val ink: Color, val ink2: Color, val mut: Color,
    val cardTop: Color, val cardBottom: Color, val cardFill: Color, val cardEdgeTop: Color, val cardEdge: Color, val cardShadow: Color?,
    val well: Color, val well2: Color, val hair: Color,
    val ember: Color, val ok: Color, val okInk: Color, val okBg: Color,
    val warn: Color, val warnInk: Color, val warnBg: Color, val warnLine: Color,
    val err: Color, val pri: Color, val priInk: Color,
)

private fun ink(alpha: Float, rgb: Long) = Color(rgb).copy(alpha = alpha)

internal val DarkResults = ResultsColors(
    dark = true,
    ground = Color(0xFF000000), ink = Color(0xFFFFFFFF), ink2 = Color(0xFFC2C2C4), mut = Color(0xFF8F8F93),
    cardTop = Color(0xFF1D1D20), cardBottom = Color(0xFF141416), cardFill = Color(0xFF17171A),
    cardEdgeTop = ink(.13f, 0xFFFFFFFF), cardEdge = ink(.045f, 0xFFFFFFFF), cardShadow = null,
    well = ink(.075f, 0xFFFFFFFF), well2 = ink(.14f, 0xFFFFFFFF), hair = ink(.08f, 0xFFFFFFFF),
    ember = Color(0xFFFF7A59), ok = Color(0xFF3DDC97), okInk = Color(0xFF6EE7B0), okBg = ink(.12f, 0xFF3DDC97),
    warn = Color(0xFFFFC24D), warnInk = Color(0xFFFFD27A), warnBg = ink(.11f, 0xFFFFC24D), warnLine = ink(.42f, 0xFFFFC24D),
    err = Color(0xFFFF6B61), pri = Color(0xFFFFFFFF), priInk = Color(0xFF000000),
)

internal val LightResults = ResultsColors(
    dark = false,
    ground = Color(0xFFFAF9F6), ink = Color(0xFF181818), ink2 = Color(0xFF47443F), mut = Color(0xFF6D6862),
    cardTop = Color(0xFFFFFFFF), cardBottom = Color(0xFFFFFFFF), cardFill = Color(0xFFFFFFFF),
    cardEdgeTop = ink(.06f, 0xFF181818), cardEdge = ink(.06f, 0xFF181818), cardShadow = ink(.32f, 0xFF181818),
    well = Color(0xFFF2F0EB), well2 = Color(0xFFE8E5DF), hair = ink(.08f, 0xFF181818),
    ember = Color(0xFFFF7A59), ok = Color(0xFF1E9E6E), okInk = Color(0xFF0E6B4F), okBg = Color(0xFFE6F4EE),
    warn = Color(0xFFE08B14), warnInk = Color(0xFF7A4A00), warnBg = Color(0xFFFFF5E2), warnLine = ink(.42f, 0xFFE08B14),
    err = Color(0xFFC9302C), pri = Color(0xFF181818), priInk = Color(0xFFFFFFFF),
)

internal val LocalResults = staticCompositionLocalOf { DarkResults }

/** Robolectric host tests: no font file to shape with and no frame clock worth driving. */
private val hostTest: Boolean get() = Build.FINGERPRINT == "robolectric"

/** Geist (res/font/transfer_geist.ttf: Transfer Phone's variable font, OFL, res/raw/ofl_geist.txt). */
@OptIn(ExperimentalTextApi::class)
internal val Geist: FontFamily by lazy {
    if (hostTest) FontFamily.Default else FontFamily(
        Font(R.font.transfer_geist, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.transfer_geist, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.transfer_geist, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    )
}

/** Transfer Phone's type scale (title 31 / 1.12 at -0.034 em, body 16.5, nothing under 13.5), plus the list's sizes. */
internal object ResultsType {
    private fun style(size: Float, line: Float, weight: Int, spacing: Float = 0f) = TextStyle(
        fontFamily = Geist, fontSize = size.sp, lineHeight = (size * line).sp, fontWeight = FontWeight(weight), letterSpacing = spacing.em,
    )
    val title = style(31f, 1.12f, 600, -0.034f)
    val body = style(16.5f, 1.45f, 400)
    val headline = style(24f, 1.15f, 600, -0.026f)
    val question = style(15f, 1.3f, 500)
    val rowTitle = style(17.5f, 1.25f, 600, -0.014f)
    val rowSub = style(14f, 1.35f, 400)
    val appTitle = style(16f, 1.25f, 600, -0.012f)
    val small = style(13.5f, 1.42f, 400)
    val label = style(15.5f, 1.2f, 600, -0.008f)
    val button = style(16.5f, 1f, 600, -0.008f)
    val pill = style(14f, 1f, 600, -0.004f)
    val chip = style(13.5f, 1.2f, 500)
    val badge = style(13.5f, 1f, 600)
    val notice = style(14f, 1.4f, 400)
    val monogram = style(19f, 1f, 600, -0.02f)
}

/**
 * Milliseconds since the screen's motion started; -1 while motion is off (the phone's animator duration scale is 0, or a
 * host test), where every loop draws its still frame instead.
 */
@Composable
internal fun rememberMotionClock(): State<Long> {
    val context = LocalContext.current
    fun readScaleOn(): Boolean = !hostTest && runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }.getOrDefault(true)
    var on by remember(context) { mutableStateOf(readScaleOn()) }
    DisposableEffect(context) {
        if (hostTest) return@DisposableEffect onDispose {}
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { on = readScaleOn() }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val registered = uri != null && runCatching {
            context.contentResolver.registerContentObserver(uri, false, observer)
            true
        }.getOrDefault(false)
        on = readScaleOn()
        onDispose { if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }
    val time = remember { mutableLongStateOf(-1L) }
    LaunchedEffect(on) {
        if (!on) {
            time.longValue = -1L
            return@LaunchedEffect
        }
        val start = withFrameMillis { it }
        while (true) withFrameMillis { time.longValue = it - start }
    }
    return time
}

private val IN_OUT = CubicBezierEasing(.45f, 0f, .55f, 1f)

/** Where [t] is in a loop of [period] ms that starts [delay] ms late, from 0 to 1. */
private fun phase(t: Long, period: Long, delay: Long = 0L): Float = (((t - delay) % period + period) % period).toFloat() / period

private fun keyframes(p: Float, vararg stops: Pair<Float, Float>): Float {
    for (i in 0 until stops.size - 1) {
        val (p0, v0) = stops[i]
        val (p1, v1) = stops[i + 1]
        if (p <= p1) {
            val f = if (p1 > p0) ((p - p0) / (p1 - p0)).coerceIn(0f, 1f) else 1f
            return v0 + (v1 - v0) * IN_OUT.transform(f)
        }
    }
    return stops.last().second
}

/** Apps still installing on the meter: a slow breath (Transfer Phone's breathe). */
internal fun breathe(t: Long): Float = if (t < 0) .45f else keyframes(phase(t, 1600), 0f to .28f, .5f to .62f, 1f to .28f)

/** One of three waiting dots: its alpha and scale (Transfer Phone's dot). */
internal fun dot(t: Long, delay: Long): Pair<Float, Float> {
    if (t < 0) return 1f to 1f
    val p = phase(t, 1500, delay)
    return keyframes(p, 0f to .25f, .35f to 1f, .7f to .25f, 1f to .25f) to keyframes(p, 0f to .8f, .35f to 1.15f, .7f to .8f, 1f to .8f)
}
