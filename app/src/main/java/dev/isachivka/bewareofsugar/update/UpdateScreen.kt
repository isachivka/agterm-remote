/*
 * ============================================================================================
 * HIDDEN, PENDING REMOVAL — REQ-0050.
 *
 * Nothing navigates here any more. The app's update screen is
 * `update/play/PlayUpdateScreen.kt`, and updates arrive through Google Play.
 *
 * This file still compiles and its tests still pass, because it still does exactly what it says.
 * It is kept rather than deleted for one reason: deleting it is a requirement of its own, with
 * consequences to decide rather than discover. Going with it, when that happens:
 *
 *   - the token screen, the token store and its hardware-backed cipher
 *   - the downloader, `UpdateInstaller`, and REQUEST_INSTALL_PACKAGES from the manifest
 *   - `update/play/PlayHomeStatus.kt`, the adapter that exists only while both types do
 *   - the app's only two-screen-deep journey, which three instrumented tests use to measure the
 *     back stack and its survival through a rotation. That coverage needs somewhere else to live
 *     BEFORE this goes, which is the decision that made removal its own requirement.
 *
 * Do not extend this path. Do not fix bugs in it. Bugs here are reported by nobody, because
 * nothing reaches it.
 * ============================================================================================
 */

package dev.isachivka.bewareofsugar.update

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.isachivka.bewareofsugar.BuildConfig
import dev.isachivka.bewareofsugar.R
import dev.isachivka.bewareofsugar.ui.AppIcons
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import dev.isachivka.bewareofsugar.ui.theme.mono
import dev.isachivka.bewareofsugar.versionLabel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * What the update check found, and a button to ask again.
 *
 * Reached from the launcher rather than shown over it. Nothing here interrupts: REQ-0003 wants the
 * updater quiet and ignorable, so every outcome — including every failure — is a card on a screen
 * the owner chose to open.
 *
 * Restyled onto REQ-0004's language without touching the state model underneath. Nothing on this
 * screen is called anything new and nothing leads anywhere new: back still goes to the launcher and
 * the token screen is still one step further in.
 */
@Composable
fun UpdateScreen(
    status: UpdateStatus,
    onCheckNow: () -> Unit,
    onOpenToken: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    download: DownloadState = DownloadState.Idle,
    tokenValidatedAtEpochSeconds: Long? = null,
    /** A stored token that would not read — REQ-0047. The row says so instead of "Not set". */
    tokenUnreadable: Boolean = false,
    onDownload: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onInstall: () -> Unit = {},
    onAllowInstalls: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag(TAG_UPDATES_BACK)) {
                Icon(
                    painter = painterResource(AppIcons.ArrowBack),
                    contentDescription = stringResource(R.string.nav_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Box(modifier = Modifier.weight(1f))
            // The design's shortcut into the token screen. The row further down does the same
            // thing and says what it is - this is for the second visit, not the first.
            IconButton(onClick = onOpenToken, modifier = Modifier.testTag(TAG_TOKEN_SHORTCUT)) {
                Icon(
                    painter = painterResource(AppIcons.Key),
                    contentDescription = stringResource(R.string.updates_open_token),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 22.dp)) {
            Text(
                text = stringResource(R.string.updates_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                // versionLabel() rather than VERSION_NAME alone, so this reads the same as the
                // launcher tile and carries the versionCode the design shows.
                text = stringResource(R.string.updates_installed, versionLabel()),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceSubtle,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(status)

            if (status is UpdateStatus.Available) {
                ReleaseCard(status.release)
                DownloadCard(
                    state = download,
                    sizeBytes = status.release.updateAsset?.sizeBytes ?: 0L,
                    onDownload = onDownload,
                    onCancel = onCancelDownload,
                    onInstall = onInstall,
                    onAllowInstalls = onAllowInstalls,
                )
            }

            OutlinedButton(
                onClick = onCheckNow,
                enabled = status != UpdateStatus.Checking,
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_CHECK_NOW),
            ) {
                Text(text = stringResource(R.string.updates_check_now))
            }

            TokenRow(
                validatedAtEpochSeconds = tokenValidatedAtEpochSeconds,
                unreadable = tokenUnreadable,
                onClick = onOpenToken,
            )
        }
    }
}

/**
 * The one card that is always here, and the only thing on this screen that changes colour.
 *
 * Its tone comes from [presentationOf], which maps all thirteen states exhaustively — see the note
 * there on why a failed background check does not get to shout.
 */
@Composable
private fun StatusCard(status: UpdateStatus, modifier: Modifier = Modifier) {
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
            // Announced as one statement rather than as two loose fragments. The title and the
            // line under it are one thing being said, and a screen reader reading them as separate
            // nodes makes the owner assemble the sentence themselves.
            .semantics(mergeDescendants = true) { }
            .testTag(TAG_UPDATE_STATUS),
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
                text = statusLine(status),
                style = MaterialTheme.typography.titleMedium,
                color = content,
            )
            statusBody(status)?.let { body ->
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    // The design's own device for a second line: the same colour, quieter.
                    color = content.copy(alpha = 0.72f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * The release notes, as plain text.
 *
 * Deliberately not rendered: a Markdown renderer is a dependency, and REQ-0003's notes are a handful
 * of `feat:`/`fix:` lines that release-please generates. What the owner sees is what GitHub sends —
 * `## What's Changed` keeps its hashes, and a link shows as its Markdown rather than as a tappable
 * link. Legible, and honest about being raw. The monospace is what says "this is what arrived".
 */
@Composable
private fun ReleaseCard(release: Release, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.extraLarge)
            .padding(22.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = release.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // The number that changes a decision on mobile data, and the reason it is a chip rather
            // than a sentence: it is data, and the design sets data in mono.
            release.updateAsset?.let { asset ->
                Text(
                    text = formatBytes(asset.sizeBytes),
                    style = MaterialTheme.typography.labelMedium.mono(),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.shapes.extraSmall,
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag(TAG_RELEASE_SIZE),
                )
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )

        val notes = release.notes.trim()
        Text(
            text = notes.ifEmpty { stringResource(R.string.updates_no_notes) },
            style = MaterialTheme.typography.bodySmall.mono(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TAG_RELEASE_NOTES),
        )
    }
}

/**
 * The download, its progress, and what to do about however it ended.
 *
 * The design draws three of these five. The other two — no permission to install, and a failure —
 * already existed and already had copy; leaving them undrawn would not have made them stop
 * happening.
 *
 * Progress is real: the numbers are bytes actually written to disk, and the bar is driven by them.
 * When GitHub does not send a size there is no bar rather than an invented one.
 */
@Composable
private fun DownloadCard(
    state: DownloadState,
    sizeBytes: Long,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onAllowInstalls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.extraLarge)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state) {
            DownloadState.Idle -> {
                // Before the tap, not during it: on mobile data the size is the whole decision.
                if (sizeBytes > 0) {
                    Text(
                        text = stringResource(R.string.updates_size, formatBytes(sizeBytes)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(TAG_DOWNLOAD_SIZE),
                    )
                }
                PrimaryAction(
                    text = stringResource(R.string.updates_download),
                    onClick = onDownload,
                    tag = TAG_DOWNLOAD,
                )
            }

            is DownloadState.Running -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.updates_downloading_label),
                        style = MaterialTheme.typography.bodyMedium.mono(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.updates_downloaded_of,
                            formatBytes(state.bytesWritten),
                            formatBytes(state.totalBytes),
                        ),
                        style = MaterialTheme.typography.bodyMedium.mono(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(TAG_DOWNLOAD_PROGRESS),
                    )
                }
                state.fraction?.let { fraction ->
                    LinearProgressIndicator(
                        progress = { fraction },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(MaterialTheme.shapes.extraSmall),
                    )
                }
                TextButton(
                    onClick = onCancel,
                    shape = CircleShape,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_DOWNLOAD_CANCEL),
                ) {
                    Text(text = stringResource(R.string.updates_cancel))
                }
            }

            is DownloadState.ReadyToInstall -> {
                IconLine(
                    icon = AppIcons.DownloadDone,
                    tint = AppTheme.colors.success,
                    text = stringResource(
                        if (state.launched) R.string.updates_ready_again else R.string.updates_ready,
                    ),
                )
                PrimaryAction(
                    text = stringResource(R.string.updates_install),
                    onClick = onInstall,
                    tag = TAG_INSTALL,
                )
            }

            DownloadState.NotAllowedToInstall -> {
                IconLine(
                    icon = AppIcons.Error,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = stringResource(R.string.updates_not_allowed),
                )
                PrimaryAction(
                    text = stringResource(R.string.updates_allow),
                    onClick = onAllowInstalls,
                    tag = TAG_ALLOW_INSTALLS,
                )
            }

            is DownloadState.Failed -> {
                // The one place on this screen that uses the error colours, and it earns them: the
                // owner tapped Update and it did not work.
                IconLine(
                    icon = AppIcons.Error,
                    tint = MaterialTheme.colorScheme.error,
                    text = downloadFailureLine(state.reason),
                    textColor = MaterialTheme.colorScheme.error,
                )
                // Every download failure leaves the same way out: try again. Nothing here is a dead
                // end, which is the whole point of REQ-0003's failure list.
                PrimaryAction(
                    text = stringResource(R.string.updates_download),
                    onClick = onDownload,
                    tag = TAG_DOWNLOAD,
                )
            }
        }
    }
}

@Composable
private fun PrimaryAction(text: String, onClick: () -> Unit, tag: String) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    ) {
        Text(text = text, modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun IconLine(
    icon: Int,
    tint: Color,
    text: String,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = textColor,
            modifier = Modifier.testTag(TAG_DOWNLOAD_STATUS),
        )
    }
}

/** The way into the token screen that says what it is for. */
@Composable
private fun TokenRow(
    validatedAtEpochSeconds: Long?,
    unreadable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .testTag(TAG_OPEN_TOKEN)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painter = painterResource(AppIcons.Key),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.updates_entry),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = when {
                    validatedAtEpochSeconds != null ->
                        stringResource(R.string.updates_token_connected, dayOf(validatedAtEpochSeconds))
                    unreadable -> stringResource(R.string.updates_token_unreadable_row)
                    else -> stringResource(R.string.updates_token_missing)
                },
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceSubtle,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            painter = painterResource(AppIcons.ChevronRight),
            contentDescription = null,
            tint = AppTheme.colors.onSurfaceSubtle,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun downloadFailureLine(reason: DownloadFailure): String = when (reason) {
    DownloadFailure.Interrupted -> stringResource(R.string.updates_interrupted)
    is DownloadFailure.Truncated -> stringResource(R.string.updates_truncated)
    is DownloadFailure.NotEnoughSpace -> stringResource(R.string.updates_no_space, formatBytes(reason.requiredBytes))
    DownloadFailure.NoAsset -> stringResource(R.string.updates_no_asset)
    // A rejected download is a fact about the token, and the status card above already says it in
    // the words the rest of the app uses.
    is DownloadFailure.Rejected -> statusLine(
        UpdateCheck.evaluate(ApiResult.Failure(reason.reason), null, 0L, hasWorkedBefore = true),
    )
}

/** Deliberately coarse: the owner wants to see it moving, not to audit it. */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun statusLine(status: UpdateStatus): String = when (status) {
    UpdateStatus.Idle -> stringResource(R.string.updates_idle)
    UpdateStatus.Checking -> stringResource(R.string.updates_checking)
    is UpdateStatus.UpToDate -> stringResource(R.string.updates_up_to_date)
    is UpdateStatus.Available -> stringResource(R.string.updates_available, status.release.tag)
    UpdateStatus.NoToken -> stringResource(R.string.updates_no_token)
    UpdateStatus.TokenUnreadable -> stringResource(R.string.updates_token_unreadable)
    UpdateStatus.TokenExpired -> stringResource(R.string.updates_token_expired)
    UpdateStatus.TokenNotAccepted -> stringResource(R.string.token_not_accepted)
    UpdateStatus.NoRepoAccess -> stringResource(R.string.token_no_repo_access, BuildConfig.GITHUB_REPO)
    UpdateStatus.InsufficientPermission -> stringResource(R.string.token_insufficient_permission)
    is UpdateStatus.RateLimited -> status.resetAtEpochSeconds
        ?.let { stringResource(R.string.token_rate_limited_until, timeOfDay(it)) }
        ?: stringResource(R.string.token_rate_limited)
    UpdateStatus.Offline -> stringResource(R.string.token_offline)
    is UpdateStatus.GitHubUnavailable -> stringResource(R.string.token_github_unavailable, status.statusCode)
    UpdateStatus.UnreadableRelease -> stringResource(R.string.updates_unreadable_release)
}

/**
 * The second line, where the design supplies one.
 *
 * Null for the eight states it does not draw. Their existing sentences already say everything, and
 * inventing a second line for each would be words written to fill a layout.
 */
@Composable
private fun statusBody(status: UpdateStatus): String? = when (status) {
    UpdateStatus.Idle -> stringResource(R.string.updates_idle_body)
    UpdateStatus.Checking -> stringResource(R.string.updates_checking_body, BuildConfig.GITHUB_REPO)
    is UpdateStatus.UpToDate -> stringResource(R.string.updates_up_to_date_body)
    is UpdateStatus.Available -> stringResource(R.string.updates_available_body, versionLabel())
    UpdateStatus.NoToken -> stringResource(R.string.updates_no_token_body)
    UpdateStatus.TokenUnreadable -> stringResource(R.string.updates_token_unreadable_body)
    else -> null
}

private fun timeOfDay(epochSeconds: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochSeconds * 1000))

private fun dayOf(epochSeconds: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000))

internal const val TAG_UPDATE_STATUS = "update_status"
internal const val TAG_CHECK_NOW = "update_check_now"
internal const val TAG_RELEASE_NOTES = "update_release_notes"
internal const val TAG_RELEASE_SIZE = "update_release_size"
internal const val TAG_OPEN_TOKEN = "update_open_token"
internal const val TAG_TOKEN_SHORTCUT = "update_token_shortcut"
internal const val TAG_UPDATES_BACK = "update_back"
internal const val TAG_DOWNLOAD = "update_download"
internal const val TAG_DOWNLOAD_SIZE = "update_download_size"
internal const val TAG_DOWNLOAD_PROGRESS = "update_download_progress"
internal const val TAG_DOWNLOAD_CANCEL = "update_download_cancel"
internal const val TAG_DOWNLOAD_STATUS = "update_download_status"
internal const val TAG_INSTALL = "update_install"
internal const val TAG_ALLOW_INSTALLS = "update_allow_installs"

@Preview(name = "Updates — available", widthDp = 412, heightDp = 892)
@Composable
private fun UpdateScreenAvailablePreview() {
    AppTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            UpdateScreen(
                status = UpdateStatus.Available(
                    Release(
                        tag = "v0.6.0",
                        version = AppVersion.parse("v0.6.0"),
                        title = "v0.6.0",
                        notes = "## What's Changed\n* feat: module launcher shell\n* fix: token screen keeps FLAG_SECURE on rotate",
                        draft = false,
                        prerelease = false,
                        assets = listOf(ReleaseAsset(1L, "beware-of-sugar-0.6.0.apk", 25_373_199L)),
                    ),
                    1_700_000_000L,
                ),
                onCheckNow = {},
                onOpenToken = {},
                onBack = {},
                tokenValidatedAtEpochSeconds = 1_700_000_000L,
            )
        }
    }
}

@Preview(name = "Updates — no token", widthDp = 412, heightDp = 892)
@Composable
private fun UpdateScreenNoTokenPreview() {
    AppTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            UpdateScreen(
                status = UpdateStatus.NoToken,
                onCheckNow = {},
                onOpenToken = {},
                onBack = {},
            )
        }
    }
}
