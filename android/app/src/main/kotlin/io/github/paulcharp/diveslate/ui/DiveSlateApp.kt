package io.github.paulcharp.diveslate.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.paulcharp.diveslate.SlateExport
import io.github.paulcharp.diveslate.SlateFiles
import io.github.paulcharp.diveslate.SlatePainter
import io.github.paulcharp.diveslate.core.Dive
import io.github.paulcharp.diveslate.core.DiveLog
import io.github.paulcharp.diveslate.core.OverlayOptions
import io.github.paulcharp.diveslate.core.SLATE_THEMES
import io.github.paulcharp.diveslate.core.SlateLayout
import io.github.paulcharp.diveslate.core.SlateTheme
import io.github.paulcharp.diveslate.core.renderOverlay

/** What the activity has managed to load. */
sealed interface LoadState {
    data object Empty : LoadState
    data class Loaded(val log: DiveLog, val message: String? = null) : LoadState
    data class Failed(val message: String) : LoadState
}

fun LoadState.withMessage(message: String): LoadState = when (this) {
    is LoadState.Loaded -> copy(message = message)
    else -> LoadState.Failed(message)
}

/**
 * The placeholder backdrop, and the story background when no media is chosen.
 *
 * Bright cyan over near-black, because that is what actually breaks an overlay:
 * blown-out surface light and deep water, usually in the same frame. A
 * checkerboard is honest about alpha but says nothing about legibility. These
 * are the same two values handed to Instagram as the story gradient, so with
 * the toggle on the preview is what ships rather than a mock-up.
 */
private const val BACKDROP_TOP = 0xFF2BA3C7L
private const val BACKDROP_BOTTOM = 0xFF04070AL

private val Surface = Color(0xFF0B1013)
private val OnSurface = Color(0xFFE8EAEC)
private val Muted = Color(0xFF8C9599)

/**
 * Display names for the stat keys the core exposes.
 *
 * Order is the order they are offered in, which is roughly how useful they are
 * on a badge — the first two are the numbers every diver reads first.
 */
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
    "gas" to "Mix",
)

@Composable
fun DiveSlateApp(
    state: LoadState,
    onLoadSample: () -> Unit,
    onExport: (SlateExport, Pair<Long, Long>) -> Unit,
    onSaveToGallery: (SlateExport, String) -> Unit,
) {
    MaterialTheme {
        // The window draws edge to edge, so content insets itself out from
        // under the system bars.
        Box(Modifier.fillMaxSize().background(Surface).safeDrawingPadding()) {
            when (state) {
                is LoadState.Empty -> Welcome(onLoadSample)
                is LoadState.Failed -> Problem(state.message, onLoadSample)
                is LoadState.Loaded -> Editor(state, onExport, onSaveToGallery)
            }
        }
    }
}

@Composable
private fun Welcome(onLoadSample: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Dive Slate", color = OnSurface, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            "Export a dive from Subsurface and share it here, " +
                "or start with the bundled sample.",
            color = Muted,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 28.dp),
        )
        Button(onClick = onLoadSample) { Text("Open the sample dive") }
    }
}

@Composable
private fun Problem(message: String, onLoadSample: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("That did not load", color = OnSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(message, color = Muted, fontSize = 15.sp, modifier = Modifier.padding(vertical = 16.dp))
        Button(onClick = onLoadSample) { Text("Open the sample dive instead") }
    }
}

@Composable
private fun Editor(
    state: LoadState.Loaded,
    onExport: (SlateExport, Pair<Long, Long>) -> Unit,
    onSaveToGallery: (SlateExport, String) -> Unit,
) {
    val log = state.log
    var diveIndex by remember { mutableIntStateOf(0) }
    var themeIndex by remember { mutableIntStateOf(0) }
    // Wide by default: it suits a feed post or the corner of a video, which is
    // the common case. Tall is the deliberate choice for a full-height story.
    var tall by remember { mutableStateOf(false) }
    var showBackdrop by remember { mutableStateOf(true) }
    var opacity by remember { mutableFloatStateOf(SLATE_THEMES[0].scrimAlphaNominal) }

    var showSite by remember { mutableStateOf(true) }
    var showDate by remember { mutableStateOf(false) }
    var showScrim by remember { mutableStateOf(true) }
    var showCeiling by remember { mutableStateOf(true) }
    var showGas by remember { mutableStateOf(false) }
    // Empty means automatic: the renderer picks the most headline-worthy
    // figures the log can actually answer.
    var chosenStats by remember { mutableStateOf(emptySet<String>()) }

    val theme = SLATE_THEMES[themeIndex]
    val dive: Dive = log[diveIndex.coerceIn(0, log.size - 1)]
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
        Text(dive.title, color = OnSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)

        if (log.size > 1) {
            ChipRow {
                log.dives.forEachIndexed { index, candidate ->
                    FilterChip(
                        selected = index == diveIndex,
                        onClick = { diveIndex = index },
                        label = { Text(candidate.title, fontSize = 12.sp) },
                    )
                }
            }
        }

        Preview(slate = slate, showBackdrop = showBackdrop)

        if (slate == null) {
            Text(
                "This dive has no depth samples, so there is no profile to draw.",
                color = Muted,
                fontSize = 14.sp,
            )
        }
        state.message?.let { Text(it, color = Muted, fontSize = 13.sp) }

        // ---- palette --------------------------------------------------------
        // Grouped, because a palette validated against dark footage and one
        // validated against a pale page are not interchangeable, and nothing in
        // the colours themselves says which is which.
        Label("Palette — for dark footage")
        PaletteRow(
            themes = SLATE_THEMES.filter { it.isDark },
            selected = theme,
            onPick = { picked ->
                themeIndex = SLATE_THEMES.indexOf(picked)
                opacity = opacity.coerceAtLeast(picked.scrimAlphaMin)
            },
        )

        Label("Palette — for pale backgrounds")
        PaletteRow(
            themes = SLATE_THEMES.filter { !it.isDark },
            selected = theme,
            onPick = { picked ->
                themeIndex = SLATE_THEMES.indexOf(picked)
                opacity = opacity.coerceAtLeast(picked.scrimAlphaMin)
            },
        )

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
            Text("  Placeholder backdrop", color = Muted, fontSize = 14.sp)
        }

        // ---- export ---------------------------------------------------------
        val export = slate?.let { SlateExport(it, SlateFiles.EXPORT_SCALE) }

        Button(
            onClick = { export?.let { onExport(it, BACKDROP_TOP to BACKDROP_BOTTOM) } },
            enabled = export != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Send to Instagram")
        }

        OutlinedButton(
            onClick = { export?.let { onSaveToGallery(it, dive.title) } },
            enabled = export != null,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        ) {
            Text("Save transparent PNG")
        }
    }
}

@Composable
private fun Preview(slate: io.github.paulcharp.diveslate.core.Slate?, showBackdrop: Boolean) {
    // A 9:16 frame, because that is what a story is. Previewing a slate on a
    // shape it will never occupy says nothing about the composition.
    Box(
        Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(14.dp)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (showBackdrop) {
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color(BACKDROP_TOP.toInt()), Color(BACKDROP_BOTTOM.toInt()))
                    )
                )
            }
            val current = slate ?: return@Canvas

            // Placed as the export places it: 86% of frame width, low left.
            val target = size.width * 0.86f
            val factor = target / current.width
            val left = (size.width - target) / 2f
            val top = size.height - current.height * factor - size.height * 0.06f

            translate(left, top) {
                scale(scaleX = factor, scaleY = factor, pivot = Offset.Zero) {
                    with(SlatePainter) { drawSlate(current) }
                }
            }
        }
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
 * A swatch showing what the palette actually is: its two themed marks sitting
 * on the surface it was validated against.
 *
 * A single dot of the curve colour cannot distinguish a dark-mode palette from
 * a light-mode one — several pairs share a hue and differ only in the
 * background they were checked against. Showing the surface is what makes the
 * two groups legible at a glance.
 */
@Composable
private fun PaletteSwatch(theme: SlateTheme, selected: Boolean, onClick: () -> Unit) {
    val diameter = if (selected) 48.dp else 40.dp
    Box(
        Modifier
            .size(diameter)
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
            // The depth curve — the mark the eye goes to first.
            Bar(Color(theme.curve.toInt()), tall = true)
            // The gas accent, chosen by search to separate from both the curve
            // and the fixed ceiling red.
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
 * last few sat off the right edge with nothing indicating they existed. An
 * option you cannot see is an option you do not have.
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
