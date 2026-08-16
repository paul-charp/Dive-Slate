package io.github.paulcharp.diveslate.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.paulcharp.diveslate.BuildConfig
import io.github.paulcharp.diveslate.SlateExport
import io.github.paulcharp.diveslate.SlateFiles
import io.github.paulcharp.diveslate.SlatePainter
import io.github.paulcharp.diveslate.core.Dive
import io.github.paulcharp.diveslate.core.DiveLog
import io.github.paulcharp.diveslate.core.OverlayOptions
import io.github.paulcharp.diveslate.core.SLATE_THEMES
import io.github.paulcharp.diveslate.core.Slate
import io.github.paulcharp.diveslate.core.SlateLayout
import io.github.paulcharp.diveslate.core.SlateTheme
import io.github.paulcharp.diveslate.core.ceilMetres
import io.github.paulcharp.diveslate.core.formatMinutes
import io.github.paulcharp.diveslate.core.renderOverlay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Something to tell the user once.
 *
 * Carries an id so two identical messages — saving twice in a row — still read
 * as two events. Keyed only on the text, the second save would silently show
 * nothing, which is precisely the case where a confirmation matters most.
 */
data class Notice(val text: String, val id: Long = System.nanoTime())

/** What the activity has managed to load. */
sealed interface LoadState {
    data object Empty : LoadState
    data class Loaded(val log: DiveLog, val notice: Notice? = null) : LoadState
    data class Failed(val message: String) : LoadState
}

fun LoadState.withMessage(message: String): LoadState = when (this) {
    is LoadState.Loaded -> copy(notice = Notice(message))
    else -> LoadState.Failed(message)
}

/**
 * The story background sent to Instagram when no media is chosen.
 *
 * Bright cyan over near-black, mirroring what actually sits behind a dive
 * overlay: blown-out surface light and deep water. Still exported, but not what
 * the preview shows — see [drawCheckerboard].
 */
private const val BACKDROP_TOP = 0xFF2BA3C7L
private const val BACKDROP_BOTTOM = 0xFF04070AL

/**
 * Checkerboard greys.
 *
 * Deliberately light. Beyond indicating transparency, a white-ish backdrop is
 * exactly the worst case the dark palettes' scrim floors were computed against,
 * so a slate that holds up here holds up anywhere.
 */
private const val CHECKER_LIGHT = 0xFFE7EAEC
private const val CHECKER_DARK = 0xFFAFB6BA
private val CHECKER_CELL = 22.dp

private val Surface = Color(0xFF0B1013)
private val OnSurface = Color(0xFFE8EAEC)
private val Muted = Color(0xFF8C9599)

/**
 * Everything is offered, because a dive log has no MIME type of its own.
 *
 * Subsurface exports arrive as `.ssrf`, and file managers variously report that
 * as octet-stream, plain text, or nothing at all. Filtering on type would hide
 * the very files this exists to open. The content is sniffed after picking, and
 * anything that is not a dive log is refused with a clear message.
 */
private val PICKER_TYPES = arrayOf("*/*")

private val STAT_LABELS = listOf(
    "depth" to "Depth",
    "time" to "Runtime",
    "deco" to "Deco",
    "gf" to "GF",
    "used" to "Gas used",
    "avg" to "Avg depth",
    "temp" to "Temp",
    "sac" to "SAC",
    "cns" to "CNS",
    "gas" to "Gases",
)

@Composable
fun DiveSlateApp(
    state: LoadState,
    onLoadSample: () -> Unit,
    onOpenUri: (Uri) -> Unit,
    onBack: () -> Unit,
    onExport: (SlateExport, Pair<Long, Long>) -> Unit,
    onSaveToGallery: (SlateExport, String) -> Unit,
) {
    MaterialTheme {
        val snackbars = remember { SnackbarHostState() }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let(onOpenUri) }

        val log = (state as? LoadState.Loaded)?.log
        // A single-dive log has no list worth showing, so it opens straight
        // into the editor and back from there leaves entirely.
        val single = log != null && log.size == 1
        var picked by remember(log) { mutableStateOf<Int?>(null) }

        // Back steps out one screen at a time — editor to list, list to start —
        // rather than leaving the app from wherever you happen to be.
        BackHandler(enabled = state !is LoadState.Empty) {
            if (picked != null && !single) picked = null else onBack()
        }

        Box(Modifier.fillMaxSize().background(Surface).safeDrawingPadding()) {
            when (state) {
                is LoadState.Empty -> Welcome(onLoadSample) { picker.launch(PICKER_TYPES) }
                is LoadState.Failed -> Problem(state.message, onBack) {
                    picker.launch(PICKER_TYPES)
                }
                is LoadState.Loaded -> {
                    val index = picked ?: if (single) 0 else null
                    if (index == null) {
                        DiveList(state.log, onBack = onBack, onPick = { picked = it })
                    } else {
                        Editor(
                            state = state,
                            diveIndex = index,
                            onBack = { if (single) onBack() else picked = null },
                            onExport = onExport,
                            onSaveToGallery = onSaveToGallery,
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbars,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            ) { data ->
                Snackbar(snackbarData = data, containerColor = Color(0xFF1E2A30))
            }
        }

        // Keyed on the notice's id, so a second identical message still shows.
        val notice = (state as? LoadState.Loaded)?.notice
        LaunchedEffect(notice?.id) {
            notice?.let { snackbars.showSnackbar(it.text) }
        }
    }
}

@Composable
private fun Welcome(onLoadSample: () -> Unit, onPickFile: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Dive Slate", color = OnSurface, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            "v${BuildConfig.VERSION_NAME}",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "Share a dive from Subsurface, open an export from your files, " +
                "or start with the bundled sample.",
            color = Muted,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 16.dp, bottom = 28.dp),
        )
        Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
            Text("Open a dive log")
        }
        OutlinedButton(
            onClick = onLoadSample,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
            Text("Open the sample dive")
        }
    }
}

@Composable
private fun Problem(message: String, onBack: () -> Unit, onPickFile: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("That did not load", color = OnSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        // Scrollable and monospaced: this may be a multi-line description of an
        // intent that arrived in an unexpected shape, and it is the only
        // diagnostic there is when the handover fails on someone else's phone.
        Text(
            message,
            color = Muted,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
            Text("Try another file")
        }
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 6.dp)) {
            Text("Back to start")
        }
    }
}

/**
 * The whole log as a scrolling list, newest first.
 *
 * A full page rather than a row of chips: a real logbook is hundreds of dives,
 * and a horizontally scrolling picker hides all but the first few with nothing
 * to say the rest exist. Newest first because the dive you want to post is
 * almost always the one you just did.
 */
@Composable
private fun DiveList(log: DiveLog, onBack: () -> Unit, onPick: (Int) -> Unit) {
    // Indices, not dives: the editor addresses dives by their position in the
    // log, and sorting a copy would quietly renumber them.
    val order = remember(log) {
        log.dives.indices.sortedWith(
            compareByDescending<Int> { log[it].whenLogged ?: LocalDateTime.MIN }
                .thenByDescending { log[it].number ?: 0 }
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
        }
        Text(
            if (log.size == 1) "1 dive" else "${log.size} dives",
            color = OnSurface,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, bottom = 10.dp),
        )

        LazyColumn(Modifier.fillMaxSize()) {
            items(order.size) { position ->
                val index = order[position]
                DiveRow(log[index]) { onPick(index) }
            }
        }
    }
}

@Composable
private fun DiveRow(dive: Dive, onClick: () -> Unit) {
    val depth = ceilMetres(dive.computedMaxDepthMetres)
    val (runtime, unit) = formatMinutes(dive.computedDurationSeconds)

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            dive.site?.takeIf { it.isNotBlank() } ?: dive.title,
            color = OnSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Row {
            dive.whenLogged?.let {
                Text(it.format(LIST_DATE), color = Muted, fontSize = 13.sp)
                Text("   ", fontSize = 13.sp)
            }
            // Depth and runtime: the two numbers that identify a dive at a
            // glance, and the same two the slate leads with.
            Text("$depth m · $runtime $unit".trim(), color = Muted, fontSize = 13.sp)
            // Only when a site named the row. Without one the title already
            // falls back to "#9 · 2026-08-16", and repeating the number under
            // it says nothing.
            if (!dive.site.isNullOrBlank()) {
                dive.number?.let { Text("   #$it", color = Muted, fontSize = 13.sp) }
            }
        }
    }
    HorizontalDivider(color = Color(0xFF1C2429))
}

private val LIST_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

@Composable
private fun Editor(
    state: LoadState.Loaded,
    diveIndex: Int,
    onBack: () -> Unit,
    onExport: (SlateExport, Pair<Long, Long>) -> Unit,
    onSaveToGallery: (SlateExport, String) -> Unit,
) {
    val log = state.log
    var themeIndex by remember { mutableIntStateOf(0) }
    var tall by remember { mutableStateOf(false) }
    var showBackdrop by remember { mutableStateOf(true) }
    var opacity by remember { mutableFloatStateOf(SLATE_THEMES[0].scrimAlphaNominal) }

    var showSite by remember { mutableStateOf(true) }
    var showDate by remember { mutableStateOf(false) }
    var showScrim by remember { mutableStateOf(true) }
    var showCeiling by remember { mutableStateOf(true) }
    var showGas by remember { mutableStateOf(false) }
    var chosenStats by remember { mutableStateOf(emptySet<String>()) }

    val theme = SLATE_THEMES[themeIndex]
    // getOrNull rather than an index: an empty log is rejected at load, but a
    // crash here would be a blank screen with no way back, and coerceIn(0, -1)
    // on an empty list throws rather than clamping.
    val dive: Dive? = log.dives.getOrNull(diveIndex) ?: log.dives.firstOrNull()
    if (dive == null) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("This log contains no dives.", color = OnSurface, fontSize = 18.sp)
            TextButton(onClick = onBack) { Text("Back to start") }
        }
        return
    }
    val minOpacity = theme.scrimAlphaMin

    val slate = remember(
        dive, theme, tall, opacity, showSite, showDate, showScrim,
        showCeiling, showGas, chosenStats,
    ) {
        runCatching {
            renderOverlay(
                dive,
                OverlayOptions(
                    theme = theme,
                    layout = if (tall) SlateLayout.TALL else SlateLayout.WIDE,
                    scrimAlpha = opacity.coerceAtLeast(minOpacity),
                    showScrim = showScrim,
                    showSite = showSite,
                    showDate = showDate,
                    showCeiling = showCeiling,
                    showGas = showGas,
                    stats = chosenStats.takeIf { it.isNotEmpty() }
                        ?.let { picked -> STAT_LABELS.map { it.first }.filter { it in picked } },
                ),
            )
        }.getOrNull()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
        }

        Text(dive.title, color = OnSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)

        Preview(slate = slate, showBackdrop = showBackdrop)

        if (slate == null) {
            Text(
                "This dive has no depth samples, so there is no profile to draw.",
                color = Muted,
                fontSize = 14.sp,
            )
        }

        // ---- panel opacity --------------------------------------------------
        Label("Panel opacity  ${(opacity.coerceIn(minOpacity, 1f) * 100).toInt()}%")
        Slider(
            value = opacity.coerceIn(minOpacity, 1f),
            onValueChange = { opacity = it },
            // The floor is where ink stops clearing 4.5:1 against the worst
            // possible backdrop. Below it the panel has stopped working and the
            // halo is carrying the text alone, which is not enough over video.
            valueRange = minOpacity..1f,
            enabled = showScrim,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = showBackdrop, onCheckedChange = { showBackdrop = it })
            Text("  Checkerboard backdrop", color = Muted, fontSize = 14.sp)
        }

        // ---- palette --------------------------------------------------------
        Label("Palette — for dark footage")
        PaletteRow(SLATE_THEMES.filter { it.isDark }, theme) { picked ->
            themeIndex = SLATE_THEMES.indexOf(picked)
            opacity = opacity.coerceAtLeast(picked.scrimAlphaMin)
        }

        Label("Palette — for pale backgrounds")
        PaletteRow(SLATE_THEMES.filter { !it.isDark }, theme) { picked ->
            themeIndex = SLATE_THEMES.indexOf(picked)
            opacity = opacity.coerceAtLeast(picked.scrimAlphaMin)
        }

        // ---- format ---------------------------------------------------------
        Label("Format")
        ChipRow {
            FilterChip(selected = tall, onClick = { tall = true }, label = { Text("Tall") })
            FilterChip(selected = !tall, onClick = { tall = false }, label = { Text("Wide") })
        }

        // ---- elements -------------------------------------------------------
        Label("Elements")
        ChipRow {
            Toggle("Site", showSite) { showSite = it }
            Toggle("Date", showDate) { showDate = it }
            Toggle("Panel", showScrim) { showScrim = it }
            Toggle("Ceiling", showCeiling) { showCeiling = it }
            Toggle("Gas switches", showGas) { showGas = it }
        }

        // ---- figures --------------------------------------------------------
        Label(
            if (chosenStats.isEmpty()) "Figures — automatic"
            else "Figures — ${chosenStats.size} chosen"
        )
        ChipRow {
            FilterChip(
                selected = chosenStats.isEmpty(),
                onClick = { chosenStats = emptySet() },
                label = { Text("Auto", fontSize = 12.sp) },
            )
            STAT_LABELS.forEach { (key, label) ->
                FilterChip(
                    selected = key in chosenStats,
                    onClick = {
                        chosenStats = if (key in chosenStats) chosenStats - key else chosenStats + key
                    },
                    label = { Text(label, fontSize = 12.sp) },
                )
            }
        }
        if (chosenStats.isNotEmpty()) {
            Text(
                "A figure this dive did not record is skipped rather than shown blank.",
                color = Muted,
                fontSize = 12.sp,
            )
        }

        // ---- export ---------------------------------------------------------
        // Saving leads. The PNG is what this project actually produces, and it
        // is the option that works regardless of which apps are installed;
        // handing it straight to one particular app is the specialised case.
        val export = slate?.let { SlateExport(it, SlateFiles.EXPORT_SCALE) }

        Button(
            onClick = { export?.let { onSaveToGallery(it, dive.title) } },
            enabled = export != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save to gallery")
        }

        OutlinedButton(
            onClick = { export?.let { onExport(it, BACKDROP_TOP to BACKDROP_BOTTOM) } },
            enabled = export != null,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        ) {
            Text("Share")
        }
    }
}

/** How much of the preview's width the slate occupies. */
private const val SLATE_FRACTION = 0.86f

/** Backdrop kept around the slate, as a multiple of its own height. */
private const val PREVIEW_MARGIN = 1.28f

@Composable
private fun Preview(slate: Slate?, showBackdrop: Boolean) {
    // Sized from the slate rather than to a 9:16 story frame: the slate is the
    // deliverable, and the backdrop is only there to judge legibility against.
    val ratio = slate?.let {
        val drawnHeight = SLATE_FRACTION * (it.height / it.width)
        (1f / (drawnHeight * PREVIEW_MARGIN)).coerceIn(0.7f, 2.6f)
    } ?: (4f / 3f)

    Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(14.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            if (showBackdrop) drawCheckerboard()
            val current = slate ?: return@Canvas

            val target = size.width * SLATE_FRACTION
            val factor = target / current.width
            val left = (size.width - target) / 2f
            val top = (size.height - current.height * factor) / 2f

            translate(left, top) {
                scale(scaleX = factor, scaleY = factor, pivot = Offset.Zero) {
                    with(SlatePainter) { drawSlate(current) }
                }
            }
        }
    }
}

private fun DrawScope.drawCheckerboard() {
    drawRect(Color(CHECKER_LIGHT.toInt()))

    val cell = CHECKER_CELL.toPx()
    var row = 0
    var y = 0f
    while (y < size.height) {
        var column = 0
        var x = 0f
        while (x < size.width) {
            if ((row + column) % 2 == 1) {
                drawRect(
                    color = Color(CHECKER_DARK.toInt()),
                    topLeft = Offset(x, y),
                    // Clamped so the last cell is cropped rather than painted
                    // past the rounded corners.
                    size = Size(
                        width = minOf(cell, size.width - x),
                        height = minOf(cell, size.height - y),
                    ),
                )
            }
            x += cell
            column++
        }
        y += cell
        row++
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteRow(
    themes: List<SlateTheme>,
    selected: SlateTheme,
    onPick: (SlateTheme) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        themes.forEach { candidate ->
            PaletteSwatch(
                theme = candidate,
                selected = candidate.name == selected.name,
                onClick = { onPick(candidate) },
            )
        }
    }
}

/**
 * A swatch showing what the palette is: its two themed marks on the surface it
 * was validated against.
 *
 * A single dot of the curve colour cannot distinguish a dark-mode palette from
 * a light-mode one — several pairs share a hue and differ only in the
 * background they were checked against.
 */
@Composable
private fun PaletteSwatch(theme: SlateTheme, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(if (selected) 48.dp else 40.dp)
            .clip(CircleShape)
            .background(Color(theme.assumedSurface.toInt()))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) OnSurface else Muted.copy(alpha = 0.4f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Bar(Color(theme.curve.toInt()), tall = true)
            Bar(Color(theme.accent.toInt()), tall = false)
        }
    }
}

@Composable
private fun Bar(color: Color, tall: Boolean) {
    Box(
        Modifier
            .width(7.dp)
            .height(if (tall) 20.dp else 13.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color)
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = checked,
        onClick = { onChange(!checked) },
        label = { Text(label, fontSize = 12.sp) },
    )
}

/**
 * Chips that wrap rather than scroll.
 *
 * A horizontally scrolling row hid its own tail: with ten figures on offer the
 * last few sat off the right edge with nothing indicating they existed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}
