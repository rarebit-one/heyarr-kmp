package one.rarebit.heyarr.mobile.theme

import androidx.compose.runtime.Composable
import one.rarebit.heyarr.ui.theme.HeyarrPlatform
import one.rarebit.heyarr.ui.theme.MediaTheme
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.ui.theme.TypeScale

/**
 * The phone's side of `:ui`'s shared design layer: the type ramp from this app's own
 * [Tokens.Type] (larger body, smaller display than the desktop), touch conventions in the
 * shared components (pressed states, 44 dp targets, TalkBack live regions) and the default
 * text colour pinned under the theme (no Material `Surface` wraps the app, so unstyled `Text`
 * — the sign-in screen — would otherwise inherit Compose's black).
 */
val PhonePlatform = HeyarrPlatform(
    touch = true,
    typeScale = TypeScale(
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
    ),
    pinContentColor = true,
)

/** The app theme (see `:ui`'s `HeyarrTheme`) with the phone's [PhonePlatform]. */
@Composable
fun HeyarrTheme(media: MediaTheme = MediaThemes.default, content: @Composable () -> Unit) {
    one.rarebit.heyarr.ui.theme.HeyarrTheme(media, PhonePlatform, content)
}
