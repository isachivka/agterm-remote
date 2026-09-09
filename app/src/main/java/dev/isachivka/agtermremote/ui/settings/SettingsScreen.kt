package dev.isachivka.agtermremote.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.isachivka.agtermremote.R
import dev.isachivka.agtermremote.ui.AppIcons
import dev.isachivka.agtermremote.ui.theme.AppTheme

/**
 * Settings: pairing this phone with the laptop, and nothing else.
 *
 * ### What was here until 2026-07-31, and why it is gone rather than hidden
 *
 * A switch per module, turning tiles off. The owner asked for it to be deleted: settings that turn
 * modules on and off are not wanted, and every module the app has should simply be shown.
 *
 * They are right, and the reason is the one that removed `eof` from the key allowlist the same day:
 * **a preference nothing honours is worse than no preference.** Eight of those tiles are placeholders
 * for features that do not exist, so the switches offered control over a fiction — and the ninth
 * effect of turning one off was a launcher that quietly said less than the truth about the app.
 *
 * So the store, the view model, the switch rows, the hidden set and the strings that described them
 * are deleted rather than left unreachable. The launcher draws the whole registry, always.
 *
 * **The eight placeholder tiles stay.** Their sentence was *all modules that exist in the app should
 * show on the home page* — a request for the list to be unfilterable, not shorter. Reading it as
 * permission to delete tiles would answer a question they did not ask.
 *
 * ### The package is `ui.settings` now, and the route is `"settings"`
 *
 * Both were `module`/`modules`, named for a registry of launcher tiles that no longer exists. A name
 * describing what a screen used to be is the same defect as a comment that does. Renaming a route is
 * normally a migration for no benefit - here there is no installed base to migrate, because the
 * `applicationId` changed with the port, so this was the last free moment to do it.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Still a slot rather than pairing state and six callbacks, so this file keeps knowing
    // nothing about pairing.
    //
    // The placement was called provisional once, on the grounds that pairing configures a feature
    // which did not exist yet and its eventual home was beside the terminal. Deleting the modules
    // section settles it the same way: pairing is not a section of this screen any more, it is the
    // screen.
    pairingSection: (@Composable () -> Unit)? = null,
    /**
     * The styled-screen switch: null hides the section (a preview, a test), otherwise its state.
     * Off by default at the store, not here — see [dev.isachivka.agtermremote.settings.StyledScreenStore].
     */
    styledScreen: Boolean? = null,
    onStyledScreen: (Boolean) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.testTag(TAG_SETTINGS_BACK)) {
                Icon(
                    painter = painterResource(AppIcons.ArrowBack),
                    contentDescription = stringResource(R.string.nav_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 22.dp)) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (pairingSection != null) {
            SectionHeading(stringResource(R.string.pairing_section))
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
                pairingSection()
            }
        }

        if (styledScreen != null) {
            SectionHeading(stringResource(R.string.settings_terminal_section))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_styled_screen_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_styled_screen_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceSubtle,
                    )
                }
                Switch(
                    checked = styledScreen,
                    onCheckedChange = onStyledScreen,
                    modifier = Modifier.testTag(TAG_SETTINGS_STYLED_SCREEN),
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = AppTheme.colors.onSurfaceSubtle,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
    )
}

internal const val TAG_SETTINGS_BACK = "modules_back"
internal const val TAG_SETTINGS_STYLED_SCREEN = "settings_styled_screen"

@Preview(name = "Settings", widthDp = 412, heightDp = 892)
@Composable
private fun SettingsPreview() {
    AppTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            SettingsScreen(onBack = {})
        }
    }
}
