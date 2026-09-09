package dev.isachivka.bewareofsugar.reachability

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

/**
 * The module screen, reading what has already been found.
 *
 * REQ-0005 ran the checks from this composable's own `LaunchedEffect` so that leaving cancelled them.
 * That is still true of every check this screen starts — the effect below is cancelled when the back
 * stack moves off `Screen.Reachability` — but the checks are no longer *owned* here, because REQ-0006
 * needs an answer to exist before the owner arrives.
 *
 * **Opening this screen does not re-check.** The launch check's answer is seconds old, and twenty
 * requests to answer one question is a bad trade on the connection this feature exists to measure. It
 * also makes the tile and these rows agree by construction rather than by coincidence. The one
 * exception is the case where there is nothing to show: if no check has settled — the app launched
 * straight into the module, or a launch check was cancelled on the way — this runs one.
 *
 * Refresh stays, unchanged and always available. It is the owner's control rather than the app's
 * guess about when they want a fresh answer.
 */
@Composable
fun ReachabilityHost(
    results: ReachabilityResults,
    scope: CoroutineScope,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val held by results.results.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        // Nothing has SETTLED, so ask - which is not the same as the map being empty. A launch check
        // that was cancelled on the way here leaves the map full of NotChecked, so an isEmpty guard
        // would show ten blank rows without asking anything. Easier to hit now that every foreground
        // starts a round a fast navigation could cancel.
        if (!results.hasSettled) results.check()
    }

    ReachabilityScreen(
        state = ReachabilityUiState.of(held),
        // On the caller's scope rather than this composable's, so that a refresh the owner asked for
        // is not silently abandoned by a stray recomposition. Leaving the app still cancels it: the
        // scope belongs to the Activity's lifecycle.
        onCheckNow = { scope.launch { results.check() } },
        onOpen = { service -> openInBrowser(context, service) },
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Hands the address to whatever opens links.
 *
 * A diagnostic that says "reachable" and gives no way to go and look is half a tool. Nothing is
 * appended to the address and no credential is involved — this is the same URL the check used.
 *
 * The catch is not defensive padding: a phone with no browser is unusual but real, and an
 * `ActivityNotFoundException` here would crash the app from a button labelled "Open".
 */
private fun openInBrowser(context: android.content.Context, service: HomeService) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, service.url.toUri()))
    } catch (e: ActivityNotFoundException) {
        // Nothing on this phone handles a web link. Silently doing nothing is the honest outcome:
        // there is no alternative to offer and no failure the owner caused.
    }
}
