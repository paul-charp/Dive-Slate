package io.github.paulcharp.diveslate.core

// GENERATED — do not edit by hand.
// Source: conformance/themes.json, via tools/generate_kotlin_themes.py
//
// The palette gates (OKLab delta-E, Machado CVD simulation at severity 1.0,
// chroma floor, lightness band, WCAG contrast) run once in Python at design
// time. Their conclusions are baked here, so none of that maths ships to a
// phone and no colour decision is left to be made at runtime.

/**
 * Colour and type tokens for one palette.
 *
 * Colours are packed ARGB (`0xAARRGGBB`). Alpha is carried in the value rather
 * than applied separately, because several tokens are deliberately translucent
 * — the fills, the halo, the scrim — and splitting that out invites painting
 * one of them opaque by accident.
 */
data class SlateTheme(
    val name: String,
    val mode: String,
    /**
     * The background this palette was validated against.
     *
     * Never painted — the output is transparent — but a swatch has to show it,
     * because a palette for dark footage and one for a pale background are not
     * interchangeable and nothing else about the colours says which is which.
     */
    val assumedSurface: Long,
    val ink: Long,
    val inkSecondary: Long,
    val inkMuted: Long,
    val halo: Long,
    val grid: Long,
    val axis: Long,
    val scrim: Long,
    val curve: Long,
    val curveFillTop: Long,
    val curveFillBottom: Long,
    val ceiling: Long,
    val ceilingFill: Long,
    val accent: Long,
    val fontSize: Float,
    val titleSize: Float,
    val labelSize: Float,
    /** The scrim opacity this palette was designed around. */
    val scrimAlphaNominal: Float,
    /**
     * Least scrim opacity at which ink still clears 4.5:1 against the worst
     * possible backdrop. The opacity slider clamps here: below it the panel has
     * stopped doing its job and the halo is carrying the text alone, which is
     * not enough over video.
     */
    val scrimAlphaMin: Float,
) {
    val isDark: Boolean get() = mode == "dark"
}

/**
 * The deco ceiling's red, fixed across every palette on purpose: a hazard
 * colour that shifts with the theme stops reading as a hazard.
 */
const val CEILING_ARGB: Long = 0xFFD03B3B

val ABYSS: SlateTheme = SlateTheme(
    name = "abyss",
    mode = "dark",
    assumedSurface = 0xFF1A1A19,
    ink = 0xFFFFFFFF,
    inkSecondary = 0xFFC3C2B7,
    inkMuted = 0xFF898781,
    halo = 0x8C000000,
    grid = 0x1AFFFFFF,
    axis = 0x38FFFFFF,
    scrim = 0x9E080C12,
    curve = 0xFF0078CF,
    curveFillTop = 0x610078CF,
    curveFillBottom = 0x0D0078CF,
    ceiling = 0xFFD03B3B,
    ceilingFill = 0x38D03B3B,
    accent = 0xFF018D74,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.62f,
    scrimAlphaMin = 0.561f,
)

val INK: SlateTheme = SlateTheme(
    name = "ink",
    mode = "light",
    assumedSurface = 0xFFFCFCFB,
    ink = 0xFF0B0B0B,
    inkSecondary = 0xFF52514E,
    inkMuted = 0xFF898781,
    halo = 0xB2FFFFFF,
    grid = 0x1A0B0B0B,
    axis = 0x400B0B0B,
    scrim = 0xC2FCFCFB,
    curve = 0xFF08C8D8,
    curveFillTop = 0x4C08C8D8,
    curveFillBottom = 0x0A08C8D8,
    ceiling = 0xFFD03B3B,
    ceilingFill = 0x2ED03B3B,
    accent = 0xFF7E6CD9,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.76f,
    scrimAlphaMin = 0.479f,
)

val LAGOON: SlateTheme = SlateTheme(
    name = "lagoon",
    mode = "dark",
    assumedSurface = 0xFF1A1A19,
    ink = 0xFFFFFFFF,
    inkSecondary = 0xFFC3C2B7,
    inkMuted = 0xFF898781,
    halo = 0x8C000000,
    grid = 0x1AFFFFFF,
    axis = 0x38FFFFFF,
    scrim = 0x9E080C12,
    curve = 0xFF09A5B3,
    curveFillTop = 0x6109A5B3,
    curveFillBottom = 0x0D09A5B3,
    ceiling = 0xFFD03B3B,
    ceilingFill = 0x38D03B3B,
    accent = 0xFF915BC2,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.62f,
    scrimAlphaMin = 0.561f,
)

val LIGHT: SlateTheme = SlateTheme(
    name = "light",
    mode = "light",
    assumedSurface = 0xFFFCFCFB,
    ink = 0xFF0B0B0B,
    inkSecondary = 0xFF52514E,
    inkMuted = 0xFF898781,
    halo = 0xB2FFFFFF,
    grid = 0x1A0B0B0B,
    axis = 0x400B0B0B,
    scrim = 0xC2FCFCFB,
    curve = 0xFF2A78D6,
    curveFillTop = 0x4C2A78D6,
    curveFillBottom = 0x0A2A78D6,
    ceiling = 0xFFD03B3B,
    ceilingFill = 0x2ED03B3B,
    accent = 0xFFEDA100,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.76f,
    scrimAlphaMin = 0.479f,
)

val ORCHID: SlateTheme = SlateTheme(
    name = "orchid",
    mode = "dark",
    assumedSurface = 0xFF1A1A19,
    ink = 0xFFFFFFFF,
    inkSecondary = 0xFFC3C2B7,
    inkMuted = 0xFF898781,
    halo = 0x8C000000,
    grid = 0x1AFFFFFF,
    axis = 0x38FFFFFF,
    scrim = 0x9E080C12,
    curve = 0xFF813C9D,
    curveFillTop = 0x61813C9D,
    curveFillBottom = 0x0D813C9D,
    ceiling = 0xFFD03B3B,
    ceilingFill = 0x38D03B3B,
    accent = 0xFF008C82,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.62f,
    scrimAlphaMin = 0.561f,
)

val PAPER: SlateTheme = SlateTheme(
    name = "paper",
    mode = "light",
    assumedSurface = 0xFFFCFCFB,
    ink = 0xFF0B0B0B,
    inkSecondary = 0xFF52514E,
    inkMuted = 0xFF898781,
    halo = 0xB2FFFFFF,
    grid = 0x1A0B0B0B,
    axis = 0x400B0B0B,
    scrim = 0xC2FCFCFB,
    curve = 0xFF05A9F7,
    curveFillTop = 0x4C05A9F7,
    curveFillBottom = 0x0A05A9F7,
    ceiling = 0xFFD03B3B,
    ceilingFill = 0x2ED03B3B,
    accent = 0xFFA05FC4,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.76f,
    scrimAlphaMin = 0.479f,
)

val REEF: SlateTheme = SlateTheme(
    name = "reef",
    mode = "dark",
    assumedSurface = 0xFF1A1A19,
    ink = 0xFFFFFFFF,
    inkSecondary = 0xFFC3C2B7,
    inkMuted = 0xFF898781,
    halo = 0x8C000000,
    grid = 0x1AFFFFFF,
    axis = 0x38FFFFFF,
    scrim = 0x9E080C12,
    curve = 0xFF07A99C,
    curveFillTop = 0x6107A99C,
    curveFillBottom = 0x0D07A99C,
    ceiling = 0xFFD03B3B,
    ceilingFill = 0x38D03B3B,
    accent = 0xFF8061CD,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.62f,
    scrimAlphaMin = 0.561f,
)

val SLATE: SlateTheme = SlateTheme(
    name = "slate",
    mode = "dark",
    assumedSurface = 0xFF1A1A19,
    ink = 0xFFFFFFFF,
    inkSecondary = 0xFFC3C2B7,
    inkMuted = 0xFF898781,
    halo = 0x8C000000,
    grid = 0x1AFFFFFF,
    axis = 0x38FFFFFF,
    scrim = 0x9E080C12,
    curve = 0xFF3987E5,
    curveFillTop = 0x613987E5,
    curveFillBottom = 0x0D3987E5,
    ceiling = 0xFFD03B3B,
    ceilingFill = 0x38D03B3B,
    accent = 0xFFC98500,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.62f,
    scrimAlphaMin = 0.561f,
)

val TWILIGHT: SlateTheme = SlateTheme(
    name = "twilight",
    mode = "dark",
    assumedSurface = 0xFF1A1A19,
    ink = 0xFFFFFFFF,
    inkSecondary = 0xFFC3C2B7,
    inkMuted = 0xFF898781,
    halo = 0x8C000000,
    grid = 0x1AFFFFFF,
    axis = 0x38FFFFFF,
    scrim = 0x9E080C12,
    curve = 0xFF5B4CB6,
    curveFillTop = 0x615B4CB6,
    curveFillBottom = 0x0D5B4CB6,
    ceiling = 0xFFD03B3B,
    ceilingFill = 0x38D03B3B,
    accent = 0xFF008C82,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.62f,
    scrimAlphaMin = 0.561f,
)

/** Every palette, default first. */
val SLATE_THEMES: List<SlateTheme> = listOf(SLATE, LIGHT, ABYSS, INK, LAGOON, ORCHID, PAPER, REEF, TWILIGHT)

fun slateTheme(name: String): SlateTheme =
    SLATE_THEMES.firstOrNull { it.name == name }
        ?: throw IllegalArgumentException(
            "unknown theme $name; available: " + SLATE_THEMES.joinToString { it.name }
        )

/**
 * Hues a dark-mode colour control may offer: 175-215, 260-330 degrees.
 *
 * Not every hue that passes the gates — every hue whose whole
 * neighbourhood passes, so the control cannot land next to a cliff.
 * The control indexes this list rather than mapping its travel onto
 * degrees, which makes an excluded band unreachable rather than
 * merely discouraged.
 */
val SAFE_HUES_DARK: IntArray = intArrayOf(175, 180, 185, 190, 195, 200, 205, 210, 215, 260, 265, 270, 275, 280, 285, 290, 295, 300, 305, 310, 315, 320, 325, 330)

/**
 * Hues a light-mode colour control may offer: 70-105, 170-230, 265-350 degrees.
 *
 * Not every hue that passes the gates — every hue whose whole
 * neighbourhood passes, so the control cannot land next to a cliff.
 * The control indexes this list rather than mapping its travel onto
 * degrees, which makes an excluded band unreachable rather than
 * merely discouraged.
 */
val SAFE_HUES_LIGHT: IntArray = intArrayOf(70, 75, 80, 85, 90, 95, 100, 105, 170, 175, 180, 185, 190, 195, 200, 205, 210, 215, 220, 225, 230, 265, 270, 275, 280, 285, 290, 295, 300, 305, 310, 315, 320, 325, 330, 335, 340, 345, 350)
