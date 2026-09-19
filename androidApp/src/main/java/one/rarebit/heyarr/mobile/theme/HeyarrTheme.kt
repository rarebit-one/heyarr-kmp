package one.rarebit.heyarr.mobile.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import one.rarebit.heyarr.mobile.R

/** The media theme in force for this subtree — cards, buttons and pressed states read it. */
val LocalMediaTheme = compositionLocalOf { MediaThemes.default }

/** Appearance preferences (Settings → Appearance). */
data class Appearance(val adaptiveAccents: Boolean = true, val reduceMotion: Boolean = false)

val LocalAppearance = staticCompositionLocalOf { Appearance() }

/** Inter — UI and body. Self-hosted static instances (OFL), see res/font. */
val InterFamily: FontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Rubik — the technical voice: nav captions, rule codes, key/value labels, badges. Self-hosted (OFL). */
val RubikFamily: FontFamily = FontFamily(
    Font(R.font.rubik_regular, FontWeight.Normal),
    Font(R.font.rubik_medium, FontWeight.Medium),
    Font(R.font.rubik_semibold, FontWeight.SemiBold),
)

/** Montserrat — display headings only. */
val MontserratFamily: FontFamily = FontFamily(
    Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold),
    Font(R.font.montserrat_extrabold, FontWeight.ExtraBold),
)

/**
 * The H1–H6 + Body-XS→XL ramp mapped onto Material's slots so every Material component
 * picks the right face without per-call styling:
 * display* = H1/H2, headline* = H3/H4, title* = H5/H6 (Inter, semibold), body* = Body L/M/S,
 * label* = Body-XS/S (chips, captions). The two small label slots are the technical
 * voice — Rubik — so chips, badges, key/value labels and captions read as
 * instrumentation, not prose.
 */
val HeyarrTypography: Typography by lazy {
    val display = RubikFamily
    val body = RubikFamily
    Typography(
        displayLarge = TextStyle(fontFamily = display, fontSize = Tokens.Type.h1, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
        displayMedium = TextStyle(fontFamily = display, fontSize = Tokens.Type.h2, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
        displaySmall = TextStyle(fontFamily = display, fontSize = Tokens.Type.h3, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
        headlineLarge = TextStyle(fontFamily = display, fontSize = Tokens.Type.h3, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
        headlineMedium = TextStyle(fontFamily = display, fontSize = Tokens.Type.h4, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
        headlineSmall = TextStyle(fontFamily = display, fontSize = Tokens.Type.h5, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp, letterSpacing = 0.2.sp),
        titleLarge = TextStyle(fontFamily = body, fontSize = Tokens.Type.h4, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
        titleMedium = TextStyle(fontFamily = body, fontSize = Tokens.Type.h5, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
        titleSmall = TextStyle(fontFamily = body, fontSize = Tokens.Type.h6, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = body, fontSize = Tokens.Type.bodyL, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = body, fontSize = Tokens.Type.bodyM, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
        bodySmall = TextStyle(fontFamily = body, fontSize = Tokens.Type.bodyS, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
        labelLarge = TextStyle(fontFamily = body, fontSize = Tokens.Type.bodyM, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = RubikFamily, fontSize = Tokens.Type.bodyS, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.2.sp),
        labelSmall = TextStyle(fontFamily = RubikFamily, fontSize = Tokens.Type.bodyXs, fontWeight = FontWeight.Medium, lineHeight = 14.sp, letterSpacing = 0.4.sp),
    )
}

val HeyarrShapes = Shapes(
    extraSmall = RoundedCornerShape(Tokens.radiusChip),
    small = RoundedCornerShape(Tokens.radiusButton),
    medium = RoundedCornerShape(Tokens.radiusInput),
    large = RoundedCornerShape(Tokens.radiusCard),
    extraLarge = RoundedCornerShape(Tokens.radiusCard),
)

/**
 * The app theme: constant dark surfaces + text from [Tokens], with the accent slots
 * driven by the [media] theme in force. Wrap a subtree in a different [media] to re-skin
 * it (a series card inside a movie rail, a book detail screen…); the surfaces stay put,
 * so the whole reads as one app.
 */
@Composable
fun HeyarrTheme(media: MediaTheme = MediaThemes.default, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
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
    CompositionLocalProvider(LocalMediaTheme provides media) {
        MaterialTheme(colorScheme = scheme, typography = HeyarrTypography, shapes = HeyarrShapes) {
            // No Material Surface wraps the app, so pin the default text colour ourselves —
            // otherwise unstyled Text (the sign-in screen) inherits Compose's black.
            CompositionLocalProvider(LocalContentColor provides Tokens.textPrimary, content = content)
        }
    }
}

/** Re-skin a subtree for one media [type] — honouring the "adaptive accents" preference. */
@Composable
fun MediaScope(type: MediaType, content: @Composable () -> Unit) {
    val adaptive = LocalAppearance.current.adaptiveAccents
    val theme = if (adaptive) MediaThemes.of(type) else MediaThemes.default
    if (theme == LocalMediaTheme.current) content() else HeyarrTheme(theme, content)
}

/** Shorthand: the accent in force. */
val accentColor: Color
    @Composable get() = LocalMediaTheme.current.accent
