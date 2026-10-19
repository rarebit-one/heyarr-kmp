package one.rarebit.heyarr.ui.theme

import androidx.compose.ui.graphics.Color
import one.rarebit.heyarr.core.theme.MediaType

/**
 * What a desktop says about its colours, normalised. Each Linux source fills what it knows:
 * Omarchy's `colors.toml` fills everything, a Plasma colour scheme fills the ground, text and
 * its semantic roles, a pywal/wallust palette fills the ground and ANSI hues, and the XDG
 * portal (GNOME, Plasma, COSMIC, and Omarchy via its gsettings hook) gives only light/dark
 * and perhaps an accent. Absent fields fall back to [Palette.Archive] (or its light twin on
 * paper, when a source asks for light but names no ground), so a sparse source re-tints
 * heyarr rather than re-building it.
 *
 * The hue slots are named for the ANSI colours terminal themes ship, because that is the one
 * vocabulary every one of those sources shares.
 */
data class SystemColors(
    /** True dark, false light, null when the source has no opinion. Only consulted when no ground is named. */
    val dark: Boolean? = null,
    val background: Color? = null,
    val foreground: Color? = null,
    val accent: Color? = null,
    /** The desktop's selection fill; becomes [Palette.fern] (filled tracks, selected rows). */
    val selection: Color? = null,
    val red: Color? = null,
    val orange: Color? = null,
    val yellow: Color? = null,
    val green: Color? = null,
    val cyan: Color? = null,
    val blue: Color? = null,
    val magenta: Color? = null,
) {
    /** Whether this source names its own ground and text, so heyarr's surfaces can be rebuilt from them. */
    val suppliesSurfaces: Boolean get() = background != null && foreground != null
}

/**
 * Build a full palette from what the desktop said. Every colour is clamped to the invariants
 * [Palette] documents, nudging lightness only, so a theme whose muted text is too dim to read
 * (Omarchy's osaka-jade muted sits at 2.9:1 on its ground) still yields AA text, in its own hue.
 *
 * Two steps. First the ground: a source that names a ground and text rebuilds the surfaces,
 * text and slate from them, dark or light; a source that only says "light" gets Archive's own
 * light twin, its reading paper and ink; anything else keeps Archive's forest. Then the accents
 * and semantic colours are re-tinted onto that ground. A light palette also carries a dark
 * [Palette.stage] (Archive's ground in the same accents) for the player.
 */
fun Palette.Companion.derive(seed: SystemColors, base: Palette = Archive): Palette {
    val ground = when {
        seed.suppliesSurfaces -> base.withGround(seed.background!!, seed.foreground!!)
        seed.dark == false -> base.withGround(base.readingPaper, base.readingInk)
        else -> base
    }
    val palette = Retint(ground, rebuilt = ground !== base).apply(seed)
    return if (palette.dark ||
        !base.dark
    ) {
        palette
    } else {
        palette.copy(darkStage = Retint(base, rebuilt = false).apply(seed))
    }
}

/** The direction "more contrast" moves in on a ground: lighter on a dark one, darker on a light one. */
private fun away(dark: Boolean): Color = if (dark) Color.White else Color.Black

/** Surfaces, text and slate from a theme's own ground and text colours, dark or light. */
@Suppress("MagicNumber") // The ramp steps are design values, tuned against the Archive palette.
private fun Palette.withGround(bg: Color, foreground: Color): Palette {
    val dark = Contrast.luminance(bg) < Contrast.luminance(foreground)
    val away = away(dark)
    // Readable text first, so a ground and text that sit too close (a murky theme) still give
    // the surface ramp below a real distance to climb.
    val fg = Contrast.atLeast(foreground, listOf(bg), Contrast.AA_TEXT, away)
    // The surface ramp steps from the ground toward the text, the way Archive's does
    // (Forest Black → Raised Panel → …): lighter panels on a dark theme, deeper ones on a
    // light theme, four distinct quiet steps either way.
    val surface1 = Contrast.mix(bg, fg, 0.04f)
    val surface2 = Contrast.mix(bg, fg, 0.07f)
    val surface3 = Contrast.mix(bg, fg, 0.105f)
    val grounds = listOf(bg, surface1, surface2, surface3)
    val textPrimary = Contrast.atLeast(fg, grounds, Contrast.AA_TEXT, away)
    val slate = Contrast.atLeast(Contrast.mix(fg, bg, 0.36f), listOf(bg), Contrast.AA_TEXT, textPrimary)
    return copy(
        bgBase = bg,
        surface1 = surface1,
        surface2 = surface2,
        surface3 = surface3,
        border = Contrast.mix(bg, fg, 0.18f),
        textPrimary = textPrimary,
        textMuted = Contrast.atLeast(Contrast.mix(fg, bg, 0.32f), grounds, Contrast.AA_TEXT, textPrimary),
        textDisabled = Contrast.mix(fg, bg, 0.55f),
        cream = textPrimary,
        slate = slate,
        slateHover = Contrast.atLeast(
            Contrast.mix(slate, textPrimary, 0.4f),
            listOf(surface3),
            Contrast.AA_MARK,
            away,
        ),
        slateGradEnd = Contrast.mix(slate, away, 0.6f),
        dark = dark,
    )
}

/**
 * Accents and semantic colours on [ground]. A hue the source names is clamped onto the ground;
 * a hue it doesn't keeps [ground]'s own — re-clamped when the ground was [rebuilt] (Archive's
 * mint was tuned for Forest Black, not for this theme's ground), untouched otherwise.
 */
@Suppress("MagicNumber") // The ramp steps are design values, tuned against the Archive palette.
private class Retint(private val ground: Palette, private val rebuilt: Boolean) {
    private val away = away(ground.dark)
    private val grounds = listOf(ground.bgBase, ground.surface1, ground.surface2, ground.surface3)

    private fun text(c: Color) = Contrast.atLeast(c, grounds, Contrast.AA_TEXT, away)

    private fun textOr(hue: Color?, own: Color) = when {
        hue != null -> text(hue)
        rebuilt -> text(own)
        else -> own
    }

    // An accent is a fill under a ground-coloured label (PrimaryButton, the CTA pill), so it
    // must clear AA against the ground: lightened on a dark ground, deepened on a light one.
    // Hover and the gradient's far end step further the same way, so the label only gains.
    private fun ramp(c: Color): MediaAccent {
        val accent = Contrast.atLeast(c, listOf(ground.bgBase), Contrast.AA_TEXT, away)
        val hover = Contrast.atLeast(
            Contrast.mix(accent, away, 0.2f),
            listOf(ground.surface3),
            Contrast.AA_MARK,
            away,
        )
        return MediaAccent(accent, hover, Contrast.mix(accent, away, 0.42f))
    }

    private fun rampOr(hue: Color?, own: MediaAccent) = when {
        hue != null -> ramp(hue)
        rebuilt -> ramp(own.accent)
        else -> own
    }

    fun apply(seed: SystemColors): Palette {
        // With no accent and no new ground, Archive's hand-tuned ramp stands as is.
        val own = MediaAccent(ground.accent, ground.accentHover, ground.accentGradEnd)
        val primary = rampOr(seed.accent, own)
        val warmth = seed.orange ?: seed.yellow

        // Each media type wears the ANSI hue closest to its Archive accent: movies the
        // desktop's own accent, series cyan, books the warm tone, audiobooks green, podcasts
        // magenta, music blue.
        fun media(type: MediaType, hue: Color?) = type to rampOr(hue, ground.mediaAccent(type))

        return ground.copy(
            ochre = textOr(warmth, ground.ochre),
            danger = textOr(seed.red, ground.danger),
            success = textOr(seed.green, ground.success),
            accent = primary.accent,
            accentHover = primary.hover,
            accentGradEnd = primary.gradientEnd,
            fern = seed.selection
                ?: if (primary == own) ground.fern else Contrast.mix(primary.accent, ground.bgBase, 0.55f),
            media = mapOf(
                MediaType.MOVIE to primary,
                media(MediaType.SERIES, seed.cyan),
                media(MediaType.BOOK, warmth),
                media(MediaType.AUDIOBOOK, seed.green),
                media(MediaType.PODCAST, seed.magenta),
                media(MediaType.MUSIC, seed.blue),
            ),
        )
    }
}
