/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.withResumed
import androidx.lifecycle.withStarted
import coil.compose.AsyncImage
import com.android.vending.R
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owner-visible receipts; this Activity never accepts an app list from an external caller (an intent may only name one
 * of the queue's own transfers).
 *
 * Two loops feed the screen (CYC-3525). Every open, change broadcast and return to the screen first reads the receipt as
 * saved ([MigrationInstallQueue.cachedSummary]: preferences only), so apps appear at once. Aurora's live check
 * ([MigrationInstallQueue.summary], one call per open app) runs beside it, one pass at a time: a burst of changes queues
 * one more pass instead of cancelling the pass in flight, which used to leave the list empty behind "No apps were
 * selected" while every change restarted it.
 */
class MigrationResultsActivity : ComponentActivity() {
    /** The transfer the opening intent or the owner asked for; null opens [MigrationInstallQueue.resultsSession]'s pick. */
    private val target = mutableStateOf<String?>(null)
    /** Saved-receipt reads: conflated, so a burst of changes is one read. */
    private val snapshots = Channel<Unit>(Channel.CONFLATED)
    /** Aurora checks: conflated, one at a time, never cancelled by a newer change. */
    private val checks = Channel<Unit>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target.value = savedInstanceState?.getString(STATE_TARGET) ?: intent.getStringExtra("session_id")
        drawBehindBars()
        refresh()
        setContent { ResultsTheme { Results() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A notification or Transfer Phone opened the results again: show the transfer it names (or the default pick).
        setIntent(intent)
        target.value = intent.getStringExtra("session_id")
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TARGET, target.value)
    }

    private fun refresh() {
        snapshots.trySend(Unit)
        checks.trySend(Unit)
    }

    /** Edge to edge on the ground colour, with bar icons readable on it: no white flash in dark mode. */
    @Suppress("DEPRECATION")
    private fun drawBehindBars() {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val ground = (if (dark) DarkResults else LightResults).ground.toArgb()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(ground))
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    @Composable
    private fun Results() {
        // Opened on first use, inside the IO blocks below: a legacy upgrade takes the install lock.
        val queue by remember { lazy { MigrationInstallQueue(applicationContext) } }
        val scope = rememberCoroutineScope()
        var sessionId by remember { mutableStateOf<String?>(null) }
        var choices by remember { mutableStateOf(emptyList<Pair<String, Int>>()) }
        var results by remember { mutableStateOf(emptyList<Bundle>()) }
        var labels by remember { mutableStateOf(emptyMap<String, String>()) }
        // Nothing is said about the transfer's apps until its saved receipt has been read once.
        var loading by remember { mutableStateOf(true) }
        var retryable by remember { mutableStateOf(emptySet<String>()) }
        var complete by remember { mutableStateOf(false) }
        var selectionClosed by remember { mutableStateOf(false) }
        var activeWork by remember { mutableStateOf(false) }
        // The apps an owner Retry is still working on, and what to tell them: a Retry that failed again in under a
        // second used to leave the screen exactly as it was (CYC-968).
        var retrying by remember { mutableStateOf(emptySet<String>()) }
        var retryNote by remember { mutableStateOf<String?>(null) }
        // Retries tapped but not recorded yet (queue.retry can take a while): no outcome is reported before they are.
        var submitting by remember { mutableStateOf(0) }
        // Advanced by every Retry tap and registration: a read that started before one only reports an outcome if none
        // happened since (a newer read does not cancel one already reading).
        var retryGeneration by remember { mutableStateOf(0) }
        // Retry feedback belongs to the transfer it was started in; another transfer starts without it.
        LaunchedEffect(sessionId) { retrying = emptySet(); retryNote = null }

        fun apply(id: String, receipt: Bundle, retry: Set<String>, readAt: Int) {
            if (sessionId != id) return
            @Suppress("DEPRECATION")
            results = receipt.getParcelableArray("cyclon_app_results")?.map { it as Bundle }.orEmpty()
            complete = receipt.getBoolean("complete")
            selectionClosed = receipt.getBoolean("selection_closed")
            activeWork = receipt.getBoolean("has_active_work")
            retryable = retry
            if (retrying.isNotEmpty() && submitting == 0 && retryGeneration == readAt) {
                MigrationResultText.retryOutcome(results.filter { it.getString("package_name") in retrying })
                    ?.let { retryNote = it; retrying = emptySet() }
            }
        }

        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { refresh() }
            }
            registerReceiver(receiver, IntentFilter(MigrationInstallQueue.ACTION_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            // Back from App Store or an opened app: read again.
            val resumed = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
            lifecycle.addObserver(resumed)
            onDispose {
                lifecycle.removeObserver(resumed)
                unregisterReceiver(receiver)
            }
        }
        // The saved receipt: preferences only, so it never waits for Aurora or for an install in flight.
        LaunchedEffect(Unit) {
            for (signal in snapshots) {
                val want = target.value
                val readAt = retryGeneration
                val known = labels
                val read = withContext(Dispatchers.IO) {
                    val id = queue.resultsSession(want)
                    val visible = queue.visibleSessions(id).map { it to queue.appCount(it) }
                    val receipt = id?.let { queue.cachedSummary(it) }
                    val retry = id?.let { queue.retryDocuments(it).mapNotNull { doc -> doc.getString("package_name") }.toSet() }.orEmpty()
                    @Suppress("DEPRECATION")
                    val rows = receipt?.getParcelableArray("cyclon_app_results")?.map { it as Bundle }.orEmpty()
                    SnapshotRead(id, visible, receipt, retry, installedLabels(packageManager, rows, known))
                }
                // A newer request (the picker, a new intent) has its own read queued.
                if (target.value != want) continue
                if (sessionId != read.id) {
                    sessionId = read.id
                    checks.trySend(Unit)
                }
                choices = read.choices
                if (read.labels.isNotEmpty()) labels = labels + read.labels
                val id = read.id
                if (id != null && read.receipt != null) apply(id, read.receipt, read.retry, readAt)
                else { results = emptyList(); retryable = emptySet(); complete = false; selectionClosed = true; activeWork = false }
                loading = false
            }
        }
        // Aurora's live check, one pass at a time, only while the screen is visible.
        LaunchedEffect(Unit) {
            for (signal in checks) {
                lifecycle.withStarted {}
                val id = sessionId ?: continue
                val readAt = retryGeneration
                val checked = withContext(Dispatchers.IO) {
                    runCatching {
                        queue.summary(id) to queue.retryDocuments(id).mapNotNull { it.getString("package_name") }.toSet()
                    }.getOrNull()
                }
                if (checked != null) apply(id, checked.first, checked.second, readAt)
            }
        }
        // Aurora does not announce download progress: while apps are still installing, check again now and then.
        LaunchedEffect(activeWork) {
            while (activeWork) {
                delay(LIVE_CHECK_MS)
                lifecycle.withResumed {}
                checks.trySend(Unit)
            }
        }

        fun retry(packageName: String? = null) {
            val id = sessionId ?: return
            val names = packageName?.let(::setOf) ?: retryable
            retryNote = "Trying again…"
            submitting++
            retryGeneration++
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { queue.retry(id, packageName) }
                    // Only now: a read before the retry is recorded would report the old failure as its outcome.
                    if (sessionId == id) retrying = retrying + names
                    retryGeneration++
                } catch (e: Exception) {
                    if (sessionId == id) retryNote = "Couldn't start the retry. Try again."
                } finally {
                    submitting--
                    refresh()
                }
            }
        }

        val groups = remember(results, retryable, labels) {
            MigrationResultGroups.group(results, retryable) { MigrationResultGroups.label(it, labels) }
        }
        ResultsScreen(
            loading = loading,
            groups = groups,
            labels = labels,
            choices = choices,
            sessionId = sessionId,
            complete = complete,
            selectionClosed = selectionClosed,
            retryable = retryable,
            retryBusy = retrying.isNotEmpty() || submitting > 0,
            retryNote = retryNote,
            actions = ResultsActions(
                back = { finish() },
                choose = { id -> target.value = id; snapshots.trySend(Unit) },
                retry = ::retry,
                appStore = { name ->
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$name")).setPackage("com.aurora.store")) }
                },
                open = { name -> packageManager.getLaunchIntentForPackage(name)?.let { runCatching { startActivity(it) } } },
                finishSelection = {
                    val id = sessionId
                    if (id != null) scope.launch { withContext(Dispatchers.IO) { queue.finishSelection(id) }; refresh() }
                },
                done = { finish() },
            ),
        )
    }

    private class SnapshotRead(val id: String?, val choices: List<Pair<String, Int>>, val receipt: Bundle?, val retry: Set<String>,
                               val labels: Map<String, String>)

    private companion object {
        const val STATE_TARGET = "target_session"
        const val LIVE_CHECK_MS = 6_000L

        /** Launcher labels for listed apps whose store title is missing or only a package name. */
        fun installedLabels(pm: PackageManager, rows: List<Bundle>, known: Map<String, String>): Map<String, String> {
            val found = HashMap<String, String>()
            for (row in rows) {
                val name = row.getString("package_name") ?: continue
                if (name in known || MigrationResultGroups.realTitle(row.getString("title"), name) != null) continue
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString().trim() }.getOrNull()
                if (!label.isNullOrEmpty() && label != name) found[name] = label
            }
            return found
        }
    }
}

// ---------------------------------------------------------------- the screen

/** What the screen asks the activity to do; the activity owns the queue and the retry bookkeeping. */
internal class ResultsActions(
    val back: () -> Unit,
    val choose: (String) -> Unit,
    val retry: (String?) -> Unit,
    val appStore: (String) -> Unit,
    val open: (String) -> Unit,
    val finishSelection: () -> Unit,
    val done: () -> Unit,
)

@Composable
internal fun ResultsTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) DarkResults else LightResults
    val scheme = if (c.dark) darkColorScheme(surface = c.cardTop, onSurface = c.ink, background = c.ground, onBackground = c.ink)
        else lightColorScheme(surface = c.cardTop, onSurface = c.ink, background = c.ground, onBackground = c.ink)
    CompositionLocalProvider(LocalResults provides c) {
        MaterialTheme(colorScheme = scheme, shapes = Shapes(extraSmall = RoundedCornerShape(18.dp)), content = content)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ResultsScreen(
    loading: Boolean,
    groups: Map<MigrationResultGroups.Section, List<Bundle>>,
    labels: Map<String, String>,
    choices: List<Pair<String, Int>>,
    sessionId: String?,
    complete: Boolean,
    selectionClosed: Boolean,
    retryable: Set<String>,
    retryBusy: Boolean,
    retryNote: String?,
    actions: ResultsActions,
) {
    val c = LocalResults.current
    val counts = MigrationResultGroups.counts(groups)
    // Unavailable apps stay folded away unless they are all there is.
    var notOnPlayOpen by remember(sessionId) { mutableStateOf<Boolean?>(null) }
    val othersShown = counts.total > 0
    val showNotOnPlay = notOnPlayOpen ?: !othersShown
    Column(Modifier.fillMaxSize().background(c.ground).safeDrawingPadding()) {
        TopBar(choices, sessionId, actions)
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 28.dp)) {
                item("title", contentType = "title") {
                    Column {
                        Text("Transferred apps", Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp).semantics { heading() },
                            style = ResultsType.title, color = c.ink)
                        Text(if (complete) "App transfer is ready to review. Check the results below."
                            else "Your data transfer can finish while App Store installs your apps. Results update as each app finishes.",
                            Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp), style = ResultsType.body, color = c.ink2)
                    }
                }
                item("hero", contentType = "hero") {
                    Hero(loading, counts, groups, selectionClosed || sessionId == null, retryable.isNotEmpty(), retryBusy, retryNote, actions)
                }
                if (!loading) {
                    section(MigrationResultGroups.Section.ATTENTION, "Needs attention", groups, labels, retryable, retryBusy, actions)
                    section(MigrationResultGroups.Section.INSTALLING, "Installing", groups, labels, retryable, retryBusy, actions)
                    section(MigrationResultGroups.Section.INSTALLED, "Installed", groups, labels, retryable, retryBusy, actions)
                    val unavailable = groups[MigrationResultGroups.Section.NOT_ON_PLAY].orEmpty()
                    if (unavailable.isNotEmpty()) {
                        item("h:not_on_play", contentType = "fold") {
                            Fold(unavailable.size, showNotOnPlay, Modifier.animateItemPlacement()) { notOnPlayOpen = !showNotOnPlay }
                        }
                        if (showNotOnPlay) itemsIndexed(unavailable, key = { _, row -> "app:" + row.getString("package_name") },
                            contentType = { _, _ -> "app" }) { index, row ->
                            AppRow(row, MigrationResultGroups.Section.NOT_ON_PLAY, labels, retryable, retryBusy, actions,
                                first = false, last = index == unavailable.lastIndex)
                        }
                    }
                }
            }
            // A short fade separates the scrolled list from the bar.
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                .background(Brush.verticalGradient(listOf(c.ground.copy(alpha = 0f), c.ground))))
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!loading && !selectionClosed && sessionId != null) {
                PrimaryButton("Finish app selection", Modifier.weight(1f), tonal = true, onClick = actions.finishSelection)
            } else {
                Spacer(Modifier.weight(1f))
            }
            PrimaryButton("Done", onClick = actions.done)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.section(
    section: MigrationResultGroups.Section,
    title: String,
    groups: Map<MigrationResultGroups.Section, List<Bundle>>,
    labels: Map<String, String>,
    retryable: Set<String>,
    retryBusy: Boolean,
    actions: ResultsActions,
) {
    val rows = groups[section].orEmpty()
    if (rows.isEmpty()) return
    item("h:${section.name}", contentType = "header") { SectionHeader(title, rows.size, section, Modifier.animateItemPlacement()) }
    itemsIndexed(rows, key = { _, row -> "app:" + row.getString("package_name") }, contentType = { _, _ -> "app" }) { index, row ->
        AppRow(row, section, labels, retryable, retryBusy, actions, first = index == 0, last = index == rows.lastIndex)
    }
}

// ---------------------------------------------------------------- building blocks

/** Press feedback: a spring to 97 % instead of a ripple (Transfer Phone's press). */
@Composable
private fun Modifier.press(enabled: Boolean, role: Role = Role.Button, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) .972f else 1f, spring(dampingRatio = .6f), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }.clickable(source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

/** Dark: a charcoal gradient with a top-lit edge. Light: a white card with a soft stacked shadow. */
@Composable
private fun Modifier.card(shape: Shape): Modifier {
    val c = LocalResults.current
    return (c.cardShadow?.let { shadow(10.dp, shape, ambientColor = it, spotColor = it) } ?: this)
        .background(Brush.verticalGradient(listOf(c.cardTop, c.cardBottom)), shape)
        .border(1.dp, Brush.verticalGradient(0f to c.cardEdgeTop, .35f to c.cardEdge), shape)
}

/**
 * One row of a card group that the list draws row by row: the group's outer edge, its rounded ends on the first and last
 * rows, and a hairline between rows (inset past the icon). Only the group's top is lit.
 */
@Composable
private fun Modifier.slice(first: Boolean, last: Boolean, dividerInset: Dp = 72.dp): Modifier {
    val c = LocalResults.current
    return drawBehind {
        val radius = 22.dp.toPx()
        val stroke = 1.dp.toPx()
        val reach = radius + stroke * 2
        val top = if (first) 0f else -reach
        val bottom = if (last) size.height else size.height + reach
        clipRect {
            val fill = if (first && c.dark) Brush.verticalGradient(listOf(c.cardTop, c.cardFill), startY = 0f, endY = size.height)
                else SolidColor(c.cardFill)
            drawRoundRect(fill, Offset(0f, top), Size(size.width, bottom - top), CornerRadius(radius))
            val edge = if (first) Brush.verticalGradient(0f to c.cardEdgeTop, 1f to c.cardEdge, startY = 0f, endY = 36.dp.toPx())
                else SolidColor(c.cardEdge)
            drawRoundRect(edge, Offset(stroke / 2, top + stroke / 2), Size(size.width - stroke, bottom - top - stroke),
                CornerRadius(radius - stroke / 2), style = Stroke(stroke))
        }
        if (!first) drawLine(c.hair, Offset(dividerInset.toPx(), 0f), Offset(size.width - 16.dp.toPx(), 0f), stroke)
    }
}

@Composable
private fun Glyph(@DrawableRes icon: Int, size: Dp, tint: Color, modifier: Modifier = Modifier) =
    Icon(painterResource(icon), null, modifier.size(size), tint = tint)

@Composable
private fun PrimaryButton(label: String, modifier: Modifier = Modifier, tonal: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalResults.current
    val shape = RoundedCornerShape(28.dp)
    Box(modifier.height(56.dp).graphicsLayer { alpha = if (enabled) 1f else .3f }
        .then(if (tonal || c.dark) Modifier else Modifier.shadow(10.dp, shape, ambientColor = c.pri, spotColor = c.pri))
        .clip(shape).background(if (tonal) c.well2 else c.pri).press(enabled, onClick = onClick)
        .padding(horizontal = if (tonal) 22.dp else 34.dp), contentAlignment = Alignment.Center) {
        Text(label, style = ResultsType.button, color = if (tonal) c.ink else c.priInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small tonal pill on a row: Open, Retry, App Store. 40 dp tall inside a 48 dp touch target. */
@Composable
private fun Pill(label: String, enabled: Boolean = true, @DrawableRes icon: Int? = null, strong: Boolean = false, onClick: () -> Unit) {
    val c = LocalResults.current
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.heightIn(min = 48.dp).press(enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(Modifier.height(36.dp).graphicsLayer { alpha = if (enabled) 1f else .35f }.clip(shape)
            .background(if (strong) c.pri else c.well2).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Glyph(icon, 15.dp, if (strong) c.priInk else c.ink)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = ResultsType.pill, color = if (strong) c.priInk else c.ink, maxLines = 1)
        }
    }
}

/** Three ember dots instead of a spinner (Transfer Phone's WaitingDots). */
@Composable
private fun WaitingDots(modifier: Modifier = Modifier) {
    val c = LocalResults.current
    val clock = rememberMotionClock()
    androidx.compose.foundation.Canvas(modifier.size(28.dp, 8.dp)) {
        val t = clock.value
        for (i in 0 until 3) {
            val (alpha, scale) = dot(t, i * 200L)
            drawCircle(c.ember, 3.dp.toPx() * scale, Offset(4.dp.toPx() + i * 11.dp.toPx(), size.height / 2), alpha = alpha)
        }
    }
}

@Composable
private fun Chip(label: String, count: Int, dot: Color?) {
    val c = LocalResults.current
    Row(Modifier.heightIn(min = 30.dp).clip(RoundedCornerShape(15.dp)).background(c.well).padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot ?: c.mut))
        Spacer(Modifier.width(8.dp))
        Text(buildAnnotatedString {
            append("$label ")
            withStyle(SpanStyle(fontWeight = ResultsType.badge.fontWeight, color = c.ink)) { append(count.toString()) }
        }, style = ResultsType.chip, color = c.ink2)
    }
}

// ---------------------------------------------------------------- header, hero, sections

@Composable
private fun TopBar(choices: List<Pair<String, Int>>, sessionId: String?, actions: ResultsActions) {
    val c = LocalResults.current
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 4.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).press(true, onClick = actions.back).semantics { contentDescription = "Back" }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.well), contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_migration_back, 20.dp, c.ink)
            }
        }
        Spacer(Modifier.weight(1f))
        if (choices.size > 1) TransferPicker(choices, sessionId, actions.choose)
    }
}

private fun transferName(index: Int, count: Int) = if (index <= 0) "Latest transfer" else "Earlier transfer ${count - index}"

private fun appCount(n: Int) = if (n == 1) "1 app" else "$n apps"

/** Which transfer the list shows; only offered when more than one has apps. */
@Composable
private fun TransferPicker(choices: List<Pair<String, Int>>, sessionId: String?, choose: (String) -> Unit) {
    val c = LocalResults.current
    var open by remember { mutableStateOf(false) }
    val index = choices.indexOfFirst { it.first == sessionId }
    val turned by animateFloatAsState(if (open) 180f else 0f, label = "picker")
    Box {
        Box(Modifier.heightIn(min = 48.dp).press(true) { open = true }, contentAlignment = Alignment.Center) {
            Row(Modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(c.well).padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(transferName(index, choices.size), style = ResultsType.chip, color = c.ink)
                Spacer(Modifier.width(4.dp))
                Glyph(R.drawable.ic_migration_expand, 18.dp, c.ink2, Modifier.rotate(turned))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEachIndexed { i, (id, apps) ->
                DropdownMenuItem(
                    text = {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(transferName(i, choices.size), style = ResultsType.label, color = c.ink)
                            Text(appCount(apps), style = ResultsType.small, color = c.mut)
                        }
                    },
                    trailingIcon = if (id == sessionId) {{ Box(Modifier.size(8.dp).clip(CircleShape).background(c.ink)) }} else null,
                    onClick = { open = false; if (id != sessionId) choose(id) },
                )
            }
        }
    }
}

/** The count, the meter and the store's warnings, as Transfer Phone's App installs card shows them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(
    loading: Boolean,
    counts: MigrationResultGroups.Counts,
    groups: Map<MigrationResultGroups.Section, List<Bundle>>,
    selectionClosed: Boolean,
    canRetry: Boolean,
    retryBusy: Boolean,
    retryNote: String?,
    actions: ResultsActions,
) {
    val c = LocalResults.current
    val shape = RoundedCornerShape(26.dp)
    Column(Modifier.padding(top = 18.dp).fillMaxWidth().card(shape).clip(shape).animateContentSize().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(MigrationResultGroups.headline(counts, loading), Modifier.weight(1f, fill = false).semantics { heading() },
                style = ResultsType.headline, color = c.ink)
            if (loading) {
                Spacer(Modifier.width(12.dp))
                WaitingDots()
            }
        }
        MigrationResultGroups.subline(counts, loading)?.let {
            Text(it, Modifier.padding(top = 6.dp), style = ResultsType.rowSub, color = c.mut)
        }
        if (loading) LoadingTrack()
        else if (counts.total > 0) {
            Row(Modifier.padding(top = 16.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.well)
                .semantics { stateDescription = "${counts.installed} of ${counts.total} installed" },
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (counts.installed > 0) Box(Modifier.weight(counts.installed.toFloat()).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c.ok))
                if (counts.installing > 0) Breathing(Modifier.weight(counts.installing.toFloat()).fillMaxHeight().clip(RoundedCornerShape(3.dp)), c.ink)
                if (counts.attention > 0) Box(Modifier.weight(counts.attention.toFloat()).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c.warn))
            }
        }
        if (!loading) {
            val reasons = MigrationResultGroups.reasons(groups)
            val chips = listOfNotNull(
                Triple("Installing now", counts.installing, c.ink.copy(alpha = .55f)).takeIf { counts.installing > 0 },
                Triple("Large apps skipped to save storage", reasons.storage, c.warn).takeIf { reasons.storage > 0 },
                Triple("Waiting for you", reasons.owner, c.warn).takeIf { reasons.owner > 0 },
                Triple("Couldn’t install", reasons.other, c.warn).takeIf { reasons.other > 0 },
                Triple("Not on Google Play", counts.notOnPlay, null).takeIf { counts.notOnPlay > 0 },
            )
            if (chips.isNotEmpty()) FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, count, dot) in chips) Chip(label, count, dot)
            }
            if (canRetry) Row(Modifier.padding(top = 14.dp)) {
                PrimaryButton("Retry failed apps", tonal = true, enabled = !retryBusy) { actions.retry(null) }
            }
        }
        retryNote?.let { RetryNote(it, retryBusy) }
        if (!loading && !selectionClosed) {
            Row(Modifier.padding(top = 16.dp)) {
                Box(Modifier.padding(top = 6.dp).size(6.dp).clip(CircleShape).background(c.mut))
                Spacer(Modifier.width(10.dp))
                Text("When you have finished choosing apps to copy, tap Finish app selection.", style = ResultsType.small, color = c.mut)
            }
        }
    }
}

/** The meter's installing share: a slow breath, its own motion clock only while it is drawn. */
@Composable
private fun Breathing(modifier: Modifier, color: Color) {
    val clock = rememberMotionClock()
    Box(modifier.graphicsLayer { alpha = breathe(clock.value) }.background(color))
}

/** The empty meter while the saved receipt is read (a few frames at most). */
@Composable
private fun LoadingTrack() {
    val c = LocalResults.current
    Breathing(Modifier.padding(top = 16.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), c.well2)
}

/** What an owner Retry came to (CYC-968): dots while it is still being tried, then the outcome. */
@Composable
private fun RetryNote(note: String, busy: Boolean) {
    val c = LocalResults.current
    val worked = note.startsWith("Retry worked")
    val failed = note.contains("couldn't", ignoreCase = true)
    Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(when { worked -> c.okBg; failed -> c.warnBg; else -> c.well })
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (busy) WaitingDots() else Box(Modifier.size(8.dp).clip(CircleShape).background(when { worked -> c.ok; failed -> c.warn; else -> c.mut }))
        Spacer(Modifier.width(12.dp))
        Text(note, style = ResultsType.notice, color = when { worked -> c.okInk; failed -> c.warnInk; else -> c.ink2 })
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, section: MigrationResultGroups.Section, modifier: Modifier = Modifier) {
    val c = LocalResults.current
    val (bg, fg) = when (section) {
        MigrationResultGroups.Section.INSTALLED -> c.okBg to c.okInk
        MigrationResultGroups.Section.ATTENTION -> c.warnBg to c.warnInk
        else -> c.well to c.ink2
    }
    Row(modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 28.dp, bottom = 10.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = ResultsType.label, color = c.ink)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.heightIn(min = 24.dp).clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
            Text(count.toString(), style = ResultsType.badge, color = fg)
        }
    }
}

/** "Not on Google Play": a quiet card that opens into the apps the store does not list. */
@Composable
private fun Fold(count: Int, open: Boolean, modifier: Modifier = Modifier, toggle: () -> Unit) {
    val c = LocalResults.current
    val turned by animateFloatAsState(if (open) 180f else 0f, label = "fold")
    Column(modifier.padding(top = 28.dp)) {
        Row(Modifier.fillMaxWidth().slice(first = true, last = !open).heightIn(min = 64.dp).press(true, onClick = toggle)
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Not on Google Play", style = ResultsType.label, color = c.ink)
                Text(if (count == 1) "1 app App Store couldn’t copy" else "$count apps App Store couldn’t copy",
                    Modifier.padding(top = 3.dp), style = ResultsType.small, color = c.mut)
            }
            Box(Modifier.size(34.dp).clip(CircleShape).background(c.well), contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_migration_expand, 18.dp, c.ink2, Modifier.rotate(turned))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LazyItemScope.AppRow(
    row: Bundle,
    section: MigrationResultGroups.Section,
    labels: Map<String, String>,
    retryable: Set<String>,
    retryBusy: Boolean,
    actions: ResultsActions,
    first: Boolean,
    last: Boolean,
) {
    val c = LocalResults.current
    val name = row.getString("package_name").orEmpty()
    val label = MigrationResultGroups.label(row, labels)
    val shown = MigrationResultGroups.normalize(row)
    val assets = rememberAppAssets(name, row.getBoolean("installed"))
    Column(Modifier.animateItemPlacement().fillMaxWidth().slice(first, last)
        .padding(start = 14.dp, end = 10.dp, top = if (first) 14.dp else 12.dp, bottom = if (last) 14.dp else 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(assets, row.getString("icon_url"), label, installed = section == MigrationResultGroups.Section.INSTALLED)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = ResultsType.appTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (section) {
                    MigrationResultGroups.Section.INSTALLED -> row.getString("developer_name")?.trim()
                        ?.takeIf { it.isNotEmpty() && !it.equals(label, ignoreCase = true) }
                    else -> MigrationResultText.description(shown)
                }
                if (sub != null) Text(sub, Modifier.padding(top = 2.dp), style = ResultsType.rowSub,
                    color = if (section == MigrationResultGroups.Section.ATTENTION) c.warnInk else c.mut,
                    maxLines = if (section == MigrationResultGroups.Section.INSTALLED) 1 else 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            when (section) {
                MigrationResultGroups.Section.INSTALLED -> if (assets.launchable) Pill("Open") { actions.open(name) }
                MigrationResultGroups.Section.INSTALLING -> Box(Modifier.padding(end = 6.dp).size(48.dp, 48.dp), contentAlignment = Alignment.Center) {
                    if (MigrationResultGroups.active(shown)) WaitingDots() else Box(Modifier.size(8.dp).clip(CircleShape).background(c.mut.copy(alpha = .6f)))
                }
                else -> {}
            }
        }
        val canRetry = name in retryable
        val store = section == MigrationResultGroups.Section.ATTENTION ||
            (section == MigrationResultGroups.Section.NOT_ON_PLAY && MigrationResultGroups.listedSomewhere(row))
        if (canRetry || store) Row(Modifier.padding(start = 58.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canRetry) Pill("Retry", enabled = !retryBusy, icon = R.drawable.ic_migration_retry) { actions.retry(name) }
            if (store) Pill("App Store") { actions.appStore(name) }
        }
    }
}

// ---------------------------------------------------------------- app icons

/** An installed app's own launcher icon (drawn into a rounded square), and whether it can be opened. */
internal class AppAssets(val icon: ImageBitmap?, val fullBleed: Boolean, val launchable: Boolean)

private val NO_ASSETS = AppAssets(null, fullBleed = false, launchable = false)

/** Icons drawn for the rows on screen, about 6 MB: a 300-app transfer scrolls without loading every icon again. */
private object AppIconCache {
    val icons = object : LruCache<String, AppAssets>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: AppAssets) = value.icon?.let { it.width * it.height * 4 } ?: 64
    }

    fun load(pm: PackageManager, name: String, px: Int): AppAssets {
        val drawable = try { pm.getApplicationIcon(name) } catch (_: PackageManager.NameNotFoundException) { return NO_ASSETS }
        catch (_: Exception) { return NO_ASSETS }
        val launchable = runCatching { pm.getLaunchIntentForPackage(name) != null }.getOrDefault(false)
        val (bitmap, fullBleed) = runCatching { render(drawable, px) }.getOrElse { return AppAssets(null, false, launchable) }
        return AppAssets(bitmap.asImageBitmap(), fullBleed, launchable)
    }

    /** Adaptive icons: both layers, edge to edge (the screen gives every icon the same rounded square). */
    private fun render(drawable: Drawable, px: Int): Pair<Bitmap, Boolean> {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (drawable is AdaptiveIconDrawable) {
            // The visible 72 of the layers' 108 units fill the square.
            val inset = px / 4
            for (layer in listOf(drawable.background, drawable.foreground)) {
                layer ?: continue
                layer.setBounds(-inset, -inset, px + inset, px + inset)
                layer.draw(canvas)
            }
            return bitmap to true
        }
        drawable.setBounds(0, 0, px, px)
        drawable.draw(canvas)
        return bitmap to false
    }
}

@Composable
private fun rememberAppAssets(name: String, installed: Boolean): AppAssets {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { 44.dp.roundToPx() }
    val key = "$name@$px"
    val assets by produceState(AppIconCache.icons.get(key) ?: NO_ASSETS, key, installed) {
        if (AppIconCache.icons.get(key) == null || installed && value.icon == null) {
            val loaded = withContext(Dispatchers.IO) { AppIconCache.load(context.packageManager, name, px) }
            if (loaded.icon != null) AppIconCache.icons.put(key, loaded)
            value = loaded
        }
    }
    return assets
}

@Composable
private fun AppIcon(assets: AppAssets, iconUrl: String?, label: String, installed: Boolean) {
    val c = LocalResults.current
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.size(44.dp)) {
        val icon = assets.icon
        when {
            icon != null -> Image(icon, null, Modifier.fillMaxSize().then(if (assets.fullBleed) Modifier.clip(shape) else Modifier))
            !iconUrl.isNullOrEmpty() -> {
                var loaded by remember(iconUrl) { mutableStateOf(false) }
                if (!loaded) Monogram(label)
                AsyncImage(model = iconUrl, contentDescription = null, modifier = Modifier.fillMaxSize().clip(shape),
                    contentScale = ContentScale.Crop, onSuccess = { loaded = true })
            }
            else -> Monogram(label)
        }
        if (installed) Box(Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp).size(18.dp).clip(CircleShape)
            .background(c.cardFill).padding(2.dp).clip(CircleShape).background(c.ok), contentAlignment = Alignment.Center) {
            Glyph(R.drawable.ic_migration_check, 10.dp, if (c.dark) Color.Black else Color.White)
        }
    }
}

/** A crisp neutral tile with the app's first letter, for apps with no icon to show. */
@Composable
private fun Monogram(label: String) {
    val c = LocalResults.current
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.well2), contentAlignment = Alignment.Center) {
        Text(MigrationResultGroups.initial(label), style = ResultsType.monogram, color = c.ink2)
    }
}

// ---------------------------------------------------------------- what goes where

/** How the screen sorts a transfer's rows (CYC-3525): sections, each in display-name order. */
internal object MigrationResultGroups {
    enum class Section { ATTENTION, INSTALLING, INSTALLED, NOT_ON_PLAY }

    data class Counts(val installed: Int, val installing: Int, val attention: Int, val notOnPlay: Int) {
        /** Apps the store is installing or tried to: not-on-Play apps are counted beside it, as on Transfer Phone. */
        val total get() = installed + installing + attention
    }

    data class Reasons(val storage: Int, val owner: Int, val other: Int)

    private val PACKAGE_NAME = Regex("[a-z][\\w]*(\\.[A-Za-z_][\\w]*)+")
    private val FAILED = setOf("failed", "cancelled", "canceled", "unavailable", "removed", "owner_action_required", "not_found", "paid")
    private val ACTIVE = setOf("purchasing", "downloading", "downloaded", "verifying", "completed", "installing", "restoring_data")
    private val STORAGE = setOf("insufficient_storage", "storage", "no_space", "package_installer_status_6")
    private val OWNER = setOf("installation_confirmation_required", "needs_confirmation", "user_action_required")
    private val UNLISTED = setOf("not_found", "app_removed", "app_not_supported")

    /** The store's title when it is a real name, not blank and not a package name. */
    fun realTitle(title: String?, packageName: String): String? =
        title?.trim()?.takeIf { it.isNotEmpty() && it != packageName && !PACKAGE_NAME.matches(it) }

    /** The name a row is shown and sorted by: the store's title, the installed app's own label, else the package. */
    fun label(row: Bundle, labels: Map<String, String>): String {
        val name = row.getString("package_name").orEmpty()
        return realTitle(row.getString("title"), name) ?: labels[name] ?: name
    }

    fun initial(label: String): String = label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "·"

    fun section(row: Bundle, retryable: Set<String>): Section {
        val name = row.getString("package_name").orEmpty()
        return when {
            row.getBoolean("installed") -> Section.INSTALLED
            row.getBoolean("excluded") -> if (row.getBoolean("recoverable") || name in retryable) Section.ATTENTION else Section.NOT_ON_PLAY
            !row.getBoolean("terminal") -> Section.INSTALLING
            else -> Section.ATTENTION
        }
    }

    /** An app still being worked on reads as waiting, not as the failure of the attempt before (an owner Retry). */
    fun normalize(row: Bundle): Bundle {
        if (row.getBoolean("terminal") || row.getBoolean("installed") || row.getBoolean("excluded") ||
            row.getString("status") !in FAILED) return row
        return Bundle(row).apply { putString("status", "pending"); remove("error"); remove("errorCode") }
    }

    /** Aurora is moving this app right now (the row's live dots), not only holding it in its queue. */
    fun active(row: Bundle): Boolean = row.getString("status") in ACTIVE

    /** An app left out for a reason App Store may still help with (paid, incompatible), not one it does not list. */
    fun listedSomewhere(row: Bundle): Boolean = (row.getString("error") ?: row.getString("errorCode")) !in UNLISTED &&
        row.getString("status") != "not_found"

    fun group(rows: List<Bundle>, retryable: Set<String>, label: (Bundle) -> String): Map<Section, List<Bundle>> {
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        val keyed = rows.map { Triple(it, label(it), it.getString("package_name").orEmpty()) }
        return keyed.groupBy { section(it.first, retryable) }.mapValues { (_, entries) ->
            entries.sortedWith { a, b -> collator.compare(a.second, b.second).takeIf { it != 0 } ?: a.third.compareTo(b.third) }.map { it.first }
        }
    }

    fun counts(groups: Map<Section, List<Bundle>>) = Counts(
        installed = groups[Section.INSTALLED].orEmpty().size,
        installing = groups[Section.INSTALLING].orEmpty().size,
        attention = groups[Section.ATTENTION].orEmpty().size,
        notOnPlay = groups[Section.NOT_ON_PLAY].orEmpty().size,
    )

    /** The attention rows by what they need, as Transfer Phone's chips: storage, the owner, or another failure. */
    fun reasons(groups: Map<Section, List<Bundle>>): Reasons {
        var storage = 0; var owner = 0; var other = 0
        for (row in groups[Section.ATTENTION].orEmpty()) {
            val error = row.getString("error") ?: row.getString("errorCode")
            when {
                error in STORAGE -> storage++
                row.getString("status") == "owner_action_required" || error in OWNER -> owner++
                else -> other++
            }
        }
        return Reasons(storage, owner, other)
    }

    fun headline(counts: Counts, loading: Boolean): String = when {
        loading -> "Checking your apps"
        counts.total == 0 && counts.notOnPlay == 0 -> "No apps in this transfer"
        counts.total == 0 -> if (counts.notOnPlay == 1) "This app isn’t on Google Play" else "These apps aren’t on Google Play"
        counts.installed == counts.total -> if (counts.total == 1) "Your app is installed" else "All ${counts.total} apps installed"
        else -> "${counts.installed} of ${counts.total} installed"
    }

    fun subline(counts: Counts, loading: Boolean): String? = when {
        loading -> null
        counts.total == 0 && counts.notOnPlay == 0 -> "No apps were selected for this transfer."
        counts.installing > 0 -> "${counts.installing} still installing. Keep using the phone; results update as each app finishes."
        counts.attention == 1 -> "1 app needs your attention."
        counts.attention > 1 -> "${counts.attention} apps need your attention."
        counts.total == 0 -> "App Store doesn’t list them, so they couldn’t be copied."
        else -> "Every app App Store could copy is on this phone."
    }
}

internal object MigrationResultText {
    /** What an owner Retry came to once all its apps are settled; null while any is still being tried. */
    fun retryOutcome(rows: List<Bundle>): String? {
        if (rows.isEmpty() || !rows.all { it.getBoolean("terminal") }) return null
        val failed = rows.filter { !it.getBoolean("installed") }
        if (failed.isEmpty()) return if (rows.size == 1) "Retry worked: the app is installed." else "Retry worked: all ${rows.size} apps are installed."
        val reasons = failed.map(::description).distinct()
        val count = if (failed.size == 1) "1 app still couldn't be installed" else "${failed.size} apps still couldn't be installed"
        return if (reasons.size == 1) "$count: ${reasons.single()}" else "$count. See the reasons below."
    }

    fun description(result: Bundle): String {
        if (result.getBoolean("installed")) return "Installed and verified"
        return when (result.getString("error") ?: result.getString("errorCode")) {
            "anonymous_sign_in_required", "not_signed_in", "session_expired" -> "App Store could not start an anonymous session. Retry when it is available."
            // Switch gives each batch of apps 45 s; one App Store had not checked in time is not a connection problem.
            "lookup_timeout" -> "App Store didn't finish checking this app in time. Retry to install it."
            "store_unavailable", "unavailable", "network_error", "network" -> "App Store is unavailable. Check your connection and retry."
            "rate_limited" -> "App Store is busy. Try again later."
            "no_google_account" -> "Sign in to a Google account on this phone to install this app with it."
            "several_google_accounts" -> "This phone has more than one Google account. Install this app from App Store."
            "google_sign_in_needed" -> "Sign in to your Google account again, then retry."
            "google_approval_needed" -> "Allow App Store to use your Google account in its notification, then retry."
            "not_found" -> "This app is not available in App Store."
            "paid", "paid_app", "purchase_required", "account_required" -> "This app requires an account or purchase and cannot be copied anonymously."
            "incompatible", "incompatible_device" -> "This app is not compatible with this phone."
            "insufficient_storage", "storage", "no_space" -> "There is not enough space. Free some storage and retry."
            "owner_removed", "removed_by_owner", "removed" -> "You removed this app. It will not be installed again."
            "store_update_required", "bridge_update_required" -> "Update App Store before transferring apps."
            "installation_confirmation_required" -> "Installation needs your approval. Retry to resume, or open App Store."
            "installation_verification_required" -> "App Store could not verify this installation. Retry or check App Store."
            "selected_version_unavailable" -> "The selected app version is no longer available. Check App Store before retrying."
            "previous_transfer_requires_review", "installation_requires_review" -> "App Store could not confirm the previous request. Retry to approve another installation."
            "cancelled", "canceled" -> "Installation was cancelled."
            "needs_confirmation", "user_action_required" -> "Open App Store to confirm the installation."
            null -> when (result.getString("status")) {
                "queued", "pending", "none" -> "Waiting for App Store"
                "downloading" -> "Downloading"
                "downloaded", "installing" -> "Installing"
                "cancelled", "canceled" -> "Installation was cancelled."
                "removed" -> "You removed this app. It will not be installed again."
                "failed", "unavailable" -> "Could not install this app. Retry or check App Store."
                else -> "Waiting for installation to be verified"
            }
            else -> "Could not install this app. Retry or check App Store."
        }
    }
}
