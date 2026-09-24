package one.rarebit.heyarr.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import one.rarebit.heyarr.ui.generated.resources.Res
import one.rarebit.heyarr.ui.generated.resources.inter_bold
import one.rarebit.heyarr.ui.generated.resources.inter_medium
import one.rarebit.heyarr.ui.generated.resources.inter_regular
import one.rarebit.heyarr.ui.generated.resources.inter_semibold
import one.rarebit.heyarr.ui.generated.resources.montserrat_bold
import one.rarebit.heyarr.ui.generated.resources.montserrat_extrabold
import one.rarebit.heyarr.ui.generated.resources.montserrat_semibold
import one.rarebit.heyarr.ui.generated.resources.rubik_medium
import one.rarebit.heyarr.ui.generated.resources.rubik_regular
import one.rarebit.heyarr.ui.generated.resources.rubik_semibold
import org.jetbrains.compose.resources.Font

/**
 * The self-hosted type faces (static instances, OFL — licences under
 * composeResources/files/licenses). The TTFs ship ONCE, from this module's composeResources,
 * to both apps: the desktop jar and the android assets.
 *
 * Compose resources load a font inside composition (on JVM and Android the first read is
 * synchronous, so there is no fallback-font flash), which is why each family is a
 * `@Composable` getter rather than a plain value.
 */
object HeyarrFonts {
    /** Inter — UI and body. */
    val inter: FontFamily
        @Composable get() {
            val regular = Font(Res.font.inter_regular, FontWeight.Normal)
            val medium = Font(Res.font.inter_medium, FontWeight.Medium)
            val semiBold = Font(Res.font.inter_semibold, FontWeight.SemiBold)
            val bold = Font(Res.font.inter_bold, FontWeight.Bold)
            return remember(regular, medium, semiBold, bold) { FontFamily(regular, medium, semiBold, bold) }
        }

    /** Rubik — the technical voice: nav captions, rule codes, key/value labels, badges, keyboard hints. */
    val rubik: FontFamily
        @Composable get() {
            val regular = Font(Res.font.rubik_regular, FontWeight.Normal)
            val medium = Font(Res.font.rubik_medium, FontWeight.Medium)
            val semiBold = Font(Res.font.rubik_semibold, FontWeight.SemiBold)
            return remember(regular, medium, semiBold) { FontFamily(regular, medium, semiBold) }
        }

    /** Montserrat — display headings only. */
    val montserrat: FontFamily
        @Composable get() {
            val semiBold = Font(Res.font.montserrat_semibold, FontWeight.SemiBold)
            val bold = Font(Res.font.montserrat_bold, FontWeight.Bold)
            val extraBold = Font(Res.font.montserrat_extrabold, FontWeight.ExtraBold)
            return remember(semiBold, bold, extraBold) { FontFamily(semiBold, bold, extraBold) }
        }
}
