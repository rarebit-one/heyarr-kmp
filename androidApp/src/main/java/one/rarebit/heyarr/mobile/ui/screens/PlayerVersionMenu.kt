package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.playback.VideoSession
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.theme.LocalMediaTheme

/**
 * The film's copies as a menu (heyarr-kmp#225): the button names the copy playing
 * (`1080p`), the menu lists every copy held with its picture and size, and a pick is
 * re-planned from the current position and remembered for this work on this phone.
 * Hidden unless the work holds two or more copies — with one there is nothing to choose.
 */
@Suppress("FunctionNaming") // a composable is PascalCase by Compose convention
@Composable
internal fun VersionMenu(video: VideoSession) {
    val item = video.current ?: return
    if (item.copies.size < 2) return
    var open by remember { mutableStateOf(false) }
    Box {
        SecondaryButton(
            item.copy?.picture ?: "Version",
            { open = !open },
            icon = Icons.Rounded.HighQuality,
            compact = true,
            contentDescription = "Version: ${item.copy?.label ?: "unknown"}",
        )
        DropdownMenu(expanded = open, onDismissRequest = {
            open = false
        }, modifier = Modifier.background(Tokens.surface3)) {
            Text(
                "Version",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.textMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            for (copy in item.copies) {
                val selected = copy.asset.id == item.assetId
                DropdownMenuItem(
                    text = {
                        Text(
                            copy.label,
                            color = if (selected) LocalMediaTheme.current.accentGradientEnd else Tokens.textPrimary,
                        )
                    },
                    onClick = {
                        open = false
                        if (!selected) video.onSwitchCopy(copy)
                    },
                )
            }
        }
    }
}
