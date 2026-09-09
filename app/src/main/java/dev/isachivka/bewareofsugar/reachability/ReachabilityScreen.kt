package dev.isachivka.bewareofsugar.reachability

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.isachivka.bewareofsugar.R
import dev.isachivka.bewareofsugar.ui.AppIcons
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import dev.isachivka.bewareofsugar.ui.theme.mono

/**
 * Can I reach my homelab from here.
 *
 * The screen is presentation and nothing else — every decision it renders was made by
 * [ReachabilityCheck] with no IO in it, and by [rowFor] and [summaryOf], which are pure. That is
 * deliberate: if the classification is right this screen is typography, and if it is wrong this
 * screen is a liar with good typography.
 *
 * **The screenshot is the bug report.** The acceptance test is a person on a filtered Russian mobile
 * network that neither the author nor the reviewer can stand on, and the only channel back is an
 * image. So every row carries its own sentence rather than a colour that needs a legend, the summary
 * says what the pattern means rather than a tally, and nothing that matters is conveyed by hue alone.
 */
@Composable
fun ReachabilityScreen(
    state: ReachabilityUiState,
    onCheckNow: () -> Unit,
    onOpen: (HomeService) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.testTag(TAG_BACK)) {
                Icon(
                    painter = painterResource(AppIcons.ArrowBack),
                    contentDescription = stringResource(R.string.nav_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 18.dp)) {
            Text(
                text = stringResource(R.string.reach_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.reach_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceSubtle,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        SummaryCard(state.summary)

        Button(
            onClick = onCheckNow,
            enabled = state.summary !is CheckSummary.Running,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .testTag(TAG_CHECK_NOW),
        ) {
            Text(text = stringResource(R.string.reach_check_now))
        }

        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.rows.forEach { row ->
                ServiceRow(row = row, onOpen = { onOpen(row.service) })
            }
        }

        NotCheckableSection(names = state.notCheckable)
    }
}

/**
 * What the ten rows add up to.
 *
 * Above the rows rather than below, because on a filtered network the pattern is the answer and the
 * rows are its evidence. Ten identical failures are one diagnosis; making the owner infer that from
 * ten red rows is the difference between a tool and a table.
 */
@Composable
private fun SummaryCard(summary: CheckSummary, modifier: Modifier = Modifier) {
    val text = when (summary) {
        CheckSummary.NotChecked -> stringResource(R.string.reach_summary_not_checked)
        is CheckSummary.Running -> stringResource(R.string.reach_summary_running, summary.done, summary.total)
        is CheckSummary.Reached ->
            if (summary.reached == summary.total) {
                stringResource(R.string.reach_summary_all_answered, summary.total)
            } else {
                pluralStringResource(
                    R.plurals.reach_summary_reached,
                    summary.reached,
                    summary.reached,
                    summary.total,
                )
            }
        is CheckSummary.NothingReached -> when (summary.pattern) {
            FailurePattern.AllSameLayer -> stringResource(R.string.reach_summary_same_layer, summary.total)
            FailurePattern.Mixed -> stringResource(R.string.reach_summary_mixed)
            FailurePattern.RouterOnly -> stringResource(R.string.reach_summary_router_only)
        }
    }

    Box(
        modifier = modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .testTag(TAG_SUMMARY)
            .padding(18.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One service.
 *
 * Semantics are merged so a screen reader announces the row as one thing — name, verdict and reason
 * together — rather than as four fragments the owner has to assemble. The Open button keeps its own
 * node because it is the only part that does anything.
 */
@Composable
private fun ServiceRow(row: ServiceRowState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val presentation = row.presentation
    val accent = accentFor(presentation.tone)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .testTag(serviceRowTag(row.service.id))
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) { },
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (presentation.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                } else {
                    Icon(
                        painter = painterResource(presentation.icon),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text(
                    text = row.service.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(presentation.headline),
                        style = MaterialTheme.typography.bodySmall,
                        color = accent,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    // Latency beside the verdict, because "up, but four seconds" is a real answer on
                    // a filtered network and is invisible if it lives anywhere else.
                }
            }

            // Subdued deliberately. The default TextButton is the primary colour, which is also the
            // Degraded verdict's colour - so on screen four amber "Open" labels pulled the eye
            // harder than the verdicts they sat beside, and "Router answered, not the service" was
            // the same hue as the button next to it. The verdict owns the colour on this screen.
            TextButton(
                onClick = onOpen,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.testTag(openTag(row.service.id)),
            ) {
                Text(text = stringResource(R.string.reach_open))
            }
        }

        // The facts, on one line of their own.
        //
        // The status code and the address family are here rather than appended to the verdict for a
        // reason the screen makes obvious: "Router answered, not the service" is already 31
        // characters, and hanging "200 · IPv6 · 112 ms" off it wraps the verdict onto two lines on
        // exactly the rows that are hardest to read. Latency moved down here with them rather than
        // being duplicated, so a row gains a line only where there are facts to gain it for.
        //
        // Absence renders as absence. A name that would not resolve has no family and no code, and
        // an empty separator would put noise on the rows the owner is most likely to be reading.
        val facts = listOfNotNull(
            row.statusCode?.toString(),
            row.family?.name,
            row.latencyMillis?.let { stringResource(R.string.reach_latency, it) },
        )
        if (facts.isNotEmpty()) {
            Text(
                text = facts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall.mono(),
                color = AppTheme.colors.onSurfaceSubtle,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag(factsTag(row.service.id)),
            )
        }

        // The sentence. Every state that has one shows it, because a colour needs a legend and a
        // sentence does not - and the only report coming back from Russia is a screenshot.
        presentation.detail?.let { detail ->
            Text(
                text = stringResource(detail),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceSubtle,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * The half of the estate this screen cannot speak for.
 *
 * Named rather than omitted: a list that silently drops ten services reads as "these are all my
 * services". Names only — `ServiceRegistry` says why their addresses stay out of the app.
 */
@Composable
private fun NotCheckableSection(names: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 28.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .testTag(TAG_NOT_CHECKABLE)
            .padding(18.dp),
    ) {
        Text(
            text = stringResource(R.string.reach_lan_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.reach_lan_body, names.size),
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceSubtle,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = names.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceSubtle,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/**
 * The tone, as a colour.
 *
 * [RowTone.Tampering] is the only one that reaches for the error role, and REQ-0005's two-clause rule
 * is why: loud styling is for something the owner just did that did not work, and for evidence that
 * something is tampering with their connection. Nothing on this screen is the first, and only one
 * state is the second. A service that did not answer because the network is filtering is not the
 * owner's mistake and does not get an alarm.
 */
@Composable
private fun accentFor(tone: RowTone): Color = when (tone) {
    RowTone.Neutral -> AppTheme.colors.onSurfaceSubtle
    RowTone.Up -> AppTheme.colors.success
    RowTone.Degraded -> MaterialTheme.colorScheme.primary
    RowTone.Unreachable -> MaterialTheme.colorScheme.onSurfaceVariant
    RowTone.Tampering -> MaterialTheme.colorScheme.error
}

internal fun serviceRowTag(id: String) = "reach_row_$id"

internal fun openTag(id: String) = "reach_open_$id"

internal fun factsTag(id: String) = "reach_facts_$id"

internal const val TAG_BACK = "reach_back"
internal const val TAG_SUMMARY = "reach_summary"
internal const val TAG_CHECK_NOW = "reach_check_now"
internal const val TAG_NOT_CHECKABLE = "reach_not_checkable"

@Preview(name = "Reachability", widthDp = 412, heightDp = 892)
@Composable
private fun ReachabilityPreview() {
    AppTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            ReachabilityScreen(
                state = ReachabilityUiState.preview(),
                onCheckNow = {},
                onOpen = {},
                onBack = {},
            )
        }
    }
}
