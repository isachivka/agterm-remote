package dev.isachivka.bewareofsugar.update.play

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.isachivka.bewareofsugar.R
import dev.isachivka.bewareofsugar.ui.AppIcons
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import dev.isachivka.bewareofsugar.update.TAG_TOKEN_SHORTCUT
import dev.isachivka.bewareofsugar.update.TAG_UPDATES_BACK
import dev.isachivka.bewareofsugar.versionLabel

/**
 * The update screen — REQ-0050, and the one the app actually shows.
 *
 * ## What happened to the old one
 *
 * [dev.isachivka.bewareofsugar.update.UpdateScreen] is intact, still compiles, and is still covered
 * by its own tests. Nothing navigates to it any more. **It is hidden pending removal**, along with
 * the token, the downloader, the installer and `REQUEST_INSTALL_PACKAGES` — every one of which
 * exists to work around not being on Play, and none of which is needed now that we are.
 *
 * It is hidden rather than deleted because deleting it is a requirement of its own with real
 * consequences to think about, not a side effect of adding this file.
 *
 * ## Why the key is still in the header
 *
 * The token screen is the app's only second level of navigation, and three instrumented tests
 * measure the back stack and its survival through a rotation by walking down to it. Removing the
 * route here would take that coverage with it, silently, in a change about updates. So the shortcut
 * stays until the removal requirement deals with both together.
 */
@Composable
fun PlayUpdateScreen(
    status: PlayUpdateStatus,
    onCheckNow: () -> Unit,
    onUpdate: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenToken: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
                // The same label the launcher tile shows, so the two cannot disagree about which
                // version is running - and it carries the versionCode, which is the number Play
                // talks in.
                text = stringResource(R.string.updates_installed, versionLabel()),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceSubtle,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        PlayUpdateSection(
            status = status,
            onCheckNow = onCheckNow,
            onUpdate = onUpdate,
            onOpenStore = onOpenStore,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        )
    }
}
