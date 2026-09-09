package dev.isachivka.bewareofsugar.update

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.isachivka.bewareofsugar.BuildConfig
import dev.isachivka.bewareofsugar.R
import dev.isachivka.bewareofsugar.ui.AppIcons
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import dev.isachivka.bewareofsugar.ui.theme.mono
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Where the owner puts their token in.
 *
 * Four things here are security decisions rather than styling:
 *
 * 1. `FLAG_SECURE` while this screen is showing, so it stays out of screenshots, screen recordings
 *    and the recents thumbnail — the last of which is written to disk.
 * 2. The chip at the top saying so. The flag has been on since REQ-0003; what is new is that the
 *    owner can now tell why their screenshot came out black instead of assuming the phone broke.
 * 3. The text toolbar is replaced with one that does nothing, so no Copy, Cut, Share or Select all
 *    is ever offered on a field holding the token. Since that also removes Paste, and pasting is how
 *    a token actually gets here, there is an explicit Paste button instead.
 * 4. The field is masked and the token is never displayed back once saved — not even the last four
 *    characters, which is a convention this screen does not need.
 *
 * Where that stops, stated rather than implied: while the field is on screen, Compose keeps the
 * untransformed value in the node's `InputText` semantics, because the IME and autofill need it.
 * The displayed text and `EditableText` are both masked, the node is marked as a password field so
 * accessibility services treat it as secret, and `FLAG_SECURE` keeps the window out of screen
 * capture — but a tool reading the semantics tree of the foreground app could still see it there.
 * It is transient and in memory only. `TokenScreenUiTest` pins exactly this boundary.
 *
 * Restyled in REQ-0004 without moving anything: this screen is still reached from Updates, back
 * still returns there, and nothing on it is called anything new.
 */
@Composable
fun TokenScreen(
    state: TokenScreenState,
    onInputChange: (String) -> Unit,
    onSave: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureWhileVisible()

    val repo = BuildConfig.GITHUB_REPO
    var howVisible by remember { mutableStateOf(false) }

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
            IconButton(onClick = onBack, modifier = Modifier.testTag(TAG_BACK)) {
                Icon(
                    painter = painterResource(AppIcons.ArrowBack),
                    contentDescription = stringResource(R.string.nav_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Box(modifier = Modifier.weight(1f))
            ScreenshotsBlockedChip()
        }

        Text(
            text = stringResource(R.string.token_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 22.dp),
        )

        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.showsField) {
                Text(
                    text = stringResource(R.string.token_intro, repo),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TAG_INTRO),
                )

                HowToPanel(expanded = howVisible, onToggle = { howVisible = !howVisible })

                TokenField(
                    value = state.input,
                    enabled = state.status != TokenStatus.Validating,
                    isError = state.status is TokenStatus.NotAccepted,
                    onValueChange = onInputChange,
                )

                Button(
                    onClick = onSave,
                    enabled = state.canSave,
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_SAVE),
                ) {
                    if (state.status == TokenStatus.Validating) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .size(18.dp),
                        )
                        Text(text = stringResource(R.string.token_checking))
                    } else {
                        Text(
                            text = stringResource(R.string.token_save),
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            } else {
                ConnectedCard(state.status as? TokenStatus.Saved, repo)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onReplace,
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TAG_REPLACE),
                    ) {
                        Text(text = stringResource(R.string.token_replace))
                    }
                    OutlinedButton(
                        onClick = onRemove,
                        shape = CircleShape,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TAG_REMOVE),
                    ) {
                        Text(text = stringResource(R.string.token_remove))
                    }
                }
            }

            StatusCard(state.status)
        }
    }
}

/**
 * Says out loud what `FLAG_SECURE` has been doing since REQ-0003.
 *
 * Cheap, and it turns "my screenshot came out black and I don't know why" into "of course it did".
 */
@Composable
private fun ScreenshotsBlockedChip(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(end = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(TAG_SCREENSHOTS_BLOCKED),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(AppIcons.NoPhotography),
            contentDescription = null,
            tint = AppTheme.colors.onSurfaceSubtle,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.token_screenshots_blocked),
            style = MaterialTheme.typography.labelSmall,
            color = AppTheme.colors.onSurfaceSubtle,
        )
    }
}

/** The steps, folded away until asked for, so the first read is one sentence and not a manual. */
@Composable
private fun HowToPanel(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
                .clickable(onClick = onToggle)
                .testTag(TAG_HOW)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(AppIcons.Help),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.token_how_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore),
                contentDescription = null,
                tint = AppTheme.colors.onSurfaceSubtle,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Text(
                text = stringResource(R.string.token_how_body),
                // Mono, because these are literal menu paths to follow rather than prose to read.
                style = MaterialTheme.typography.bodySmall.mono(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        MaterialTheme.shapes.medium,
                    )
                    .padding(18.dp),
            )
        }
    }
}

/** A token is stored and GitHub accepted it. The one place this screen is allowed to look pleased. */
@Composable
private fun ConnectedCard(saved: TokenStatus.Saved?, repo: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AppTheme.colors.successContainer, MaterialTheme.shapes.extraLarge)
            .padding(22.dp)
            // "Connected to the repo, checked on the 27th" is one statement; see the note on the
            // update screen's status card.
            .semantics(mergeDescendants = true) { }
            .testTag(TAG_SAVED),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painter = painterResource(AppIcons.VerifiedUser),
            contentDescription = null,
            tint = AppTheme.colors.onSuccessContainer,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.token_saved, repo),
                style = MaterialTheme.typography.titleMedium,
                color = AppTheme.colors.onSuccessContainer,
            )
            saved?.let {
                Text(
                    text = stringResource(R.string.token_checked_on, checkedOn(it.validatedAtEpochSeconds)),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onSuccessContainer.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * The one card that tells the owner what happened.
 *
 * Every failure is a card here rather than a dialog or an error screen: REQ-0003 is explicit that an
 * expired token is a normal state, and the same is true of a flat connection.
 *
 * The error colours are reserved for the outcomes the owner just caused by pressing Save. Being
 * offline, being rate-limited, or GitHub having a bad afternoon are none of their doing, so those
 * say what happened without shouting — the same rule the update screen's status card follows, and
 * the reason it is written down in both places is that it is easy to lose one of them.
 */
@Composable
private fun StatusCard(status: TokenStatus, modifier: Modifier = Modifier) {
    val message = when (status) {
        is TokenStatus.NotAccepted -> stringResource(R.string.token_not_accepted)
        is TokenStatus.NoRepoAccess -> stringResource(R.string.token_no_repo_access, BuildConfig.GITHUB_REPO)
        is TokenStatus.InsufficientPermission -> stringResource(R.string.token_insufficient_permission)
        is TokenStatus.RateLimited -> status.resetAtEpochSeconds
            ?.let { stringResource(R.string.token_rate_limited_until, timeOfDay(it)) }
            ?: stringResource(R.string.token_rate_limited)
        is TokenStatus.Offline -> stringResource(R.string.token_offline)
        is TokenStatus.GitHubUnavailable -> stringResource(R.string.token_github_unavailable, status.statusCode)
        is TokenStatus.Unreadable -> stringResource(
            if (status.permanent) R.string.token_unreadable else R.string.token_unreadable_for_now,
        )
        is TokenStatus.NotStored -> stringResource(R.string.token_not_stored)
        TokenStatus.NoToken, TokenStatus.Validating, is TokenStatus.Saved -> null
    } ?: return

    // **The cause and the time, on their own line - REQ-0047.** The owner lost a token three times
    // before anyone knew which failure it was; the sentence above says what to do, this says what
    // happened, so the next report carries the evidence with it.
    val detail = (status as? TokenStatus.Unreadable)?.let { unreadable ->
        val since = unreadable.sinceEpochSeconds.takeIf { it > 0L }
        if (since != null) {
            stringResource(R.string.token_unreadable_detail, unreadable.reason, dayAndTime(since))
        } else {
            unreadable.reason
        }
    }

    val loud = when (status) {
        // Something the owner just did that needs redoing: a token GitHub refused, one that cannot
        // see the repository, one without the right permission, one this phone would not keep, and
        // one that will not decrypt.
        is TokenStatus.NotAccepted,
        is TokenStatus.NoRepoAccess,
        is TokenStatus.InsufficientPermission,
        is TokenStatus.NotStored,
        is TokenStatus.Unreadable,
        -> true
        // Weather.
        else -> false
    }

    val container =
        if (loud) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer
    val content =
        if (loud) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(container, MaterialTheme.shapes.medium)
            .padding(18.dp)
            .testTag(TAG_STATUS),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(AppIcons.Error),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(20.dp),
        )
        Column {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 6.dp).testTag(TAG_STATUS_DETAIL),
                )
            }
        }
    }
}

private fun dayAndTime(epochSeconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochSeconds * 1000))

@Composable
private fun TokenField(
    value: String,
    enabled: Boolean,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // No Copy, no Cut, no Share, no Select all on a field holding the token.
    CompositionLocalProvider(LocalTextToolbar provides NoTextToolbar) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                isError = isError,
                singleLine = true,
                label = { Text(text = stringResource(R.string.token_field_label)) },
                trailingIcon = {
                    Icon(
                        painter = painterResource(AppIcons.VisibilityOff),
                        // Not a control - it says the field is masked, next to a node already
                        // announced as a password. Describing it would be said twice.
                        contentDescription = null,
                        tint = AppTheme.colors.onSurfaceSubtle,
                        modifier = Modifier.size(20.dp),
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.bodyLarge.mono(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
                // The design's one asymmetric shape: a filled box sitting on an underline.
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 4.dp,
                    bottomEnd = 4.dp,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    // Marks the node as a password field, which is what makes an accessibility
                    // service treat its contents as secret rather than something to read out.
                    .semantics { password() }
                    .testTag(TAG_FIELD),
            )
            // Because the toolbar above is gone, and pasting is how a token gets onto a phone.
            OutlinedButton(
                onClick = {
                    scope.launch {
                        clipboard.getClipEntry()
                            ?.clipData
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.text
                            ?.let { onValueChange(it.toString()) }
                    }
                },
                enabled = enabled,
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_PASTE),
            ) {
                Text(text = stringResource(R.string.token_paste))
            }
        }
    }
}

/**
 * Keeps this screen out of screenshots, screen recordings and the recents thumbnail for as long as
 * it is on screen, and no longer.
 */
@Composable
private fun SecureWhileVisible() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private val NoTextToolbar = object : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit

    override fun hide() = Unit
}

private fun checkedOn(epochSeconds: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000))

private fun timeOfDay(epochSeconds: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochSeconds * 1000))

internal const val TAG_INTRO = "token_intro"
internal const val TAG_FIELD = "token_field"
internal const val TAG_PASTE = "token_paste"
internal const val TAG_SAVE = "token_save"
internal const val TAG_SAVED = "token_saved"
internal const val TAG_REPLACE = "token_replace"
internal const val TAG_REMOVE = "token_remove"
internal const val TAG_STATUS = "token_status"
internal const val TAG_STATUS_DETAIL = "token_status_detail"
internal const val TAG_BACK = "token_back"
internal const val TAG_HOW = "token_how"
internal const val TAG_SCREENSHOTS_BLOCKED = "token_screenshots_blocked"

@Preview(name = "Token — empty", widthDp = 412, heightDp = 892)
@Composable
private fun TokenScreenEmptyPreview() {
    AppTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            TokenScreen(
                state = TokenScreenState(),
                onInputChange = {},
                onSave = {},
                onReplace = {},
                onRemove = {},
                onBack = {},
            )
        }
    }
}

@Preview(name = "Token — rejected", widthDp = 412, heightDp = 892)
@Composable
private fun TokenScreenNotAcceptedPreview() {
    AppTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            TokenScreen(
                // A preview never carries anything token-shaped, not even a fake one.
                state = TokenScreenState(input = "xxxx", status = TokenStatus.NotAccepted),
                onInputChange = {},
                onSave = {},
                onReplace = {},
                onRemove = {},
                onBack = {},
            )
        }
    }
}

@Preview(name = "Token — connected", widthDp = 412, heightDp = 892)
@Composable
private fun TokenScreenSavedPreview() {
    AppTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            TokenScreen(
                state = TokenScreenState(status = TokenStatus.Saved(1_700_000_000L)),
                onInputChange = {},
                onSave = {},
                onReplace = {},
                onRemove = {},
                onBack = {},
            )
        }
    }
}
