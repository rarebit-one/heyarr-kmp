package one.rarebit.heyarr.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import one.rarebit.heyarr.ui.generated.resources.Res
import one.rarebit.heyarr.ui.generated.resources.rubik_medium
import one.rarebit.heyarr.ui.generated.resources.rubik_regular
import one.rarebit.heyarr.ui.generated.resources.rubik_semibold
import org.jetbrains.compose.resources.Font

/**
 * The self-hosted type faces (static instances, OFL — licences under
 * composeResources/files/licenses). The TTFs ship ONCE, from this module's composeResources,
 * to both apps: the desktop jar and the android assets. Only Rubik ships: every type slot
 * uses it (see `heyarrTypography`), so the unused Inter and Montserrat faces were dropped
 * rather than carried as dead weight in both apps.
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
}
