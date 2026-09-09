package dev.isachivka.bewareofsugar.limits

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.isachivka.bewareofsugar.R
import dev.isachivka.bewareofsugar.ui.AppIcons
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import dev.isachivka.bewareofsugar.ui.theme.mono
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * How much of each subscription is left — one full-width tile, Claude on the left and Codex on the
 * right, under the module pair. REQ-0045.
 *
 * Every decision it renders was made without it: [footerFor], [toneFor] and [resetTextFor] are pure,
 * and [LimitsState.shown] decides which numbers survive a laptop that stopped answering. What is left
 * here is typography, and the one gesture — **a long press asks again; a tap does nothing**, which is
 * the owner's own shape for this feature: *"+ force refresh by long tap."*
 *
 * The sentence is always beside the colour. A number that is only red needs a legend, and the only
 * report that comes back from a phone is a screenshot.
 */
@Composable
fun LimitsTile(
    state: LimitsState,
    now: Instant,
    onForceRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(
                onLongClickLabel = stringResource(R.string.limits_force_refresh_a11y),
                onLongClick = {
                    // A press during an update is already getting what it asked for. The results
                    // ignore a second call too; this guard is what keeps the haptic honest.
                    if (!state.busy) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onForceRefresh()
                    }
                },
                // A tap does nothing, and that is a decision - REQ-0045 names no detail screen.
                onClick = {},
            )
            .testTag(TAG_LIMITS_TILE)
            .padding(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            ProviderHalf(
                name = stringResource(R.string.limits_claude),
                icon = AppIcons.Claude,
                // The Claude mark keeps its own colour - see ic_claude.xml. Unspecified, not white.
                tint = Color.Unspecified,
                limits = state.shown?.claude,
                busy = state.busy && state.shown == null,
                now = now,
                expired = R.string.limits_error_expired_claude,
                unreachable = R.string.limits_error_unreachable_claude,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag(limitsHalfTag("claude")),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            ProviderHalf(
                name = stringResource(R.string.limits_codex),
                icon = AppIcons.Codex,
                tint = MaterialTheme.colorScheme.onSurface,
                limits = state.shown?.codex,
                busy = state.busy && state.shown == null,
                now = now,
                expired = R.string.limits_error_expired_codex,
                unreachable = R.string.limits_error_unreachable_codex,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 16.dp)
                    .testTag(limitsHalfTag("codex")),
            )
        }
        Text(
            text = footerText(footerFor(state, now)),
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceSubtle,
            modifier = Modifier.padding(top = 12.dp).testTag(TAG_LIMITS_FOOTER),
        )
    }
}

@Composable
private fun ProviderHalf(
    name: String,
    icon: Int,
    tint: Color,
    limits: ProviderLimits?,
    busy: Boolean,
    now: Instant,
    expired: Int,
    unreachable: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Text(text = name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        when {
            busy -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            limits == null -> Text(
                text = "—",
                style = MaterialTheme.typography.bodySmall.mono(),
                color = AppTheme.colors.onSurfaceSubtle,
            )
            limits is ProviderLimits.Failed -> Text(
                text = stringResource(
                    when (limits.error) {
                        ProviderError.NoCredential -> R.string.limits_error_no_credential
                        ProviderError.Expired -> expired
                        ProviderError.Unreachable -> unreachable
                        ProviderError.Malformed -> R.string.limits_error_malformed
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceSubtle,
            )
            limits is ProviderLimits.Windows -> limits.windows.forEach { window -> WindowRow(window, now) }
        }
    }
}

@Composable
private fun WindowRow(window: LimitWindow, now: Instant) {
    Column {
        Text(
            text = stringResource(R.string.limits_window, window.kind.label, window.remainingPct),
            style = MaterialTheme.typography.bodyMedium.mono(),
            fontWeight = FontWeight.Medium,
            color = when (toneFor(window.remainingPct)) {
                LimitTone.Good -> AppTheme.colors.success
                LimitTone.Low -> MaterialTheme.colorScheme.primary
                LimitTone.Critical -> MaterialTheme.colorScheme.error
            },
        )
        window.resetsAt?.let { at ->
            Text(
                text = when (val reset = resetTextFor(at, now, ZoneId.systemDefault())) {
                    is ResetText.In -> stringResource(R.string.limits_resets_in, reset.hours, reset.minutes)
                    is ResetText.At -> stringResource(
                        R.string.limits_resets_at,
                        reset.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                        "%02d:%02d".format(Locale.ENGLISH, reset.time.hour, reset.time.minute),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceSubtle,
            )
        }
    }
}

@Composable
private fun footerText(footer: Footer): String = when (footer) {
    Footer.Idle -> stringResource(R.string.limits_footer_idle)
    Footer.NotPaired -> stringResource(R.string.limits_footer_not_paired)
    Footer.TooOld -> stringResource(R.string.limits_footer_too_old)
    Footer.Unreachable -> stringResource(R.string.limits_footer_unreachable)
    Footer.Updating -> stringResource(R.string.limits_footer_updating)
    is Footer.Refused -> footer.reason
    is Footer.UpdatedAgo ->
        if (footer.minutes < 1) {
            stringResource(R.string.limits_footer_updated_now)
        } else {
            pluralStringResource(R.plurals.limits_footer_updated_ago, footer.minutes.toInt(), footer.minutes.toInt())
        }
}

const val TAG_LIMITS_TILE = "limits_tile"
const val TAG_LIMITS_FOOTER = "limits_footer"
fun limitsHalfTag(provider: String) = "limits_half_$provider"

@Preview(name = "Limits tile", widthDp = 412)
@Composable
private fun LimitsTilePreview() {
    val now = Instant.parse("2026-09-06T10:00:00Z")
    AppTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            LimitsTile(
                state = LimitsState.Held(
                    LimitsSnapshot(
                        fetchedAt = now.minusSeconds(12 * 60),
                        claude = ProviderLimits.Windows(
                            listOf(
                                LimitWindow(WindowKind.FiveHour, 89, now.plusSeconds(2 * 3600 + 13 * 60)),
                                LimitWindow(WindowKind.SevenDay, 75, now.plusSeconds(33 * 3600)),
                            ),
                        ),
                        codex = ProviderLimits.Windows(
                            listOf(LimitWindow(WindowKind.SevenDay, 11, now.plusSeconds(21 * 3600))),
                        ),
                    ),
                ),
                now = now,
                onForceRefresh = {},
            )
        }
    }
}

@Preview(name = "Limits tile, bridge too old", widthDp = 412)
@Composable
private fun LimitsTileTooOldPreview() {
    AppTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            LimitsTile(state = LimitsState.BridgeTooOld, now = Instant.now(), onForceRefresh = {})
        }
    }
}
