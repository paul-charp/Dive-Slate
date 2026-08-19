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
     * For a style that paints no card this is an assumption about the footage
     * and is never painted; for one that paints its own it is that card, which
     * is the surface the marks are genuinely read against. Either way a swatch
     * has to show it, because a palette for dark footage and one for a pale
     * background are not interchangeable and nothing else about the colours
     * says which is which.
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
    val accent: Long,
    /**
     * Second stop of a two-stop panel, equal to [scrim] where the panel is flat.
     *
     * The optional tokens below are resolved at design time rather than left
     * null, so a style asking a palette for something it does not have gets a
     * defined answer — a transparent edge paints nothing — instead of every
     * style inventing its own fallback.
     */
    val surfaceTint: Long,
    /** Panel border, bezel or rule. Fully transparent where there is none. */
    val surfaceEdge: Long,
    /** Ink for a style's ornament: sparkles, microcopy, legend, contours. */
    val ornament: Long,
    /** Far stop of a gradient curve, equal to [curve] where it is flat. */
    val curveEnd: Long,
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
    /**
     * Which gates this palette cleared — see `tools/palette.py`.
     *
     * `"chromatic"` and `"expressive"` mean the marks were proved distinct by
     * *colour*, at CVD and normal-vision delta-E floors. `"monochrome"` means
     * they were not, because the palette is one ink on purpose: what tells the
     * curve from the ceiling there is dash and stroke width, and that claim is
     * checked in `SlateStyleTest` rather than in Python, since form is not
     * something a colour gate can measure.
     */
    val paletteProfile: String,
) {
    val isDark: Boolean get() = mode == "dark"

    /** Whether this palette's marks are told apart by shape rather than hue. */
    val separatesByForm: Boolean get() = paletteProfile == "monochrome"
}

/**
 * The deco ceiling's red, as the Modern style paints it in every palette.
 *
 * It was once fixed for every style there was, on the argument that a hazard
 * colour which shifts with the theme stops reading as a hazard. That argument
 * still holds where the hazard is carried by colour — which is why Modern's
 * nine all use this value, and why the styles that keep a red keep this one.
 *
 * What changed is that it is no longer the only way to carry a hazard. A white
 * dashed step over a hatch on a violet card is not a red mark, and it is not
 * ambiguous either: the hatch says region-to-avoid and the dash says boundary,
 * and neither depends on hue. So a style may re-colour the ceiling, and the
 * palettes that do had the substitute *measured* against the card they land on
 * — a white ceiling on the yellow card was rejected at 1.43:1 and reverted to
 * a red. What is not allowed is dropping the hatch or the dash, because that is
 * where the meaning went.
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
    accent = 0xFF018D74,
    surfaceTint = 0x9E080C12,
    surfaceEdge = 0x00000000,
    ornament = 0xFF018D74,
    curveEnd = 0xFF0078CF,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.62f,
    scrimAlphaMin = 0.561f,
    paletteProfile = "chromatic",
)

val FROSTED_MIST: SlateTheme = SlateTheme(
    name = "frosted-mist",
    mode = "light",
    assumedSurface = 0xFFF7F9FC,
    ink = 0xFF10161C,
    inkSecondary = 0xDB10161C,
    inkMuted = 0xA810161C,
    halo = 0xB2FFFFFF,
    grid = 0x1F10161C,
    axis = 0x4710161C,
    scrim = 0xBDFAFCFF,
    curve = 0xFF10161C,
    curveFillTop = 0x2910161C,
    curveFillBottom = 0x0510161C,
    ceiling = 0xFF10161C,
    accent = 0xFF10161C,
    surfaceTint = 0xFFFFFFFF,
    surfaceEdge = 0xF2FFFFFF,
    ornament = 0xBF10161C,
    curveEnd = 0xFF10161C,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.74f,
    scrimAlphaMin = 0.502f,
    paletteProfile = "monochrome",
)

val FROSTED_SMOKE: SlateTheme = SlateTheme(
    name = "frosted-smoke",
    mode = "dark",
    assumedSurface = 0xFF12181F,
    ink = 0xFFFFFFFF,
    inkSecondary = 0xDBFFFFFF,
    inkMuted = 0xA8FFFFFF,
    halo = 0x8C000000,
    grid = 0x1FFFFFFF,
    axis = 0x47FFFFFF,
    scrim = 0x9E10161E,
    curve = 0xFFFFFFFF,
    curveFillTop = 0x3DFFFFFF,
    curveFillBottom = 0x05FFFFFF,
    ceiling = 0xFFFFFFFF,
    accent = 0xFFFFFFFF,
    surfaceTint = 0xFF2C3F52,
    surfaceEdge = 0x80FFFFFF,
    ornament = 0xCCFFFFFF,
    curveEnd = 0xFFFFFFFF,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.62f,
    scrimAlphaMin = 0.584f,
    paletteProfile = "monochrome",
)

val HOLO_CYAN: SlateTheme = SlateTheme(
    name = "holo-cyan",
    mode = "dark",
    assumedSurface = 0xFF0C1E2E,
    ink = 0xFFEAFCFF,
    inkSecondary = 0xEB6EE7FF,
    inkMuted = 0xB86EE7FF,
    halo = 0x8C000000,
    grid = 0x296EE7FF,
    axis = 0x666EE7FF,
    scrim = 0xD10A1C2C,
    curve = 0xFF6EE7FF,
    curveFillTop = 0x386EE7FF,
    curveFillBottom = 0x006EE7FF,
    ceiling = 0xFFFFB86B,
    accent = 0xFFC39BFF,
    surfaceTint = 0xFF081828,
    surfaceEdge = 0x8C6EE7FF,
    ornament = 0xCC6EE7FF,
    curveEnd = 0xFF6EE7FF,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.82f,
    scrimAlphaMin = 0.611f,
    paletteProfile = "expressive",
)

val HOLO_DAYLIGHT: SlateTheme = SlateTheme(
    name = "holo-daylight",
    mode = "light",
    assumedSurface = 0xFFEAF2F7,
    ink = 0xFF0C2836,
    inkSecondary = 0xFF155066,
    inkMuted = 0xFF4A7186,
    halo = 0xB2FFFFFF,
    grid = 0x2400708F,
    axis = 0x5900708F,
    scrim = 0xE6E8F2F8,
    curve = 0xFF00708F,
    curveFillTop = 0x3300708F,
    curveFillBottom = 0x0000708F,
    ceiling = 0xFFB4530A,
    accent = 0xFF7A3FB8,
    surfaceTint = 0xFFD6E7F0,
    surfaceEdge = 0x8000708F,
    ornament = 0xCC00708F,
    curveEnd = 0xFF00708F,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.9f,
    scrimAlphaMin = 0.58f,
    paletteProfile = "expressive",
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
    accent = 0xFF7E6CD9,
    surfaceTint = 0xC2FCFCFB,
    surfaceEdge = 0x00000000,
    ornament = 0xFF7E6CD9,
    curveEnd = 0xFF08C8D8,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.76f,
    scrimAlphaMin = 0.479f,
    paletteProfile = "chromatic",
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
    accent = 0xFF915BC2,
    surfaceTint = 0x9E080C12,
    surfaceEdge = 0x00000000,
    ornament = 0xFF915BC2,
    curveEnd = 0xFF09A5B3,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.62f,
    scrimAlphaMin = 0.561f,
    paletteProfile = "chromatic",
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
    accent = 0xFFEDA100,
    surfaceTint = 0xC2FCFCFB,
    surfaceEdge = 0x00000000,
    ornament = 0xFFEDA100,
    curveEnd = 0xFF2A78D6,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.76f,
    scrimAlphaMin = 0.479f,
    paletteProfile = "chromatic",
)

val MAGAZINE_BLACK: SlateTheme = SlateTheme(
    name = "magazine-black",
    mode = "light",
    assumedSurface = 0xFFFCFCFB,
    ink = 0xFF0B0B0B,
    inkSecondary = 0xDB0B0B0B,
    inkMuted = 0xA80B0B0B,
    halo = 0xB2FFFFFF,
    grid = 0x1F0B0B0B,
    axis = 0x470B0B0B,
    scrim = 0xC7FCFCFB,
    curve = 0xFF0B0B0B,
    curveFillTop = 0x000B0B0B,
    curveFillBottom = 0x000B0B0B,
    ceiling = 0xFF0B0B0B,
    accent = 0xFF0B0B0B,
    surfaceTint = 0xC7FCFCFB,
    surfaceEdge = 0xF20B0B0B,
    ornament = 0xFF0B0B0B,
    curveEnd = 0xFF0B0B0B,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.78f,
    scrimAlphaMin = 0.479f,
    paletteProfile = "monochrome",
)

val MAGAZINE_WHITE: SlateTheme = SlateTheme(
    name = "magazine-white",
    mode = "dark",
    assumedSurface = 0xFF1A1A19,
    ink = 0xFFFFFFFF,
    inkSecondary = 0xDBFFFFFF,
    inkMuted = 0xA8FFFFFF,
    halo = 0x8C000000,
    grid = 0x1FFFFFFF,
    axis = 0x47FFFFFF,
    scrim = 0x9E080A0E,
    curve = 0xFFFFFFFF,
    curveFillTop = 0x00FFFFFF,
    curveFillBottom = 0x00FFFFFF,
    ceiling = 0xFFFFFFFF,
    accent = 0xFFFFFFFF,
    surfaceTint = 0x9E080A0E,
    surfaceEdge = 0xF2FFFFFF,
    ornament = 0xFFFFFFFF,
    curveEnd = 0xFFFFFFFF,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.62f,
    scrimAlphaMin = 0.558f,
    paletteProfile = "monochrome",
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
    accent = 0xFF008C82,
    surfaceTint = 0x9E080C12,
    surfaceEdge = 0x00000000,
    ornament = 0xFF008C82,
    curveEnd = 0xFF813C9D,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.62f,
    scrimAlphaMin = 0.561f,
    paletteProfile = "chromatic",
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
    accent = 0xFFA05FC4,
    surfaceTint = 0xC2FCFCFB,
    surfaceEdge = 0x00000000,
    ornament = 0xFFA05FC4,
    curveEnd = 0xFF05A9F7,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.76f,
    scrimAlphaMin = 0.479f,
    paletteProfile = "chromatic",
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
    accent = 0xFF8061CD,
    surfaceTint = 0x9E080C12,
    surfaceEdge = 0x00000000,
    ornament = 0xFF8061CD,
    curveEnd = 0xFF07A99C,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.62f,
    scrimAlphaMin = 0.561f,
    paletteProfile = "chromatic",
)

val RETRO_AMBER: SlateTheme = SlateTheme(
    name = "retro-amber",
    mode = "dark",
    assumedSurface = 0xFF1E1608,
    ink = 0xFFFFB347,
    inkSecondary = 0xE0FFB347,
    inkMuted = 0xADFFB347,
    halo = 0x00000000,
    grid = 0x1FFFB347,
    axis = 0x47FFB347,
    scrim = 0xFF1E1608,
    curve = 0xFFFFB347,
    curveFillTop = 0x3DFFB347,
    curveFillBottom = 0x3DFFB347,
    ceiling = 0xFFFFB347,
    accent = 0xFFFFB347,
    surfaceTint = 0xFF150F04,
    surfaceEdge = 0xFF2A2318,
    ornament = 0xCCFFB347,
    curveEnd = 0xFFFFB347,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 1.0f,
    scrimAlphaMin = 0.754f,
    paletteProfile = "monochrome",
)

val RETRO_EL: SlateTheme = SlateTheme(
    name = "retro-el",
    mode = "dark",
    assumedSurface = 0xFF07202C,
    ink = 0xFF7FF0FF,
    inkSecondary = 0xE07FF0FF,
    inkMuted = 0xA87FF0FF,
    halo = 0x00000000,
    grid = 0x1F7FF0FF,
    axis = 0x477FF0FF,
    scrim = 0xFF07202C,
    curve = 0xFF7FF0FF,
    curveFillTop = 0x387FF0FF,
    curveFillBottom = 0x387FF0FF,
    ceiling = 0xFF7FF0FF,
    accent = 0xFF7FF0FF,
    surfaceTint = 0xFF04141C,
    surfaceEdge = 0xFF10222C,
    ornament = 0xCC7FF0FF,
    curveEnd = 0xFF7FF0FF,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 1.0f,
    scrimAlphaMin = 0.685f,
    paletteProfile = "monochrome",
)

val RETRO_LCD: SlateTheme = SlateTheme(
    name = "retro-lcd",
    mode = "light",
    assumedSurface = 0xFFB0BC9C,
    ink = 0xFF1C241C,
    inkSecondary = 0xE01C241C,
    inkMuted = 0xD93A4536,
    halo = 0x00000000,
    grid = 0x1F1C241C,
    axis = 0x471C241C,
    scrim = 0xFFB0BC9C,
    curve = 0xFF1C241C,
    curveFillTop = 0x331C241C,
    curveFillBottom = 0x331C241C,
    ceiling = 0xFF1C241C,
    accent = 0xFF1C241C,
    surfaceTint = 0xFFA9B596,
    surfaceEdge = 0xFF2E3230,
    ornament = 0xCC1C241C,
    curveEnd = 0xFF1C241C,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 1.0f,
    scrimAlphaMin = 0.743f,
    paletteProfile = "monochrome",
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
    accent = 0xFFC98500,
    surfaceTint = 0x9E080C12,
    surfaceEdge = 0x00000000,
    ornament = 0xFFC98500,
    curveEnd = 0xFF3987E5,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.62f,
    scrimAlphaMin = 0.561f,
    paletteProfile = "chromatic",
)

val STICKER_DAY: SlateTheme = SlateTheme(
    name = "sticker-day",
    mode = "light",
    assumedSurface = 0xFFFFFFFF,
    ink = 0xFF262626,
    inkSecondary = 0xFF5A5A5A,
    inkMuted = 0xFF8E8E8E,
    halo = 0x00000000,
    grid = 0x1F262626,
    axis = 0x47262626,
    scrim = 0xFFFFFFFF,
    curve = 0xFFE8730F,
    curveFillTop = 0x33D62976,
    curveFillBottom = 0x00D62976,
    ceiling = 0xFFD62976,
    accent = 0xFF0C879E,
    surfaceTint = 0xFFFFFFFF,
    surfaceEdge = 0xFFE9E4EE,
    ornament = 0xFFD62976,
    curveEnd = 0xFF4F5BD5,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 1.0f,
    scrimAlphaMin = 0.548f,
    paletteProfile = "expressive",
)

val STICKER_NIGHT: SlateTheme = SlateTheme(
    name = "sticker-night",
    mode = "dark",
    assumedSurface = 0xFF15151C,
    ink = 0xFFF6F6F8,
    inkSecondary = 0xD1F6F6F8,
    inkMuted = 0x99F6F6F8,
    halo = 0x00000000,
    grid = 0x1FF6F6F8,
    axis = 0x47F6F6F8,
    scrim = 0xFF15151C,
    curve = 0xFFFF9D4D,
    curveFillTop = 0x42FF5F9E,
    curveFillBottom = 0x00FF5F9E,
    ceiling = 0xFFFF5F9E,
    accent = 0xFF5B882E,
    surfaceTint = 0xFF15151C,
    surfaceEdge = 0xFF2C2C38,
    ornament = 0xFFFF5F9E,
    curveEnd = 0xFF7C8BFF,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 1.0f,
    scrimAlphaMin = 0.606f,
    paletteProfile = "expressive",
)

val TOPO_BLUEPRINT: SlateTheme = SlateTheme(
    name = "topo-blueprint",
    mode = "dark",
    assumedSurface = 0xFF0F3050,
    ink = 0xFFDFEAF6,
    inkSecondary = 0xD6DFEAF6,
    inkMuted = 0x9EDFEAF6,
    halo = 0x00000000,
    grid = 0x29DFEAF6,
    axis = 0x57DFEAF6,
    scrim = 0xFF0F3050,
    curve = 0xFF8FD0FF,
    curveFillTop = 0x338FD0FF,
    curveFillBottom = 0x338FD0FF,
    ceiling = 0xFFFF8F7A,
    accent = 0xFFFFD166,
    surfaceTint = 0xFF0B2540,
    surfaceEdge = 0x66DFEAF6,
    ornament = 0x6BDFEAF6,
    curveEnd = 0xFF8FD0FF,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 1.0f,
    scrimAlphaMin = 0.713f,
    paletteProfile = "expressive",
)

val TOPO_FIELD: SlateTheme = SlateTheme(
    name = "topo-field",
    mode = "light",
    assumedSurface = 0xFFECE7D3,
    ink = 0xFF4A3B28,
    inkSecondary = 0xFF6B5738,
    inkMuted = 0xFF8A7A55,
    halo = 0x00000000,
    grid = 0x2E4A3B28,
    axis = 0x594A3B28,
    scrim = 0xFFECE7D3,
    curve = 0xFF33546B,
    curveFillTop = 0x2E5A8CAA,
    curveFillBottom = 0x2E5A8CAA,
    ceiling = 0xFFB23A2E,
    accent = 0xFF915BC2,
    surfaceTint = 0xFFE5DFC7,
    surfaceEdge = 0xFFB5A880,
    ornament = 0xFF7A643C,
    curveEnd = 0xFF33546B,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 1.0f,
    scrimAlphaMin = 0.726f,
    paletteProfile = "expressive",
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
    accent = 0xFF008C82,
    surfaceTint = 0x9E080C12,
    surfaceEdge = 0x00000000,
    ornament = 0xFF008C82,
    curveEnd = 0xFF5B4CB6,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 0.62f,
    scrimAlphaMin = 0.561f,
    paletteProfile = "chromatic",
)

val WRAPPED_SOLAR: SlateTheme = SlateTheme(
    name = "wrapped-solar",
    mode = "light",
    assumedSurface = 0xFFFFD400,
    ink = 0xFF141414,
    inkSecondary = 0xDB141414,
    inkMuted = 0xA8141414,
    halo = 0x00000000,
    grid = 0x1F141414,
    axis = 0x47141414,
    scrim = 0xFFFFD400,
    curve = 0xFF141414,
    curveFillTop = 0xFF1B3BD6,
    curveFillBottom = 0xFF1B3BD6,
    ceiling = 0xFFD1002B,
    accent = 0xFF00766D,
    surfaceTint = 0xFFFFD400,
    surfaceEdge = 0x00000000,
    ornament = 0xFF1B3BD6,
    curveEnd = 0xFF141414,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 1.0f,
    scrimAlphaMin = 0.583f,
    paletteProfile = "expressive",
)

val WRAPPED_VIOLET: SlateTheme = SlateTheme(
    name = "wrapped-violet",
    mode = "dark",
    assumedSurface = 0xFF6528F7,
    ink = 0xFFD2F34C,
    inkSecondary = 0xEBFFFFFF,
    inkMuted = 0xC7FFFFFF,
    halo = 0x00000000,
    grid = 0x1FD2F34C,
    axis = 0x47D2F34C,
    scrim = 0xFF6528F7,
    curve = 0xFFD2F34C,
    curveFillTop = 0xFFFF6EC7,
    curveFillBottom = 0xFFFF6EC7,
    ceiling = 0xFFFFFFFF,
    accent = 0xFFFF9C3F,
    surfaceTint = 0xFF6528F7,
    surfaceEdge = 0x00000000,
    ornament = 0xFFFF6EC7,
    curveEnd = 0xFFD2F34C,
    fontSize = 13.0f,
    titleSize = 20.0f,
    labelSize = 11.5f,
    scrimAlphaNominal = 1.0f,
    scrimAlphaMin = 0.913f,
    paletteProfile = "expressive",
)

/** Every palette the Modern style offers, default first. */
val SLATE_THEMES: List<SlateTheme> = listOf(SLATE, LIGHT, ABYSS, INK, LAGOON, ORCHID, PAPER, REEF, TWILIGHT)

/** The frosted style's palettes, default first. */
val FROSTED_THEMES: List<SlateTheme> = listOf(FROSTED_SMOKE, FROSTED_MIST)

/** The holo style's palettes, default first. */
val HOLO_THEMES: List<SlateTheme> = listOf(HOLO_CYAN, HOLO_DAYLIGHT)

/** The magazine style's palettes, default first. */
val MAGAZINE_THEMES: List<SlateTheme> = listOf(MAGAZINE_WHITE, MAGAZINE_BLACK)

/** The retro style's palettes, default first. */
val RETRO_THEMES: List<SlateTheme> = listOf(RETRO_LCD, RETRO_AMBER, RETRO_EL)

/** The sticker style's palettes, default first. */
val STICKER_THEMES: List<SlateTheme> = listOf(STICKER_DAY, STICKER_NIGHT)

/** The topo style's palettes, default first. */
val TOPO_THEMES: List<SlateTheme> = listOf(TOPO_FIELD, TOPO_BLUEPRINT)

/** The wrapped style's palettes, default first. */
val WRAPPED_THEMES: List<SlateTheme> = listOf(WRAPPED_VIOLET, WRAPPED_SOLAR)
