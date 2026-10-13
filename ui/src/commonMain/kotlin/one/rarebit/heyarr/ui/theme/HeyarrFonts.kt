package one.rarebit.heyarr.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import one.rarebit.heyarr.ui.generated.resources.Res
import one.rarebit.heyarr.ui.generated.resources.rubik_medium
import one.rarebit.heyarr.ui.generated.resources.rubik_regular
import one.rarebit.heyarr.ui.generated.resources.rubik_semibold
import one.rarebit.heyarr.ui.generated.resources.source_serif_regular
import one.rarebit.heyarr.ui.generated.resources.source_serif_semibold
import org.jetbrains.compose.resources.Font

/**
 * The self-hosted type faces (static instances, OFL — licences under
 * composeResources/files/licenses). The TTFs ship ONCE, from this module's composeResources,
 * to both apps: the desktop jar and the android assets. Rubik is every interface slot (see
 * `heyarrTypography`); Source Serif 4 (the SmText optical size, cut for body reading) is the
 * reader's face only. It ships so long-form text looks the same on every platform instead of
 * falling back to whatever `FontFamily.Serif` resolves to.
 *
 * Compose resources load a font inside composition (on JVM and Android the first read is
 * synchronous, so there is no fallback-font flash), which is why each family is a
 * `@Composable` getter rather than a plain value.
 */
object HeyarrFonts {
    /** Rubik — every type slot, and the technical voice: nav captions, rule codes, key/value labels, badges. */
    val rubik: FontFamily
        @Composable get() {
            val regular = Font(Res.font.rubik_regular, FontWeight.Normal)
            val medium = Font(Res.font.rubik_medium, FontWeight.Medium)
            val semiBold = Font(Res.font.rubik_semibold, FontWeight.SemiBold)
            return remember(regular, medium, semiBold) { FontFamily(regular, medium, semiBold) }
        }

    /** Source Serif 4 — long-form reading surfaces only, never chrome. */
    val serif: FontFamily
        @Composable get() {
            val regular = Font(Res.font.source_serif_regular, FontWeight.Normal)
            val semiBold = Font(Res.font.source_serif_semibold, FontWeight.SemiBold)
            return remember(regular, semiBold) { FontFamily(regular, semiBold) }
        }
}
