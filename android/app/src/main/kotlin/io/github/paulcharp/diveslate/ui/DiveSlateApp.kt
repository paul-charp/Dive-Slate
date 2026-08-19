package io.github.paulcharp.diveslate.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import io.github.paulcharp.diveslate.BuildConfig
import io.github.paulcharp.diveslate.ExportRequest
import io.github.paulcharp.diveslate.SlatePainter
import io.github.paulcharp.diveslate.UpdateCheck
import io.github.paulcharp.diveslate.core.Dive
import io.github.paulcharp.diveslate.core.DiveLog
import io.github.paulcharp.diveslate.core.OverlayOptions
import io.github.paulcharp.diveslate.core.SLATE_STYLES
import io.github.paulcharp.diveslate.core.Slate
import io.github.paulcharp.diveslate.core.SlateLayout
import io.github.paulcharp.diveslate.core.SlateStyle
import io.github.paulcharp.diveslate.core.SlateTheme
import io.github.paulcharp.diveslate.core.adopt
import io.github.paulcharp.diveslate.core.availableStats
import io.github.paulcharp.diveslate.core.ceilMetres
import io.github.paulcharp.diveslate.core.formatMinutes
import io.github.paulcharp.diveslate.core.renderOverlay
import java.io.File
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

    /**
     * One entry per file, rather than one merged list of dives.
     *
     * Opening several files at once makes provenance load-bearing. Two logbooks
     * overlapping — the same dive exported twice, a file shared again after an
     * edit — is an ordinary accident, and merged into one list those become two
     * rows that look identical and cannot be told apart. There is no honest way
     * to de-duplicate them either: dive numbers are per-logbook and collide
     * across files, so any key would be a guess, and this codebase's rule is to
     * degrade to nothing rather than to a guess. So the files stay separate and
     * the list says which is which.
     */
    data class Loaded(val logs: List<DiveLog>, val notice: Notice? = null) : LoadState

    data class Failed(val message: String) : LoadState
}

fun LoadState.withMessage(message: String): LoadState = when (this) {
    is LoadState.Loaded -> copy(notice = Notice(message))
    else -> LoadState.Failed(message)
}

/** A dive addressed by the file it came from and its position within that file. */
data class DiveRef(val log: Int, val dive: Int)

/** How many dives are open, across every file. */
val LoadState.Loaded.diveCount: Int get() = logs.sumOf { it.size }

fun LoadState.Loaded.dive(ref: DiveRef): Dive? =
    logs.getOrNull(ref.log)?.dives?.getOrNull(ref.dive)

/** What to call a file in the list. */
fun LoadState.Loaded.sourceOf(index: Int): String =
    logs.getOrNull(index)?.source?.takeIf { it.isNotBlank() } ?: "Shared log"

/**
 * Every dive in the order the list shows it: grouped by file, newest first
 * within each.
 *
 * Sorted per file rather than across all of them, because the grouping is the
 * point — a strictly chronological run interleaving two logbooks would put a
 * file's dives in several places under one heading. This is also the order a
 * batch exports in, so what the list shows top to bottom is what the gallery
 * receives.
 */
fun LoadState.Loaded.order(): List<DiveRef> = logs.indices.flatMap { l ->
    val log = logs[l]
    log.dives.indices
        .sortedWith(
            compareByDescending<Int> { log[it].whenLogged ?: LocalDateTime.MIN }
                .thenByDescending { log[it].number ?: 0 }
        )
        .map { DiveRef(l, it) }
}

/**
 * Why this dive cannot become a slate, or null if it can.
 *
 * Asked before anything is selected rather than after something failed. A
 * hand-entered dive with no computer download has no profile to draw, and in a
 * batch that would be a slate quietly missing from the gallery — so the row
 * says why instead, and refuses to be picked.
 */
fun Dive.blockedReason(): String? =
    if (samples.isEmpty()) "No depth samples — there is no profile to draw" else null

/**
 * Where a batch of slates has got to.
 *
 * A slate at export density takes a visible moment to compress, so a selection
 * of any size spends real time between the tap and the last file. Without this
 * the export button would simply sit there, which is the failure mode this app
 * keeps arriving at from other directions: work that produces no visible result
 * is indistinguishable from a button that did nothing.
 */
sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val verb: String, val done: Int, val total: Int) : ExportState
}

/**
 * Where the app is in finding out whether it is out of date.
 *
 * [Idle] is silent, and it is where an automatic check that found nothing —
 * including one that failed — puts things back. Being told "you are up to date"
 * or "GitHub could not be reached" is only useful in answer to having asked, and
 * a daily background check nobody requested has no business reporting either.
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: UpdateCheck.Release) : UpdateState
    data class Downloading(val release: UpdateCheck.Release, val fraction: Float) : UpdateState
    /**
     * Downloaded and verified. [note] replaces the default explanation when the
     * install could not be started yet — almost always because permission to
     * install from this app has not been granted, which is a detour rather than
     * a failure, so the state stays Ready and keeps its button.
     */
    data class Ready(
        val release: UpdateCheck.Release,
        val apk: File,
        val note: String? = null,
    ) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Bundled so that the app's signature stays about dive logs. */
data class Updates(
    val state: UpdateState,
    val onCheck: () -> Unit,
    val onDownload: (UpdateCheck.Release) -> Unit,
    // The whole state, so the installer never has to look up which release the
    // file on disk belongs to.
    val onInstall: (UpdateState.Ready) -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * Checkerboard greys.
 *
 * Mid-grey rather than the near-white this started as. Both readings of the
 * backdrop are legitimate — it indicates transparency, and it stands in for the
 * footage the slate will land on — and they want opposite things. White is the
 * worst case the dark palettes' scrim floors were computed against, so a slate
 * that held up over it held up anywhere; but it is not what anyone drops a
 * slate onto, and it made every dark palette look as though it were fighting
 * the backdrop.
 *
 * The floor is unaffected either way: it is computed against white in
 * [io.github.paulcharp.diveslate.core.SlateTheme.scrimAlphaMin] and still binds
 * the slider. What changed is that the preview no longer shows that case, only
 * the typical one.
 *
 * **These two do not follow the system theme, and that is the point.** Every
 * other colour on this screen is chrome and moves with the phone; this one is
 * the stand-in for the footage. A checkerboard that went pale on a light phone
 * would mean the same slate was being judged against two different backdrops
 * depending on a setting that has nothing to do with where the image is going —
 * and a light palette would look correct on a light phone and wrong on the
 * video. Fixed here, the mode changes the shell and leaves the verdict alone.
 */
private const val CHECKER_LIGHT = 0xFF5E666B
private const val CHECKER_DARK = 0xFF4A5257
private val CHECKER_CELL = 22.dp

/**
 * Material You, following the phone.
 *
 * This was pinned dark for a long time, on the argument that a light shell would
 * compete with the transparent slate being judged. What made that argument
 * survive was the checkerboard, and the checkerboard does not follow the theme —
 * it is a stand-in for the footage, so it stays mid-grey whichever way the phone
 * is set (see [CHECKER_LIGHT]). The slate is therefore judged against exactly
 * the same backdrop in both modes, and the shell around it is free to be the one
 * the user asked their phone for. An app that stays dark on a light phone is not
 * protecting anything here; it is just ignoring a setting.
 *
 * What the wallpaper is allowed to move is the accent — on Android 12 and up the
 * scheme is derived from it, so the app looks like it belongs to the phone rather
 * than to itself.
 *
 * None of this reaches the slate. The palettes in `Themes.kt` cleared measured
 * contrast gates against the marks they paint, and a colour chosen from someone's
 * wallpaper has cleared nothing. The dynamic scheme colours buttons, chips and
 * text; the drawing keeps the palette the user picked, in the mode the user
 * picked — the slate's own dark/light choice is a statement about the footage and
 * is not touched by the phone's.
 */
@Composable
private fun DiveSlateTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) FALLBACK_DARK else FALLBACK_LIGHT
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * For Android 11, which has no wallpaper palette to ask.
 *
 * Blue, because that is what the profile curve has always been drawn in and what
 * the launcher icon carries.
 */
private val FALLBACK_DARK = darkColorScheme(
    primary = Color(0xFF9CCAFF),
    onPrimary = Color(0xFF003257),
    primaryContainer = Color(0xFF00497C),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondaryContainer = Color(0xFF23405B),
    onSecondaryContainer = Color(0xFFD1E4FF),
    background = Color(0xFF0B1013),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF0B1013),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC2C7CF),
    outline = Color(0xFF8C9199),
)

/**
 * The same blue, for a light phone on Android 11.
 *
 * Tonally the mirror of [FALLBACK_DARK] rather than a second design: the two are
 * one scheme read from opposite ends, so a screenshot taken in either mode shows
 * the same app.
 */
private val FALLBACK_LIGHT = lightColorScheme(
    primary = Color(0xFF00639B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D32),
    secondaryContainer = Color(0xFFD6E4F7),
    onSecondaryContainer = Color(0xFF101C2B),
    background = Color(0xFFF7FAFD),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFF7FAFD),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF41484D),
    outline = Color(0xFF71787E),
)

/**
 * The three text tones, read off the scheme.
 *
 * Composable getters rather than constants, because with a dynamic scheme there
 * is no longer one right answer to compile in — the values depend on the
 * wallpaper and have to be asked for at composition.
 */
private val Surface: Color
    @Composable get() = MaterialTheme.colorScheme.background

private val OnSurface: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface

private val Muted: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

/**
 * For something the user should read before acting rather than after.
 *
 * The one colour here that is *not* taken from the scheme. A caution has to read
 * as a caution against any wallpaper, and `tertiary` is whatever the phone
 * happens to hand over — on a warm wallpaper it can arrive indistinguishable
 * from the body text. Amber rather than the deco red, which stays reserved: that
 * red is a hazard marker inside the slate, and spending it on chrome would
 * weaken what it means there.
 *
 * Two values rather than one, because the shell now follows the phone and the
 * light amber that reads as a caution on a near-black surface measures under
 * 2:1 on a near-white one — which would leave the one line the user is meant to
 * read before acting as the faintest text on the screen. Same hue, wound down
 * in lightness until it clears the body text going the other way.
 */
private val Caution: Color
    @Composable get() =
        if (isSystemInDarkTheme()) Color(0xFFE8B860) else Color(0xFF7A5200)

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

/**
 * The chosen figures cut to [budget], keeping the ones that read first.
 *
 * In the order [STAT_LABELS] lists them, which is the order the slate prints
 * them in — so trimming takes off the tail the user would have seen last rather
 * than whichever entries a set happens to iterate late.
 */
private fun Set<String>.trimmedTo(budget: Int): Set<String> =
    if (size <= budget) this
    else STAT_LABELS.map { it.first }.filter { it in this }.take(budget).toSet()

@Composable
fun DiveSlateApp(
    state: LoadState,
    updates: Updates,
    exports: ExportState,
    onLoadSample: () -> Unit,
    onOpenUris: (List<Uri>) -> Unit,
    onBack: () -> Unit,
    onExport: (ExportRequest) -> Unit,
    onSaveToGallery: (ExportRequest) -> Unit,
) {
    DiveSlateTheme {
        val snackbars = remember { SnackbarHostState() }

        // Several files, because a logbook split by year or by trip is a normal
        // thing to have and picking them one at a time would drop the previous
        // one on each trip through the picker.
        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris -> if (uris.isNotEmpty()) onOpenUris(uris) }

        val loaded = state as? LoadState.Loaded
        // A single dive has no list worth showing, so it opens straight into the
        // editor and back from there leaves entirely.
        val single = loaded != null && loaded.diveCount == 1

        // What the editor is currently showing. Empty means the list; one ref
        // is the ordinary single-dive edit; several is a batch. Reset whenever
        // a different set of files is loaded, since the refs address those.
        var editing by remember(loaded?.logs) { mutableStateOf<List<DiveRef>>(emptyList()) }
        // Invariant: a non-empty [selection] implies [selecting]. The list reads
        // the two in different places — the row tint follows the selection, the
        // top bar and the dots and the action bar follow the mode — so breaking
        // it does not produce a clean "nothing is selected", it produces a list
        // with rows visibly highlighted and no control anywhere that admits it.
        // Every site that clears one clears the other; see [onOpenSelection] for
        // the one that used to not.
        var selection by remember(loaded?.logs) { mutableStateOf<Set<DiveRef>>(emptySet()) }
        var selecting by remember(loaded?.logs) { mutableStateOf(false) }

        // Back steps out one screen at a time — editor to list, selection to
        // list, list to start — rather than leaving the app from wherever you
        // happen to be.
        BackHandler(enabled = state !is LoadState.Empty) {
            when {
                editing.isNotEmpty() && !single -> editing = emptyList()
                selecting -> { selecting = false; selection = emptySet() }
                else -> onBack()
            }
        }

        // No inset padding here. Edge-to-edge means the app bars and the
        // selection bar paint their own colour behind the system bars — a
        // contextual bar that stops short of the status bar reads as a floating
        // strip rather than as the top of the screen — so each screen takes the
        // insets it actually needs.
        Box(Modifier.fillMaxSize().background(Surface)) {
            when (state) {
                is LoadState.Empty -> Welcome(
                    onLoadSample = onLoadSample,
                    onPickFile = { picker.launch(PICKER_TYPES) },
                    onCheckUpdates = updates.onCheck,
                    checking = updates.state is UpdateState.Checking,
                )
                is LoadState.Failed -> Problem(state.message, onBack) {
                    picker.launch(PICKER_TYPES)
                }
                is LoadState.Loaded -> {
                    val order = remember(state.logs) { state.order() }
                    val open = when {
                        editing.isNotEmpty() -> editing
                        single -> order
                        else -> emptyList()
                    }
                    if (open.isEmpty()) {
                        DiveList(
                            state = state,
                            order = order,
                            selection = selection,
                            selecting = selecting,
                            onBack = onBack,
                            onOpen = { ref -> editing = listOf(ref) },
                            onSelection = { picked -> selection = picked },
                            onSelecting = { on ->
                                selecting = on
                                if (!on) selection = emptySet()
                            },
                            onOpenSelection = {
                                // In list order, not the order they were tapped:
                                // the switcher steps through them the way they
                                // were read, and the batch exports in that order
                                // too.
                                //
                                // The selection is deliberately left standing.
                                // Opening the editor is not leaving the
                                // selection — the selection is what the editor
                                // is editing — so back out of the editor and the
                                // list is exactly as it was left, still ticked
                                // and still offering to open them. Dropping the
                                // mode here collapsed two back steps into one on
                                // the way in and left the selection behind
                                // invisibly on the way out: the rows kept their
                                // highlight, while the bar said "6 dives" and
                                // offered "Select", and tapping it brought the
                                // three back as though from nowhere.
                                editing = order.filter { it in selection }
                            },
                        )
                    } else {
                        Editor(
                            state = state,
                            refs = open,
                            exports = exports,
                            onBack = {
                                if (single) onBack() else editing = emptyList()
                            },
                            onExport = onExport,
                            onSaveToGallery = onSaveToGallery,
                        )
                    }
                }
            }

            // Over whichever screen is showing, rather than tucked into the
            // welcome page. The ordinary way in is a share from Subsurface,
            // which lands straight in the editor, so anything only reachable
            // from the start screen goes unseen by exactly the people who use
            // the app most.
            UpdateBanner(updates, Modifier.align(Alignment.TopCenter))

            SnackbarHost(
                hostState = snackbars,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(12.dp),
            ) { data ->
                Snackbar(snackbarData = data)
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
private fun Welcome(
    onLoadSample: () -> Unit,
    onPickFile: () -> Unit,
    onCheckUpdates: () -> Unit,
    checking: Boolean,
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Dive Slate",
            color = OnSurface,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "v${BuildConfig.VERSION_NAME}",
            color = Muted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "Share a dive from Subsurface, open an export from your files, " +
                "or start with the bundled sample.",
            color = Muted,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp, bottom = 28.dp),
        )
        Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
            Text("Open a dive log")
        }
        // Tonal rather than outlined: the two are a pair of ways in, and the
        // filled/tonal pairing reads as one primary and one secondary rather
        // than as a button and its ghost.
        FilledTonalButton(
            onClick = onLoadSample,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
            Text("Open the sample dive")
        }
        // The check also runs by itself once a day. This is for the moment you
        // have just been told a fix exists and do not want to wait for that.
        TextButton(
            onClick = onCheckUpdates,
            enabled = !checking,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            Text(
                if (checking) "Checking…" else "Check for updates",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        // Under the update check, because it answers the question that one
        // raises: there is no store page to read, so the repository is the only
        // place saying what changed, what this reads, and what it does with it.
        // An app that installs itself from GitHub should say which GitHub.
        //
        // Guarded, and quiet about it. A phone with no browser at all is not a
        // real case, but a chooser that cannot be resolved throws, and crashing
        // the start screen over a footer link would cost far more than the link
        // is worth.
        val context = LocalContext.current
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, UpdateCheck.PROJECT_URL.toUri())
                    )
                }
            },
        ) {
            Text(
                "View the project on GitHub",
                style = MaterialTheme.typography.labelLarge,
                color = Muted,
            )
        }
    }
}
/**
 * What the app has to say about a newer version.
 *
 * There is no store to do this, so the app has to say it itself — and it is the
 * one thing here that talks to the network, which is why every state is visible
 * rather than a spinner that resolves into a silent install.
 */
@Composable
private fun UpdateBanner(updates: Updates, modifier: Modifier = Modifier) {
    val state = updates.state
    if (state is UpdateState.Idle) return

    // A tonal card rather than a hand-drawn box. It floats over whichever
    // screen is showing, so it needs a surface of its own that the scheme can
    // tint along with everything else.
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxWidth()
            .padding(10.dp),
    ) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        when (state) {
            is UpdateState.Idle -> Unit

            is UpdateState.Checking ->
                Text("Checking for updates…", style = MaterialTheme.typography.bodyMedium)

            is UpdateState.UpToDate -> BannerRow(
                title = "Dive Slate ${BuildConfig.VERSION_NAME} is the latest version",
                onDismiss = updates.onDismiss,
            )

            is UpdateState.Available -> {
                BannerRow(
                    title = "Dive Slate ${state.release.versionName} is available",
                    detail = "${megabytes(state.release.apkSize)} download",
                    onDismiss = updates.onDismiss,
                )
                Button(
                    onClick = { updates.onDownload(state.release) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Text("Download")
                }
            }

            is UpdateState.Downloading -> {
                Text(
                    "Downloading ${state.release.versionName}…",
                    style = MaterialTheme.typography.titleSmall,
                )
                // Determinate, because the manifest carries the size. A bar that
                // cannot say how far along it is turns a slow connection into an
                // app that looks stuck.
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }

            is UpdateState.Ready -> {
                BannerRow(
                    title = "Dive Slate ${state.release.versionName} is ready to install",
                    // Android asks for permission to install from this app the
                    // first time, in Settings, and there is no way to prompt for
                    // it inline — so say what is about to happen instead of
                    // letting the installer appear to do nothing.
                    detail = state.note ?: "Android will ask you to confirm the install",
                    onDismiss = updates.onDismiss,
                )
                Button(
                    onClick = { updates.onInstall(state) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Text("Install")
                }
            }

            is UpdateState.Failed -> BannerRow(
                title = "Could not check for updates",
                detail = state.message,
                onDismiss = updates.onDismiss,
            )
        }
    }
    }
}

/**
 * A line of the update banner, and the one way out of it.
 *
 * The dismiss is a cross rather than the "Not now" it used to be. This row is
 * shared by every state the banner has, and "Not now" only ever described one
 * of them: against "is the latest version" it offered to postpone something
 * that had already finished, and against "could not check" it read as declining
 * an offer that was never made. A cross means close, in all four, and it means
 * it without a word — which also stops the widest state of the banner from
 * pushing its own title into two lines on a narrow phone.
 */
@Composable
private fun BannerRow(title: String, detail: String? = null, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // Sized down from the 48dp default and nudged out to the edge: at full
        // size it sat a thumb's width in from the corner and stretched the card
        // taller than the two lines beside it.
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(32.dp).offset(x = 6.dp, y = (-2).dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun megabytes(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

@Composable
private fun Problem(message: String, onBack: () -> Unit, onPickFile: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "That did not load",
            color = OnSurface,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        // In a container of its own, because it is evidence rather than prose:
        // this may be a multi-line description of an intent that arrived in an
        // unexpected shape, and it is the only diagnostic there is when the
        // handover fails on someone else's phone. Monospaced and scrollable for
        // the same reason.
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        ) {
            Text(
                message,
                color = Muted,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(16.dp),
            )
        }
        Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
            Text("Try another file")
        }
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 6.dp)) {
            Text("Back to start")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiveList(
    state: LoadState.Loaded,
    order: List<DiveRef>,
    selection: Set<DiveRef>,
    selecting: Boolean,
    onBack: () -> Unit,
    onOpen: (DiveRef) -> Unit,
    onSelection: (Set<DiveRef>) -> Unit,
    onSelecting: (Boolean) -> Unit,
    onOpenSelection: () -> Unit,
) {
    // groupBy keeps insertion order, so the files appear in the order they were
    // opened and each group holds the sort [order] already put it in.
    val groups = remember(order) { order.groupBy { it.log } }
    val grouped = state.logs.size > 1

    // Select-all means every dive that can actually become a slate. Including
    // the ones the list has already refused would either put a known failure in
    // the batch or leave a circle that will not fill — and a control that does
    // nothing when pressed is worse than one that is not offered.
    val selectable = remember(order, state.logs) {
        order.filter { state.dive(it)?.blockedReason() == null }.toSet()
    }
    val allSelected = selectable.isNotEmpty() && selection.containsAll(selectable)

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { onSelecting(false) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Stop selecting")
                    }
                },
                title = {
                    Column {
                        Text("${selection.size} selected", fontSize = 19.sp)
                        // The slot Files fills with a total size. Used here only
                        // when there is something worth saying: a batch this
                        // size is a wait and a pile of gallery entries, and both
                        // are easier to agree to before than to undo after.
                        if (selection.size > LARGE_BATCH) {
                            Text(
                                "a while to draw · ${selection.size} images",
                                color = Caution,
                                fontSize = 12.sp,
                            )
                        }
                    }
                },
                actions = {
                    TextButton(
                        onClick = { onSelection(if (allSelected) emptySet() else selectable) },
                    ) {
                        Text(if (allSelected) "Deselect all" else "Select all")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    titleContentColor = OnSurface,
                ),
            )
        } else {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            if (state.diveCount == 1) "1 dive" else "${state.diveCount} dives",
                            fontSize = 19.sp,
                        )
                        if (grouped) {
                            Text("in ${state.logs.size} files", color = Muted, fontSize = 12.sp)
                        }
                    }
                },
                actions = {
                    if (state.diveCount > 1) {
                        TextButton(onClick = { onSelecting(true) }) { Text("Select") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = OnSurface,
                ),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(
                bottom = 12.dp +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            groups.forEach { (logIndex, refs) ->
                // Headings only when there is more than one file. A single one
                // over the whole list names something the user already knows,
                // and costs a row of every screenful.
                if (grouped) {
                    val mine = refs.filter { it in selectable }
                    item(key = "file-$logIndex") {
                        FileHeader(
                            source = state.sourceOf(logIndex),
                            dives = refs.size,
                            // Nothing to offer when every dive in the file was
                            // already refused.
                            selecting = selecting && mine.isNotEmpty(),
                            allSelected = mine.isNotEmpty() && selection.containsAll(mine),
                            onToggleAll = {
                                onSelection(
                                    if (selection.containsAll(mine)) selection - mine.toSet()
                                    else selection + mine
                                )
                            },
                        )
                    }
                }
                items(refs, key = { "${it.log}:${it.dive}" }) { ref ->
                    val dive = state.dive(ref)
                    if (dive != null) {
                        DiveRow(
                            dive = dive,
                            selected = ref in selection,
                            selecting = selecting,
                            blocked = dive.blockedReason(),
                            onClick = {
                                if (!selecting) onOpen(ref)
                                else onSelection(
                                    if (ref in selection) selection - ref else selection + ref
                                )
                            },
                            // A hold always adds rather than toggling. It is how
                            // a selection is started, and starting one by
                            // removing the dive you held is nonsense.
                            onLongClick = {
                                if (!selecting) onSelecting(true)
                                onSelection(selection + ref)
                            },
                        )
                    }
                }
            }
        }

        if (selecting && selection.isNotEmpty()) {
            SelectionBar(selection.size, onOpenSelection)
        }
    }
}

/** Which file the dives below it came from, and a way to take all of them. */
@Composable
private fun FileHeader(
    source: String,
    dives: Int,
    selecting: Boolean,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 24.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(source, color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(if (dives == 1) "1 dive" else "$dives dives", color = Muted, fontSize = 12.sp)
        }
        // The section circle, exactly as a file manager uses it: a heading is
        // also a handle on everything under it, which is the whole reason the
        // grouping is by file — a file is a trip, or a season.
        if (selecting) {
            SelectionDot(selected = allSelected, onClick = onToggleAll)
        }
    }
}

/**
 * How many dives a batch will draw.
 *
 * A bottom action bar rather than an icon in the contextual top bar. What
 * happens next is not an action on the files — it opens an editor where the
 * slate is still to be designed — and a glyph cannot say that, whereas Files can
 * get away with icons because delete and share need no explaining.
 */
@Composable
private fun SelectionBar(count: Int, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Text(if (count == 1) "Open 1 dive" else "Open $count dives")
        }
    }
}

/**
 * Where a selection stops being routine.
 *
 * Set by what it costs rather than by what breaks: a dozen slates is a trip's
 * worth and finishes while you watch it. Past that, the wait and the pile of
 * gallery entries are both worth saying out loud first.
 */
private const val LARGE_BATCH = 12

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiveRow(
    dive: Dive,
    selected: Boolean,
    selecting: Boolean,
    blocked: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val depth = ceilMetres(dive.computedMaxDepthMetres)
    val (runtime, unit) = formatMinutes(dive.computedDurationSeconds)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            // The selected state is the container, not a tick alone. It is what
            // makes a selection readable while scrolling past it at speed,
            // which a 24dp circle on the far edge is not.
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent
            )
            // A dive that cannot be drawn is refused by the list, not merely
            // skipped by the export. Letting it be picked and dropped later
            // would put the omission in the gallery, where it looks exactly
            // like a dive that was never selected.
            .then(
                if (blocked != null) Modifier
                else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = when {
            blocked != null -> Muted
            selected -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> OnSurface
        }
        val faint =
            if (selected) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
            else Muted

        Column(Modifier.weight(1f)) {
            Text(
                dive.site?.takeIf { it.isNotBlank() } ?: dive.title,
                color = ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Row {
                dive.whenLogged?.let {
                    Text(it.format(LIST_DATE), color = faint, fontSize = 13.sp)
                    Text("   ", fontSize = 13.sp)
                }
                // Depth and runtime: the two numbers that identify a dive at a
                // glance, and the same two the slate leads with.
                Text("$depth m · $runtime $unit".trim(), color = faint, fontSize = 13.sp)
                // Only when a site named the row. Without one the title already
                // falls back to "#9 · 2026-08-16", and repeating the number under
                // it says nothing.
                if (!dive.site.isNullOrBlank()) {
                    dive.number?.let { Text("   #$it", color = faint, fontSize = 13.sp) }
                }
            }
            // Said on the row, in place of a dive that would otherwise have
            // been picked and then quietly not exported.
            if (blocked != null) {
                Text(blocked, color = Caution, fontSize = 12.sp)
            }
        }

        // No circle at all on a dive that cannot be drawn, rather than a
        // greyed one. A dimmed ring still reads as a control, and a control
        // that does nothing when pressed is worse than one that was never
        // offered — the amber line above already says why this row is out.
        if (selecting && blocked == null) {
            Spacer(Modifier.width(12.dp))
            SelectionDot(selected = selected, onClick = onClick)
        }
    }
}

/**
 * The trailing selection circle.
 *
 * Drawn rather than iconified: the outlined-circle glyph lives in the extended
 * icon set, and pulling in several thousand vectors for one ring is not a
 * trade worth making in an APK that is otherwise under two megabytes.
 */
@Composable
private fun SelectionDot(selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.background(scheme.primary)
                else Modifier.border(width = 2.dp, color = scheme.outline, shape = CircleShape)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = scheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private val LIST_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * The slate, and the controls that shape it.
 *
 * [refs] is what the list handed over: one dive in the ordinary case, several
 * when a batch was picked. One settings object covers all of them — the point
 * of a batch is that a trip's slates match — so the only thing the switcher
 * changes is which dive is being looked at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Editor(
    state: LoadState.Loaded,
    refs: List<DiveRef>,
    exports: ExportState,
    onBack: () -> Unit,
    onExport: (ExportRequest) -> Unit,
    onSaveToGallery: (ExportRequest) -> Unit,
) {
    val dives = remember(refs, state.logs) { refs.mapNotNull { state.dive(it) } }

    // The three axes the slate is chosen along, in the order they narrow: the
    // style decides how it is drawn and therefore which palettes exist, the
    // layout decides its proportions, the theme decides its colour.
    val initialStyle = SLATE_STYLES.first()
    var style by remember { mutableStateOf(initialStyle) }
    var layout by remember { mutableStateOf(SlateLayout.WIDE) }
    var theme by remember { mutableStateOf(initialStyle.defaultTheme) }

    var showBackdrop by remember { mutableStateOf(true) }
    var opacity by remember { mutableFloatStateOf(initialStyle.defaultScrimAlpha) }

    var showSite by remember { mutableStateOf(true) }
    var showDate by remember { mutableStateOf(false) }
    var showScrim by remember { mutableStateOf(true) }
    var showCeiling by remember { mutableStateOf(true) }
    var showGas by remember { mutableStateOf(false) }
    // On by default, and it stays wherever the user leaves it.
    //
    // A dive profile is a curve in the water; the polyline is the sampling
    // artefact. So the curve is the truer of the two pictures and leads, and the
    // control is here for a reader who wants every tooth of a sawtooth bottom
    // rather than a line through them. Carried across a style change like the
    // palette's dark/light choice, because it is a statement about how this
    // slate should read and the incoming style knows nothing about it.
    var smooth by remember { mutableStateOf(true) }
    var chosenStats by remember { mutableStateOf(emptySet<String>()) }

    // Which of the selection is on screen. Deliberately outside every setting
    // above it: stepping to the next dive must not disturb a palette or a
    // figure choice, since the whole reason to hold several dives open at once
    // is to settle those choices against all of them.
    var shown by remember(refs) { mutableIntStateOf(0) }

    // An empty selection is rejected before the editor opens, but a crash here
    // would be a blank screen with no way back.
    if (dives.isEmpty()) {
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

    val index = shown.coerceIn(0, dives.lastIndex)
    val dive = dives[index]
    val minOpacity = theme.scrimAlphaMin

    // Built once and used twice: the preview draws it, and the export carries
    // the very same object. Two constructions could drift apart, and a slate
    // that exports differently from the one on screen is the one failure this
    // screen cannot afford.
    val options = remember(
        style, layout, theme, opacity, minOpacity, showScrim, showSite, showDate,
        showCeiling, showGas, smooth, chosenStats,
    ) {
        OverlayOptions(
            style = style,
            layout = layout,
            theme = theme,
            scrimAlpha = opacity.coerceAtLeast(minOpacity),
            showScrim = showScrim,
            showSite = showSite,
            showDate = showDate,
            showCeiling = showCeiling,
            showGas = showGas,
            // Ignored by a style that cannot honour it, which is also the one
            // that hides the control — so the flag can never disagree with what
            // is on screen.
            smoothProfile = smooth && style.supportsSmooth,
            stats = chosenStats.takeIf { it.isNotEmpty() }
                ?.let { picked -> STAT_LABELS.map { it.first }.filter { it in picked } },
        )
    }

    val slate = remember(dive, options) {
        runCatching { renderOverlay(dive, options) }.getOrNull()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            title = {
                Column {
                    Text(dive.title, fontSize = 19.sp, maxLines = 1)
                    if (dives.size > 1) {
                        Text(
                            "Dive ${index + 1} of ${dives.size}",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = OnSurface,
            ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
        Preview(slate = slate, showBackdrop = showBackdrop)

        // Under the thing it pages, the way a carousel control sits under a
        // carousel. In the top bar it would be a long reach from the preview it
        // changes, and the preview is what the user is actually looking at.
        if (dives.size > 1) {
            DiveSwitcher(
                position = index,
                count = dives.size,
                onStep = { step -> shown = (index + step + dives.size) % dives.size },
            )
        }

        if (slate == null) {
            Text(
                "This dive has no depth samples, so there is no profile to draw.",
                color = Muted,
                fontSize = 14.sp,
            )
        }

        // ---- style ----------------------------------------------------------
        // The broadest of the three axes, so it leads: it decides how the slate
        // is drawn and therefore which palettes are even on offer below.
        Label("Style")
        StylePicker(selected = style) { candidate ->
            style = candidate
            // The palette follows the style rather than resetting: the
            // dark/light choice is a statement about the footage this slate
            // will land on, which the new style knows nothing about.
            theme = candidate.adopt(theme)
            opacity = opacity.coerceAtLeast(theme.scrimAlphaMin)
        }

        // ---- layout ---------------------------------------------------------
        Label("Layout")
        // Segmented rather than chips: the layouts are one-of-four and always
        // have been, and a row of filter chips says "any number of these" with
        // its shape while the code says otherwise.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SlateLayout.entries.forEachIndexed { index, candidate ->
                SegmentedButton(
                    selected = candidate == layout,
                    onClick = {
                        layout = candidate
                        // Trim visibly, here, rather than letting the renderer
                        // drop the overflow: the figures deselect in front of
                        // the user, so a narrower badge showing fewer of them is
                        // something they watched happen.
                        chosenStats = chosenStats.trimmedTo(candidate.maxFigures)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, SlateLayout.entries.size),
                    label = { Text(candidate.label, maxLines = 1) },
                )
            }
        }

        // ---- palette --------------------------------------------------------
        // One row, dark palettes then light. Two headings spent a heading and a
        // row gap on a distinction the swatches already make: each is drawn on
        // the surface it was validated against, so the dark ones are dark discs
        // and the light ones are light discs, and the grouping is legible
        // before any label is read.
        //
        // Sorted rather than taken in the style's own order, which interleaves
        // the two for some styles — unsorted, the row was a chequer of light
        // and dark discs where the eye had nothing to group on. The sort is
        // what carries the meaning the two headings used to.
        val palettes = remember(style) {
            style.themes.sortedByDescending { it.isDark }
        }
        Label("Palette")
        PaletteRow(palettes, theme) { picked ->
            theme = picked
            opacity = opacity.coerceAtLeast(picked.scrimAlphaMin)
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

        // ---- elements -------------------------------------------------------
        Label("Elements")
        ChipRow {
            Toggle("Site", showSite) { showSite = it }
            Toggle("Date", showDate) { showDate = it }
            Toggle("Panel", showScrim) { showScrim = it }
            Toggle("Ceiling", showCeiling) { showCeiling = it }
            Toggle("Gas switches", showGas) { showGas = it }
            // Absent rather than greyed on the one style that cannot honour it.
            // The segment screen quantises the profile to one minute and one
            // metre, and a curve through a staircase is a staircase with
            // rounded corners — so the style says it cannot, and the control is
            // not offered. Same rule as a dive row that refuses selection
            // instead of being selected and then dropped.
            if (style.supportsSmooth) {
                Toggle("Smooth curve", smooth) { smooth = it }
            }
        }

        // ---- figures --------------------------------------------------------
        val figureBudget = layout.maxFigures
        Label(
            if (chosenStats.isEmpty()) "Figures — automatic, up to $figureBudget"
            else "Figures — ${chosenStats.size} of $figureBudget"
        )
        ChipRow {
            FilterChip(
                selected = chosenStats.isEmpty(),
                onClick = { chosenStats = emptySet() },
                label = { Text("Auto", fontSize = 12.sp) },
            )
            STAT_LABELS.forEach { (key, label) ->
                val picked = key in chosenStats
                FilterChip(
                    selected = picked,
                    // Spent budget greys out what is left rather than swapping
                    // a figure out from under the user: which two a corner
                    // badge carries is a choice, so it is theirs to unmake.
                    enabled = picked || chosenStats.size < figureBudget,
                    onClick = {
                        chosenStats = if (picked) chosenStats - key else chosenStats + key
                    },
                    label = { Text(label, fontSize = 12.sp) },
                )
            }
        }
        if (chosenStats.isNotEmpty()) {
            Text(
                buildString {
                    append(
                        if (dives.size == 1) "A figure this dive did not record"
                        else "A figure a dive did not record"
                    )
                    append(" is skipped rather than shown blank.")
                    if (chosenStats.size >= figureBudget) {
                        append(
                            " The ${layout.label.lowercase()} layout has room " +
                                "for $figureBudget."
                        )
                    }
                },
                color = Muted,
                fontSize = 12.sp,
            )
        }

        // Which of the chosen figures the rest of the batch cannot supply.
        //
        // Skipping a figure the log never recorded is the right behaviour and
        // it is not in question here. What is, is that these settings were
        // chosen while looking at one dive: without this, picking SAC against a
        // dive that has it would silently produce eleven other slates without
        // it, and a figure missing from a slate looks exactly like a dive that
        // never recorded it — which is the confusion this whole project keeps
        // refusing to let happen unwatched.
        val gaps = remember(dives, chosenStats) { figureGaps(dives, chosenStats) }
        if (gaps.isNotEmpty()) {
            Text(
                "Not every dive here recorded what you picked — " +
                    "${gaps.joinToString(", ")}. Those slates print fewer figures.",
                color = Caution,
                fontSize = 12.sp,
            )
        }

        // ---- export ---------------------------------------------------------
        // Saving leads. The PNG is what this project actually produces, and the
        // gallery is where it stays put; sharing hands the same files to
        // whatever the user picks from the chooser.
        val exportable = dives.filter { it.blockedReason() == null }
        val request = ExportRequest(exportable, options)
        val busy = exports as? ExportState.Running

        if (busy != null) {
            Text(
                "${busy.verb} ${(busy.done + 1).coerceAtMost(busy.total)} of ${busy.total}…",
                color = Muted,
                fontSize = 13.sp,
            )
            LinearProgressIndicator(
                progress = { (busy.done + 1f) / busy.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (exportable.size > LARGE_BATCH) {
            Text(
                "${exportable.size} slates will take a while to draw, and land as " +
                    "${exportable.size} separate images.",
                color = Caution,
                fontSize = 12.sp,
            )
        }

        Button(
            onClick = { onSaveToGallery(request) },
            enabled = exportable.isNotEmpty() && busy == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (exportable.size == 1) "Save to gallery"
                else "Save ${exportable.size} slates to gallery"
            )
        }

        FilledTonalButton(
            onClick = { onExport(request) },
            enabled = exportable.isNotEmpty() && busy == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (exportable.size == 1) "Share" else "Share ${exportable.size} slates")
        }

        // Worth saying before the chooser rather than after it fails there.
        // The receiving app decides how many images it will take, and plenty of
        // them take one; a batch that arrives as a single slate would otherwise
        // look like this app dropped the rest.
        if (exportable.size > 1) {
            Text(
                "Many apps accept only one image at a time — the gallery takes them all.",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        } else {
            Spacer(Modifier.height(12.dp))
        }
        }
    }
}

/**
 * Which style, in one row whatever the count.
 *
 * This was a wrapping row of filter chips, which is the right shape for a
 * handful and the wrong one for nine: it stood three rows tall and pushed the
 * preview — the thing every one of these settings is judged against — off the
 * top of the screen before a single one had been touched. Chips also say "any
 * number of these" with their shape, while a style is one-of-N.
 *
 * A menu rather than a scrolling row of chips. A row that scrolls hides its own
 * tail, which is the objection already recorded on [ChipRow] and is worse here:
 * a style nobody scrolls to is a style nobody knows the app has. The menu costs
 * one extra tap per change and in exchange shows every style at once. Adding a
 * tenth style costs no vertical space at all.
 *
 * Names only. The descriptions were carried here when this stopped being a row
 * of chips, and nine of them wrapped to two lines each — which buried the nine
 * words that actually distinguish the entries in fifty that do not, and made a
 * list of nine short names read as a wall. The preview answers the question a
 * description was answering, and answers it better: pick a style and the slate
 * redraws in it.
 */
@Composable
private fun StylePicker(selected: SlateStyle, onPick: (SlateStyle) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(selected.label, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SLATE_STYLES.forEach { candidate ->
                val current = candidate.id == selected.id
                DropdownMenuItem(
                    onClick = {
                        onPick(candidate)
                        open = false
                    },
                    text = {
                        Text(
                            candidate.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (current) FontWeight.Bold else null,
                        )
                    },
                    // A mark on the current one, because the anchor above shows
                    // only its name and the menu covers the anchor when open.
                    trailingIcon = {
                        if (current) Icon(Icons.Filled.Check, contentDescription = "Selected")
                    },
                )
            }
        }
    }
}

/**
 * Step through the dives a batch holds.
 *
 * Wraps at both ends. This is for flipping back and forth while judging a
 * palette against several dives, not for navigating a list, and a stop at the
 * last one just costs a second gesture to get back to the first.
 *
 * The arrows carry a filled container rather than sitting bare. Bare, they were
 * two thin grey chevrons directly under a preview that is the busiest thing on
 * the screen — the one control a batch cannot be used without, drawn as the
 * faintest marks near it, and reported as invisible. A tonal container is what
 * makes them read as buttons at all; the count between them says which of the
 * two ends is being approached, so the arrows themselves do not have to.
 */
@Composable
private fun DiveSwitcher(position: Int, count: Int, onStep: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FilledTonalIconButton(onClick = { onStep(-1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous dive")
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${position + 1} / $count",
                color = OnSurface,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "Every setting below applies to all $count",
                color = Muted,
                fontSize = 12.sp,
            )
        }
        FilledTonalIconButton(onClick = { onStep(1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next dive")
        }
    }
}

/**
 * Which hand-picked figures are missing from some of the dives, and from how
 * many.
 *
 * Only for a hand-picked set. Automatic figures already adapt per dive — each
 * slate takes the best the log can answer — so there is nothing there for the
 * user to have got wrong.
 */
private fun figureGaps(dives: List<Dive>, chosen: Set<String>): List<String> {
    if (dives.size < 2 || chosen.isEmpty()) return emptyList()
    val available = dives.map { availableStats(it) }
    return STAT_LABELS.filter { it.first in chosen }.mapNotNull { (key, label) ->
        val missing = available.count { key !in it }
        // "of ${dives.size}" rather than a bare count, so the number is read as
        // a share of the batch rather than as a total the user has to place.
        if (missing == 0) null else "$label is missing on $missing of ${dives.size}"
    }
}

/** How much of the preview's width the reference frame occupies. */
private const val CANVAS_FRACTION = 0.86f

/**
 * The preview frame's own proportions, width over height.
 *
 * A constant, so that changing the layout changes the slate and nothing else.
 * It is set by rendering rather than by arithmetic: across every log in
 * `conformance/data` the tallest slate any layout produces is Tall at 812px on
 * the 1080px reference canvas, and at this ratio that lands inside the frame
 * with backdrop still showing around it.
 */
private const val PREVIEW_RATIO = 1.2f

@Composable
private fun Preview(slate: Slate?, showBackdrop: Boolean) {
    // A fixed frame standing in for the shot, rather than a box sized to the
    // slate. Scaling every layout up to the same width made a 400px corner
    // badge and a 1080px full-width strip look alike — the one difference
    // between them that matters is how much of the frame they take, and the
    // preview was the only place it could have been seen.
    Box(Modifier.fillMaxWidth().aspectRatio(PREVIEW_RATIO).clip(RoundedCornerShape(14.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            if (showBackdrop) drawCheckerboard()
            val current = slate ?: return@Canvas

            // Quoted against the reference canvas, so the scale is the same for
            // every layout and each one draws at its own share of it. The
            // second term only ever binds for a slate taller than the frame
            // allows, where shrinking to fit beats cropping the marks off.
            val factor = minOf(
                size.width * CANVAS_FRACTION / SlateLayout.REFERENCE_WIDTH,
                size.height * CANVAS_FRACTION / current.height,
            )
            val left = (size.width - current.width * factor) / 2f
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
    Text(
        text,
        color = Muted,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 4.dp),
    )
}
