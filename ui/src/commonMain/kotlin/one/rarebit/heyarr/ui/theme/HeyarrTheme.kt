package one.rarebit.heyarr.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import one.rarebit.heyarr.core.theme.MediaType

/** The media theme in force for this subtree — cards, buttons and focus rings read it. */
val LocalMediaTheme = compositionLocalOf { MediaThemes.default }

/** Appearance preferences (Settings → Appearance). */
data class Appearance(val adaptiveAccents: Boolean = true, val reduceMotion: Boolean = false)

val LocalAppearance = staticCompositionLocalOf { Appearance() }

/**
 * The Display + H1–H6 + Body-XS→XL sizes the type ramp is built from. The desktop's scale is
 * [Tokens.Type]; the phone sets its own (larger body, smaller display) — see `:androidApp`'s
 * `theme/Tokens`. [display] is for hero and detail titles only; it defaults to [h1] so a scale
 * that predates it keeps its look.
 */
@Immutable
data class TypeScale(
    val h1: TextUnit,
    val h2: TextUnit,
    val h3: TextUnit,
    val h4: TextUnit,
    val h5: TextUnit,
    val h6: TextUnit,
    val bodyL: TextUnit,
    val bodyM: TextUnit,
    val bodyS: TextUnit,
    val bodyXs: TextUnit,
    val display: TextUnit = h1,
) {
    companion object {
        /** The desktop's compact scale, straight from [Tokens.Type]. */
        val Desktop = TypeScale(
            h1 = Tokens.Type.h1,
            h2 = Tokens.Type.h2,
            h3 = Tokens.Type.h3,
            h4 = Tokens.Type.h4,
            h5 = Tokens.Type.h5,
            h6 = Tokens.Type.h6,
            bodyL = Tokens.Type.bodyL,
            bodyM = Tokens.Type.bodyM,
            bodyS = Tokens.Type.bodyS,
            bodyXs = Tokens.Type.bodyXs,
            display = Tokens.Type.display,
        )
    }
}

/**
 * What differs between the two apps that share this design layer. The desktop is driven by
 * a pointer: hover states, compact controls. The phone is driven by touch: pressed states
 * instead of hover, 44 dp minimum targets, TalkBack live regions, and a default text colour
 * pinned under the theme (no Material `Surface` wraps the phone's screens, so unstyled
 * `Text` would otherwise inherit Compose's black). Each app hands its own to [HeyarrTheme]
 * at the root; [MediaScope] carries it into every re-skinned subtree.
 */
@Immutable
data class HeyarrPlatform(
    /** Touch (phone) rather than pointer (desktop) conventions in the shared components. */
    val touch: Boolean,
    val typeScale: TypeScale,
    /** Provide [Tokens.textPrimary] as the default content colour under the theme. */
    val pinContentColor: Boolean,
) {
    companion object {
        val Desktop = HeyarrPlatform(touch = false, typeScale = TypeScale.Desktop, pinContentColor = false)
    }
}

val LocalHeyarrPlatform = staticCompositionLocalOf { HeyarrPlatform.Desktop }

/**
 * The H1–H6 + Body-XS→XL ramp mapped onto Material's slots so every Material component
 * picks the right face without per-call styling:
 * display* = H1/H2, headline* = H3/H4, title* = H5/H6 (semibold), body* = Body L/M/S,
 * label* = Body-XS/S (chips, captions). Every slot is Rubik today.
 */
@Composable
fun rememberHeyarrTypography(scale: TypeScale): Typography {
    val rubik = HeyarrFonts.rubik
    return remember(rubik, scale) { heyarrTypography(rubik, scale) }
}

@Suppress("MagicNumber") // Leading ratios and tracking are the type ramp's design values.
private fun heyarrTypography(rubik: FontFamily, t: TypeScale): Typography {
    val semi = FontWeight.SemiBold
    val medium = FontWeight.Medium

    // Display steps are tight-leaded and slightly negatively tracked, like the mockups' titles;
    // body steps keep a comfortable 1.4–1.6 leading. Labels are the technical voice: tracked so
    // the uppercase button, chip and badge strings read as instrumentation.
    fun heading(size: TextUnit, leading: Float, tracking: TextUnit = 0.sp) = TextStyle(
        fontFamily = rubik,
        fontSize = size,
        fontWeight = semi,
        lineHeight = size * leading,
        letterSpacing = tracking,
    )

    fun body(size: TextUnit, leading: Float) =
        TextStyle(fontFamily = rubik, fontSize = size, fontWeight = FontWeight.Normal, lineHeight = size * leading)

    fun label(size: TextUnit, leading: Float, tracking: TextUnit) = TextStyle(
        fontFamily = rubik,
        fontSize = size,
        fontWeight = medium,
        lineHeight = size * leading,
        letterSpacing = tracking,
    )

    return Typography(
        displayLarge = heading(t.display, 1.05f, (-0.8).sp),
        displayMedium = heading(t.h1, 1.12f, (-0.4).sp),
        displaySmall = heading(t.h2, 1.2f, (-0.2).sp),
        headlineLarge = heading(t.h2, 1.2f, (-0.2).sp),
        headlineMedium = heading(t.h3, 1.25f),
        headlineSmall = heading(t.h4, 1.3f),
        titleLarge = heading(t.h4, 1.3f),
        titleMedium = heading(t.h5, 1.35f),
        titleSmall = heading(t.h6, 1.4f),
        bodyLarge = body(t.bodyL, 1.6f),
        bodyMedium = body(t.bodyM, 1.45f),
        bodySmall = body(t.bodyS, 1.4f),
        labelLarge = label(t.bodyM, 1.4f, 0.9.sp),
        labelMedium = label(t.bodyS, 1.35f, 0.6.sp),
        labelSmall = label(t.bodyXs, 1.3f, 1.0.sp),
    )
}

/**
 * Styles Material's slots don't cover: the tracked uppercase eyebrow that heads a hero or a
 * section ("FEATURED DISCOVERY", "RECENTLY CATALOGUED"). Apply it only to fixed UI strings —
 * never uppercase a title, a person or a device name.
 */
object HeyarrTextStyles {
    val eyebrow: TextStyle
        @Composable get() = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Medium,
            letterSpacing = Tokens.Type.labelTracking,
        )

    val sectionLabel: TextStyle
        @Composable get() = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = Tokens.Type.labelTracking,
        )
}

val HeyarrShapes = Shapes(
    extraSmall = RoundedCornerShape(Tokens.radiusChip),
    small = RoundedCornerShape(Tokens.radiusButton),
    medium = RoundedCornerShape(Tokens.radiusInput),
    large = RoundedCornerShape(Tokens.radiusCard),
    extraLarge = RoundedCornerShape(Tokens.radiusCard),
)

/** Constant dark surfaces + text from [Tokens], with the accent slots driven by [media]. */
fun heyarrColorScheme(media: MediaTheme): ColorScheme = darkColorScheme(
    primary = media.accent,
    onPrimary = media.onAccent,
    primaryContainer = media.tint(0.18f),
    onPrimaryContainer = Tokens.textPrimary,
    secondary = media.accentHover,
    onSecondary = media.onAccent,
    secondaryContainer = Tokens.surface2,
    onSecondaryContainer = Tokens.textPrimary,
    tertiary = Tokens.ratingGold,
    background = Tokens.bgBase,
    onBackground = Tokens.textPrimary,
    surface = Tokens.surface1,
    onSurface = Tokens.textPrimary,
    surfaceVariant = Tokens.surface2,
    onSurfaceVariant = Tokens.textMuted,
    surfaceContainer = Tokens.surface1,
    surfaceContainerHigh = Tokens.surface2,
    surfaceContainerHighest = Tokens.surface3,
    surfaceContainerLow = Tokens.surface1,
    surfaceContainerLowest = Tokens.bgBase,
    inverseSurface = Tokens.textPrimary,
    inverseOnSurface = Tokens.bgBase,
    outline = Tokens.border,
    outlineVariant = Tokens.border,
    error = Tokens.danger,
    onError = Tokens.textPrimary,
    errorContainer = Tokens.danger.copy(alpha = 0.18f),
    onErrorContainer = Tokens.textPrimary,
    scrim = Tokens.bgBase,
)

/**
 * The app theme: constant dark surfaces + text from [Tokens], with the accent slots
 * driven by the [media] theme in force. Wrap a subtree in a different [media] to re-skin
 * it (a series card inside a movie rail, a book detail screen…); the surfaces stay put,
 * so the whole reads as one app. [platform] is the app's own ([HeyarrPlatform]); nested
 * calls inherit the one in force.
 */
@Composable
fun HeyarrTheme(
    media: MediaTheme = MediaThemes.default,
    platform: HeyarrPlatform = LocalHeyarrPlatform.current,
    content: @Composable () -> Unit,
) {
    val scheme = heyarrColorScheme(media)
    val typography = rememberHeyarrTypography(platform.typeScale)
    CompositionLocalProvider(LocalMediaTheme provides media, LocalHeyarrPlatform provides platform) {
        if (platform.pinContentColor) {
            MaterialTheme(colorScheme = scheme, typography = typography, shapes = HeyarrShapes) {
                CompositionLocalProvider(LocalContentColor provides Tokens.textPrimary, content = content)
            }
        } else {
            MaterialTheme(colorScheme = scheme, typography = typography, shapes = HeyarrShapes, content = content)
        }
    }
}

/** Re-skin a subtree for one media [type] — honouring the "adaptive accents" preference. */
@Composable
fun MediaScope(type: MediaType, content: @Composable () -> Unit) {
    val adaptive = LocalAppearance.current.adaptiveAccents
    val theme = if (adaptive) MediaThemes.of(type) else MediaThemes.default
    if (theme == LocalMediaTheme.current) content() else HeyarrTheme(theme, content = content)
}

/** Shorthand: the accent in force. */
val accentColor: Color
    @Composable get() = LocalMediaTheme.current.accent
