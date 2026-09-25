package one.rarebit.heyarr.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import one.rarebit.heyarr.ui.theme.Tokens

/**
 * A modal over the whole window: a dimmed scrim that dismisses on a click, with [content]
 * centred in a box sized by [panelModifier] that swallows its own clicks so they never
 * reach the scrim. The desktop's sheets (connection, want, sign-in) all sit in one.
 */
@Composable
fun ModalScrim(onDismiss: () -> Unit, panelModifier: Modifier, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Tokens.bgBase.copy(alpha = 0.7f)).clickable(
            interactionSource = remember {
                MutableInteractionSource()
            },
            indication = null,
            onClick = onDismiss,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            panelModifier.clickable(
                interactionSource = remember {
                    MutableInteractionSource()
                },
                indication = null,
                onClick = {},
            ),
        ) {
            content()
        }
    }
}
