package io.github.paulcharp.diveslate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import io.github.paulcharp.diveslate.core.DiveLog
import io.github.paulcharp.diveslate.core.OverlayOptions
import io.github.paulcharp.diveslate.core.SLATE_THEMES
import io.github.paulcharp.diveslate.core.SlateLayout
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
 * checkerboard is honest about alpha but says nothing about legibility, and a
 * flat mid-grey stresses nothing at all. These are the same two values handed
 * to Instagram as the story gradient, so the preview is not a mock-up — with
 * the toggle on, it is what ships.
 */
private const val BACKDROP_TOP = 0xFF2BA3C7L
private const val BACKDROP_BOTTOM = 0xFF04070AL

private val Surface = Color(0xFF0B1013)
private val OnSurface = Color(0xFFE8EAEC)
private val Muted = Color(0xFF8C9599)

@Composable
fun DiveSlateApp(
    state: LoadState,
    onLoadSample: () -> Unit,
    onExport: (SlateExport, Pair<Long, Long>) -> Unit,
) {
    MaterialTheme {
        // The window is drawn edge to edge, so content has to be inset out from
        // under the status and navigation bars itself.
        Box(Modifier.fillMaxSize().background(Surface).safeDrawingPadding()) {
            when (state) {
                is LoadState.Empty -> Welcome(onLoadSample)
                is LoadState.Failed -> Problem(state.message, onLoadSample)
                is LoadState.Loaded -> Editor(state, onExport)
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
private fun Editor(state: LoadState.Loaded, onExport: (SlateExport, Pair<Long, Long>) -> Unit) {
    val log = state.log
    var diveIndex by remember { mutableIntStateOf(0) }
    var themeIndex by remember { mutableIntStateOf(0) }
    var tall by remember { mutableStateOf(true) }
    var showBackdrop by remember { mutableStateOf(true) }
    var opacity by remember { mutableFloatStateOf(SLATE_THEMES[0].scrimAlphaNominal) }

    val theme = SLATE_THEMES[themeIndex]
    val dive = log[diveIndex.coerceIn(0, log.size - 1)]

    // Clamped here as well as in the renderer: a slider that can be dragged
    // somewhere the renderer will refuse is a control that lies about its range.
    val minOpacity = theme.scrimAlphaMin

    val slate = remember(dive, theme, tall, opacity) {
        runCatching {
            renderOverlay(
                dive,
                OverlayOptions(
                    theme = theme,
                    layout = if (tall) SlateLayout.TALL else SlateLayout.WIDE,
                    scrimAlpha = opacity.coerceAtLeast(minOpacity),
                    showSite = true,
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(log.dives.size) { index ->
                    FilterChip(
                        selected = index == diveIndex,
                        onClick = { diveIndex = index },
                        label = { Text(log[index].title, fontSize = 12.sp) },
                    )
                }
            }
        }

        // A 9:16 frame, because that is what a story is. Previewing a slate on a
        // shape it will never occupy tells you nothing about the composition.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(14.dp)),
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

        if (slate == null) {
            Text(
                "This dive has no depth samples, so there is no profile to draw.",
                color = Muted,
                fontSize = 14.sp,
            )
        }

        state.message?.let { Text(it, color = Muted, fontSize = 13.sp) }

        Label("Palette")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(SLATE_THEMES.size) { index ->
                val candidate = SLATE_THEMES[index]
                Box(
                    Modifier
                        .size(if (index == themeIndex) 40.dp else 32.dp)
                        .clip(CircleShape)
                        .background(Color(candidate.curve.toInt()))
                        .clickable {
                            themeIndex = index
                            opacity = opacity.coerceAtLeast(candidate.scrimAlphaMin)
                        }
                )
            }
        }

        Label("Format")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tall, onClick = { tall = true }, label = { Text("Tall") })
            FilterChip(selected = !tall, onClick = { tall = false }, label = { Text("Wide") })
        }

        Label("Panel opacity  ${(opacity * 100).toInt()}%")
        Slider(
            value = opacity.coerceIn(minOpacity, 1f),
            onValueChange = { opacity = it },
            // The floor is where ink stops clearing 4.5:1 on the worst possible
            // backdrop. Below it the panel has stopped working and the halo is
            // carrying the text alone, which is not enough over video.
            valueRange = minOpacity..1f,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = showBackdrop, onCheckedChange = { showBackdrop = it })
            Text(
                "  Placeholder backdrop",
                color = Muted,
                fontSize = 14.sp,
            )
        }

        Button(
            onClick = {
                slate?.let {
                    onExport(
                        SlateExport(it, SlateFiles.EXPORT_SCALE),
                        BACKDROP_TOP to BACKDROP_BOTTOM,
                    )
                }
            },
            enabled = slate != null,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text("Send to Instagram")
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}
