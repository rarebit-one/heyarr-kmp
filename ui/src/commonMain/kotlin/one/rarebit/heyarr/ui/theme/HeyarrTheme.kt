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
 * The H1–H6 + Body-XS→XL sizes the type ramp is built from. The desktop's compact scale is
 * [Tokens.Type]; the phone sets its own (larger body, smaller display) — see `:androidApp`'s
 * `theme/Tokens`.
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

private fun heyarrTypography(rubik: FontFamily, t: TypeScale): Typography {
    val display = rubik
    val body = rubik
    val semi = FontWeight.SemiBold
    return Typography(
        displayLarge = TextStyle(
            fontFamily = display,
            fontSize = t.h1,
            fontWeight = semi,
            lineHeight = 34.sp,
            letterSpacing = (-0.5).sp,
        ),
        displayMedium = TextStyle(
            fontFamily = display,
            fontSize = t.h2,
            fontWeight = semi,
            lineHeight = 28.sp,
            letterSpacing = (-0.3).sp,
        ),
        displaySmall = TextStyle(fontFamily = display, fontSize = t.h3, fontWeight = semi, lineHeight = 28.sp),
        headlineLarge = TextStyle(fontFamily = display, fontSize = t.h3, fontWeight = semi, lineHeight = 28.sp),
        headlineMedium = TextStyle(fontFamily = display, fontSize = t.h4, fontWeight = semi, lineHeight = 26.sp),
        headlineSmall = TextStyle(
            fontFamily = display,
            fontSize = t.h5,
            fontWeight = semi,
            lineHeight = 22.sp,
            letterSpacing = 0.2.sp,
        ),
        titleLarge = TextStyle(fontFamily = body, fontSize = t.h4, fontWeight = semi, lineHeight = 26.sp),
        titleMedium = TextStyle(fontFamily = body, fontSize = t.h5, fontWeight = semi, lineHeight = 22.sp),
        titleSmall = TextStyle(fontFamily = body, fontSize = t.h6, fontWeight = semi, lineHeight = 20.sp),
        bodyLarge = TextStyle(
            fontFamily = body,
            fontSize = t.bodyL,
            fontWeight = FontWeight.Normal,
            lineHeight = 24.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = body,
            fontSize = t.bodyM,
            fontWeight = FontWeight.Normal,
            lineHeight = 20.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = body,
            fontSize = t.bodyS,
            fontWeight = FontWeight.Normal,
            lineHeight = 16.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = body,
            fontSize = t.bodyM,
            fontWeight = FontWeight.Medium,
            lineHeight = 20.sp,
        ),
        // The two small label slots are the technical voice — Rubik — so chips, badges,
        // key/value labels and captions read as instrumentation, not prose.
        labelMedium = TextStyle(
            fontFamily = rubik,
            fontSize = t.bodyS,
            fontWeight = FontWeight.Medium,
            lineHeight = 16.sp,
            letterSpacing = 0.2.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = rubik,
            fontSize = t.bodyXs,
            fontWeight = FontWeight.Medium,
            lineHeight = 14.sp,
            letterSpacing = 0.4.sp,
        ),
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
