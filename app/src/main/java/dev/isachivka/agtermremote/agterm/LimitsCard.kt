package dev.isachivka.agtermremote.agterm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.isachivka.agtermremote.R
import dev.isachivka.agtermremote.ui.AppIcons
import dev.isachivka.agtermremote.ui.theme.AppTheme
import kotlinx.coroutines.delay

const val TAG_LIMITS_CARD = "agterm_limits_card"
const val TAG_LIMITS_REFRESH = "agterm_limits_refresh"

/** The LazyColumn key of the card. Not a UUID shape, so it can never be a session's. */
internal const val LIMITS_ITEM_KEY = "limits-card"

/**
 * Whether the card has anything to be: an answer with at least one provider in it.
 *
 * An older bridge, or a Mac where neither CLI is signed in, draws nothing rather than an empty box.
 */
internal fun limitsCardShown(state: LimitsState): Boolean =
    !state.unsupported && state.known && state.providers.isNotEmpty()

/**
 * The laptop's Claude Code and Codex limits, under the session list.
 *
 * One card in the list's own style. Every window is a row: its name, a bar, how much is used and
 * how long until it resets. The header says how old the numbers are, and its button asks the
 * bridge past its cache.
 */
@Composable
internal fun LimitsCard(state: LimitsState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    // A clock for the "ago" and "resets in" text, ticking once a minute: the numbers are minutes
    // old by design, and a label that stops moving reads as a frozen screen.
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(30_000)
        }
    }
    val oldest = state.providers.filter { it.fetchedAt > 0 }.minOfOrNull { it.fetchedAt }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .clip(LimitsShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 14.dp)
            .testTag(TAG_LIMITS_CARD),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.agterm_limits_title),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = TerminalFontFamily),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (oldest != null) {
                Text(
                    text = stringResource(R.string.agterm_limits_age, LimitsFormat.age(oldest, now)),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.colors.onSurfaceSubtle,
                )
            }
            IconButton(
                onClick = onRefresh,
                enabled = !state.loading,
                modifier = Modifier.testTag(TAG_LIMITS_REFRESH),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        painter = painterResource(AppIcons.Refresh),
                        contentDescription = stringResource(R.string.agterm_limits_refresh),
                        tint = AppTheme.colors.onSurfaceSubtle,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        state.providers.forEach { provider ->
            Column(
                modifier = Modifier.padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProviderHeading(provider)
                provider.windows.forEach { WindowRow(it, now) }
                if (provider.error.isNotEmpty()) {
                    Text(
                        text = provider.error,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderHeading(provider: LimitsProvider) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = when (provider.name) {
                "claude" -> stringResource(R.string.agterm_limits_claude)
                "codex" -> stringResource(R.string.agterm_limits_codex)
                else -> provider.name
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (provider.plan.isNotEmpty()) {
            Text(
                text = provider.plan,
                style = MaterialTheme.typography.labelMedium,
                color = AppTheme.colors.onSurfaceSubtle,
            )
        }
    }
}

@Composable
private fun WindowRow(window: LimitWindow, now: Long) {
    val color = when (LimitLevel.of(window.usedPercent)) {
        LimitLevel.Calm -> AppTheme.colors.success
        LimitLevel.Warm -> MaterialTheme.colorScheme.primary
        LimitLevel.Hot -> MaterialTheme.colorScheme.error
    }
    val label = MaterialTheme.typography.labelMedium.copy(fontFamily = TerminalFontFamily)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = window.label,
            style = label,
            color = AppTheme.colors.onSurfaceSubtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(84.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { (window.usedPercent / 100).toFloat().coerceIn(0f, 1f) },
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
        }
        Text(
            text = LimitsFormat.percent(window.usedPercent),
            style = label,
            color = if (LimitLevel.of(window.usedPercent) == LimitLevel.Calm) MaterialTheme.colorScheme.onSurface else color,
            modifier = Modifier.width(40.dp),
        )
        Text(
            text = LimitsFormat.until(window.resetsAt, now) ?: "",
            style = label,
            color = AppTheme.colors.onSurfaceSubtle,
            maxLines = 1,
            modifier = Modifier.width(56.dp),
        )
    }
}

private val LimitsShape = RoundedCornerShape(24.dp)
