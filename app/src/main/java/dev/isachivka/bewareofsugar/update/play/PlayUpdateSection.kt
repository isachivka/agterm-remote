package dev.isachivka.bewareofsugar.update.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.isachivka.bewareofsugar.R
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import dev.isachivka.bewareofsugar.update.StatusTone
import dev.isachivka.bewareofsugar.update.TAG_CHECK_NOW

/** So an instrumented test can find the card without reading its words. */
const val TAG_PLAY_STATUS = "play-update-status"
const val TAG_PLAY_UPDATE = "play-update-now"
const val TAG_PLAY_OPEN_STORE = "play-open-store"

/**
 * The Play half of the update screen — REQ-0050.
 *
 * A card saying what Play answered, and at most one button. It replaces four things the GitHub path
 * needed and this one does not: a token row, a release-notes card, a download card with progress,
 * and a hand-off to Android's installer. Play does all of that, which is the entire reason this
 * requirement exists.
 */
@Composable
fun PlayUpdateSection(
    status: PlayUpdateStatus,
    onCheckNow: () -> Unit,
    onUpdate: () -> Unit,
    onOpenStore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PlayStatusCard(status)

        // At most ONE action, and never two at once. An update screen offering both "Update now"
        // and "Open in Google Play" makes the owner choose between two things they cannot tell
        // apart; which one is right is a fact this app already knows.
        when {
            status is PlayUpdateStatus.Available && status.immediateAllowed -> {
                Button(
                    onClick = onUpdate,
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_PLAY_UPDATE),
                ) {
                    Text(text = stringResource(R.string.updates_play_update))
                }
            }

            // Play has the update and will not let this app install it. The button that does work
            // is the Store's, so that is the one offered - rather than a button of ours that Play
            // would refuse, which is the failure PlayUpdateViewModel has a whole state for.
            status is PlayUpdateStatus.Available -> {
                OutlinedButton(
                    onClick = onOpenStore,
                    shape = CircleShape,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_PLAY_OPEN_STORE),
                ) {
                    Text(text = stringResource(R.string.updates_play_open_store))
                }
            }

            status is PlayUpdateStatus.NotFromPlay -> {
                OutlinedButton(
                    onClick = onOpenStore,
                    shape = CircleShape,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_PLAY_OPEN_STORE),
                ) {
                    Text(text = stringResource(R.string.updates_play_open_store))
                }
            }
        }

        // Check stays available in every state, including while a check is running - disabled then,
        // not hidden, so the button does not move under a finger already on its way to it.
        OutlinedButton(
            onClick = onCheckNow,
            enabled = status != PlayUpdateStatus.Checking && status !is PlayUpdateStatus.NotFromPlay,
            shape = CircleShape,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
            // The same tag the GitHub screen's check button carried. Not laziness: NavigationInstrumentedTest
            // identifies "the updates screen has finished opening" by it, and that statement is about
            // the screen rather than about which channel it checks.
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TAG_CHECK_NOW),
        ) {
            Text(text = stringResource(R.string.updates_check_now))
        }
    }
}

@Composable
private fun PlayStatusCard(status: PlayUpdateStatus, modifier: Modifier = Modifier) {
    val presentation = presentationOf(status)
    val container = when (presentation.tone) {
        StatusTone.Neutral -> MaterialTheme.colorScheme.surfaceContainer
        StatusTone.Success -> AppTheme.colors.successContainer
        StatusTone.UpdateAvailable -> MaterialTheme.colorScheme.primaryContainer
        StatusTone.NeedsToken -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = when (presentation.tone) {
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusTone.Success -> AppTheme.colors.onSuccessContainer
        StatusTone.UpdateAvailable -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.NeedsToken -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(container, MaterialTheme.shapes.extraLarge)
            .padding(22.dp)
            // One statement, not two loose fragments - the same reason the GitHub card does this.
            .semantics(mergeDescendants = true) { }
            .testTag(TAG_PLAY_STATUS),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (presentation.spinner) {
            CircularProgressIndicator(
                color = content,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(24.dp),
            )
        } else {
            Icon(
                painter = painterResource(presentation.icon),
                contentDescription = null,
                tint = content,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playStatusLine(status),
                style = MaterialTheme.typography.titleMedium,
                color = content,
            )
            playStatusBody(status)?.let { body ->
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = content.copy(alpha = 0.72f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun playStatusLine(status: PlayUpdateStatus): String = when (status) {
    PlayUpdateStatus.Idle -> stringResource(R.string.updates_idle)
    PlayUpdateStatus.Checking -> stringResource(R.string.updates_play_checking)
    is PlayUpdateStatus.UpToDate -> stringResource(R.string.updates_play_up_to_date)
    is PlayUpdateStatus.Available -> stringResource(
        R.string.updates_play_available,
        // The name when the code is one this scheme could have produced, and the bare number when
        // it is not. Never an invented version - see versionNameOfCode.
        versionNameOfCode(status.versionCode) ?: status.versionCode.toString(),
    )

    is PlayUpdateStatus.InProgress -> stringResource(R.string.updates_play_in_progress)
    PlayUpdateStatus.NotFromPlay -> stringResource(R.string.updates_play_not_from_play)
    is PlayUpdateStatus.Unknown -> stringResource(R.string.updates_play_unknown)
    is PlayUpdateStatus.Failed ->
        if (status.errorCode != null) {
            stringResource(R.string.updates_play_failed_code, status.errorCode)
        } else {
            stringResource(R.string.updates_play_failed)
        }
}

@Composable
private fun playStatusBody(status: PlayUpdateStatus): String? = when (status) {
    PlayUpdateStatus.Idle -> null
    PlayUpdateStatus.Checking -> null
    is PlayUpdateStatus.UpToDate -> stringResource(R.string.updates_play_up_to_date_body)
    is PlayUpdateStatus.Available ->
        if (status.immediateAllowed) {
            stringResource(R.string.updates_play_available_body)
        } else {
            stringResource(R.string.updates_play_not_forceable)
        }

    is PlayUpdateStatus.InProgress -> stringResource(R.string.updates_play_in_progress_body)
    PlayUpdateStatus.NotFromPlay -> stringResource(R.string.updates_play_not_from_play_body)
    is PlayUpdateStatus.Unknown -> stringResource(R.string.updates_play_unknown_body)
    is PlayUpdateStatus.Failed -> stringResource(R.string.updates_play_failed_body)
}
