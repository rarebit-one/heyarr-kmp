package one.rarebit.heyarr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.ui.theme.HeyarrTextStyles
import one.rarebit.heyarr.ui.theme.Tokens

/**
 * A row of tracked uppercase tabs over a dashed rule: the active one in mint with a short bar
 * under it, the rest muted. No boxes, so a tab strip reads as part of the page, not a control panel.
 * The row scrolls sideways rather than squeezing a tab out of reach.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun UnderlineTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        // Scrolls sideways when the labels outgrow the pane (a narrow window, a large UI scale).
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            labels.forEachIndexed { i, label ->
                val active = i == selected
                val interaction = remember { MutableInteractionSource() }
                Column(
                    Modifier.focusRing(interaction, RectangleShape)
                        .clickable(interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(i) }
                        .semantics {
                            contentDescription = label
                            this.selected = active
                        }
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        label.uppercase(),
                        style = HeyarrTextStyles.sectionLabel,
                        color = if (active) Tokens.accent else Tokens.textMuted,
                    )
                    Box(Modifier.width(24.dp).height(2.dp).background(if (active) Tokens.accent else Color.Transparent))
                }
            }
        }
        DashedDivider()
    }
}
