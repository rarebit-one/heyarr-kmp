package one.rarebit.heyarr.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import one.rarebit.heyarr.desktop.theme.RubikFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.mcp.Reason
import one.rarebit.heyarr.desktop.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.Tokens

/** The colour a verdict word gets: pass/bonus in the accent, fail red, miss muted, undetermined gold. */
@Composable
fun verdictColor(result: String): Color = when (result) {
    "pass", "bonus" -> LocalMediaTheme.current.accentGradientEnd
    "fail" -> Tokens.danger
    "undetermined" -> Tokens.ratingGold
    else -> Tokens.textMuted
}

/** The rule code in an uppercase Archive tag; accessibility retains the original code. */
@Composable
fun RuleCode(rule: String, modifier: Modifier = Modifier, tone: Color = Tokens.textPrimary) {
    Box(modifier.semantics { contentDescription = rule }.background(Tokens.surface3, RoundedCornerShape(Tokens.radiusChip)).border(Tokens.hairline, Tokens.border, RoundedCornerShape(Tokens.radiusChip)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(rule.uppercase(), style = MaterialTheme.typography.labelMedium.copy(fontFamily = RubikFamily, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp), color = tone)
    }
}

/**
 * "Why this release": every rule the scorer weighed, one per line — code · section ·
 * verdict · the server's own detail. Nothing is paraphrased; the code and detail are
 * rendered exactly as heyarr sent them, because that wording is the contract.
 */
@Composable
fun ReasonList(reasons: List<Reason>, modifier: Modifier = Modifier, emphasiseFailures: Boolean = true) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (reasons.isEmpty()) Text("No rules reported.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        for (r in reasons) {
            val tone = verdictColor(r.result)
            val strong = emphasiseFailures && r.isFailure
            Row(
                Modifier.fillMaxWidth()
                    .background(if (strong) Tokens.danger.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .semantics { this.contentDescription = "Rule ${r.rule}, ${r.section}, ${r.result}. ${r.detail}" },
                verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.padding(top = 6.dp).size(7.dp).background(tone, CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RuleCode(r.rule, tone = if (strong) Tokens.danger else Tokens.textPrimary)
                        Text(r.section, style = MaterialTheme.typography.labelSmall, color = Tokens.textDisabled)
                        Text(r.result, style = MaterialTheme.typography.labelMedium, color = tone)
                        if (r.score != null && r.score != 0) Text("+${r.score}", style = MaterialTheme.typography.labelSmall, color = tone)
                    }
                    if (r.detail.isNotBlank()) Text(r.detail, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
                }
            }
        }
    }
}

/** A one-line summary chip row of the failures only (for a candidate row). */
@Composable
fun RejectedBy(reasons: List<Reason>, modifier: Modifier = Modifier) {
    if (reasons.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("rejected by", style = MaterialTheme.typography.labelSmall, color = Tokens.danger)
        for (r in reasons.take(3)) RuleCode(r.rule, tone = Tokens.danger)
        if (reasons.size > 3) Text("+${reasons.size - 3}", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
    }
}

/** A small panel with a hairline and title, for the detail screen's side sections. */
@Composable
fun Panel(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Tokens.surface1, RoundedCornerShape(Tokens.radiusCard)).border(Tokens.hairline, Tokens.border, RoundedCornerShape(Tokens.radiusCard)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Tokens.textPrimary, modifier = Modifier.weight(1f))
            if (trailing != null) trailing()
        }
        content()
    }
}

/** Label/value pair for metadata tables. */
@Composable
fun KeyValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Tokens.textPrimary) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Tokens.textMuted, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor, modifier = Modifier.weight(1f))
    }
}
