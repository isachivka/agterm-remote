package dev.isachivka.bewareofsugar.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.isachivka.bewareofsugar.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.isachivka.bewareofsugar.reachability.TileLevel
import dev.isachivka.bewareofsugar.reachability.TileVerdict
import dev.isachivka.bewareofsugar.ui.AppIcons
import dev.isachivka.bewareofsugar.ui.module.Module
import dev.isachivka.bewareofsugar.ui.module.ModuleRegistry
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import dev.isachivka.bewareofsugar.update.UpdateNotice
import dev.isachivka.bewareofsugar.update.UpdateStatus
import dev.isachivka.bewareofsugar.limits.LimitsState
import dev.isachivka.bewareofsugar.limits.LimitsTile
import dev.isachivka.bewareofsugar.versionLabel
import java.time.Instant

/**
 * The launcher, and the first thing the owner sees.
 *
 * What replaced "Hello world". The grid is the honest statement of where this app is: one tile that
 * works, eight that are shapes. REQ-0004 is explicit that the eight stay shapes — no host polling,
 * no Docker API, nothing that talks to anything but github.com — so tapping one lands on a
 * placeholder that says so.
 *
 * The updater's presence here is one row and no dialog, shown only when there is something to say -
 * which is what REQ-0003 means by ignorable. What may be said is [UpdateNotice]'s decision, not this
 * screen's.
 */
@Composable
fun HomeScreen(
    modules: List<Module>,
    installedVersion: String,
    updateStatus: UpdateStatus,
    onOpenUpdates: () -> Unit,
    onOpenModule: (Module) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The tag to announce, or null for silence. Decided by [UpdateNotice] rather than here, so that
     * "may this be shown" is one tested rule instead of a condition living on a screen.
     */
    noticeTag: String? = null,
    /**
     * What the live module's tile says. Null before REQ-0006's launch check has produced anything -
     * which is not the same as zero available, and must not look like it.
     */
    verdict: TileVerdict? = null,
    /** Swipe down to run the checks again. The same round the app runs on every return. */
    onRefresh: () -> Unit = {},
    /** The limits tile's state, or null to draw no tile - previews and tests that are not about it. */
    limits: LimitsState? = null,
    onForceRefreshLimits: () -> Unit = {},
) {

    // Refreshing is DERIVED, not stored. TileVerdict.busy already means "a round is in flight", and a
    // second boolean would be a second source of truth for one fact - they would disagree the first
    // time a round was cancelled, and the indicator would spin over a screen that had stopped.
    PullToRefreshBox(
        isRefreshing = verdict?.busy == true,
        onRefresh = onRefresh,
        modifier = Modifier.testTag(TAG_PULL_REFRESH),
    ) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxWidth().testTag(TAG_HOME_GRID),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 0.dp, 16.dp, 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 18.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        // **It used to count, and the count stopped meaning anything.**
                        //
                        // `homelab · %d of %d modules live` was worth saying while eight of the ten
                        // tiles were illustrative shapes. With only real modules left it reads
                        // "2 of 2", a ratio whose halves are now necessarily equal - REQ-0012
                        // Decision 3. Nothing was invented to replace it: there is nothing left to
                        // explain, and a launcher that stops explaining itself once it is
                        // self-evident is the honest outcome.
                        text = stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.onSurfaceSubtle,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .testTag(TAG_HOME_SUBTITLE),
                    )
                }
                // Held back until iteration 3 because until now it opened nothing, and a control
                // that does nothing is the same defect as the disabled Open button the module
                // placeholder does not have.
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag(TAG_OPEN_SETTINGS)) {
                        Icon(
                            painter = painterResource(AppIcons.Tune),
                            contentDescription = stringResource(R.string.modules_open),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        if (noticeTag != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                UpdateBanner(tag = noticeTag, onClick = onOpenUpdates)
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            UpdatesTile(
                installedVersion = installedVersion,
                updateStatus = updateStatus,
                noticeTag = noticeTag,
                onClick = onOpenUpdates,
            )
        }

        // **Tiles go out in PAIRS, and the pair decides the height.**
        //
        // The owner: *"они разные высоты — можешь пожалуйста сделать высоту одинаковой чтобы красиво
        // смотрелось"*. The cause is not a bug in the tile - `LazyVerticalGrid` lays a line out to its
        // tallest item and does NOT stretch the others, so two tiles whose subtitles wrap to different
        // numbers of lines are two different heights. One live tile says `10 of 10 available` and the
        // other says its tagline.
        //
        // `Row` at `IntrinsicSize.Min` asks both tiles how tall they need to be at this width and
        // takes the larger; `fillMaxHeight` then gives that height to both. **The height is still a
        // pure function of the text**, at whatever font scale the owner's phone is set to.
        //
        // `minLines` on the subtitle was considered and rejected: reserving two lines makes the tiles
        // equal only while no subtitle needs three, which is a property nothing holds and nobody would
        // notice breaking at fontScale 1.3. A fixed `height(148.dp)` or an `aspectRatio` is worse
        // still - it clips the first time the text is taller than the day it was measured.
        //
        // Every testTag survives this: `moduleTileTag` and `TAG_REACHABILITY_SUBTITLE` are inside the
        // tile, `TAG_HOME_GRID` is on the grid. Wrapping moves where a tag sits in the tree, never
        // what wears it - and a tag that vanished would take its test's coverage with it silently.
        items(
            modules.chunked(2),
            key = { pair -> pair.first().id },
            span = { GridItemSpan(maxLineSpan) },
        ) { pair ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                pair.forEach { module ->
                    ModuleTile(
                        module = module,
                        onClick = { onOpenModule(module) },
                        // **A verdict belongs to the module that produced it, not to whichever modules
                        // are live.** This read `module.live`, which was true for exactly one module
                        // when it was written and silently became true for two - so the Terminal tile
                        // advertised "10 of 10 available", a count of homelab SERVICES, about a list of
                        // terminal sessions.
                        //
                        // The owner reported it three times. It is one line, it is the first thing on
                        // screen, and it is the app asserting something it never measured.
                        verdict = verdict.takeIf { module.id == REACHABILITY_MODULE_ID },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                // An odd trailing tile keeps its half width rather than stretching across, which is
                // what the grid did before and what the design draws.
                if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }

        // REQ-0045. Full width under the module pair, like the Updates tile above them: two providers
        // in one tile because the question is one question - how much have I got left today. Null
        // draws nothing, which is what a preview or a test that is not about limits passes.
        if (limits != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LimitsTile(state = limits, now = Instant.now(), onForceRefresh = onForceRefreshLimits)
            }
        }

        // **The footer was deleted here on 2026-07-31**, with `TAG_HOME_FOOTER` and its string.
        //
        // It read "Modules appear here as they ship" and existed to explain the eight dashed shapes
        // below it. With nothing dashed on the screen it explained an absence the owner is no longer
        // looking at - REQ-0012 Decision 3. Checked before removing: the tag had no assertions
        // anywhere, so no coverage went quiet with it.
    }
    }
}

/**
 * The whole of the updater's presence on the launcher when there is something to say.
 *
 * A row rather than a dialog, and it can be walked straight past — which is what REQ-0003 means by
 * quiet and ignorable. There is nothing to dismiss because there is nothing in the way.
 *
 * Tapping it asks for a fresh check on the way through: the banner names a tag, and "tap to see what
 * changed" can only be honoured by release notes, which a remembered tag does not carry.
 */
@Composable
private fun UpdateBanner(tag: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .testTag(TAG_UPDATE_BANNER)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            painter = painterResource(AppIcons.SystemUpdateAlt),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.updates_available_short, tag),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(R.string.home_banner_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            painter = painterResource(AppIcons.ChevronRight),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The one tile that does something, which is why it looks unlike the others: filled rather than
 * dashed, full width, and with a real subtitle instead of a skeleton.
 *
 * The subtitle carries the installed version. That is not decoration - REQ-0002's acceptance ends
 * with the owner opening the app and seeing that it is now the new version, and this is where they
 * read it.
 */
@Composable
private fun UpdatesTile(
    installedVersion: String,
    updateStatus: UpdateStatus,
    noticeTag: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .testTag(TAG_UPDATES_ENTRY)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(AppIcons.SystemUpdateAlt),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(modifier = Modifier
            .weight(1f)
            .padding(start = 16.dp)) {
            Text(
                text = stringResource(R.string.updates_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Never says "ready" for a check that failed or has not finished: noticeTag is null in
            // both cases, and "up to date" is only claimed when a check actually said so.
            val subtitle = when {
                noticeTag != null ->
                    stringResource(R.string.home_updates_ready, installedVersion, noticeTag)
                updateStatus is UpdateStatus.UpToDate ->
                    stringResource(R.string.home_updates_current, installedVersion)
                else -> stringResource(R.string.home_updates_installed, installedVersion)
            }
            val subtitleColour =
                if (noticeTag != null || updateStatus is UpdateStatus.UpToDate) {
                    AppTheme.colors.success
                } else {
                    AppTheme.colors.onSurfaceSubtle
                }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColour,
                modifier = Modifier
                    .padding(top = 3.dp)
                    .testTag(TAG_UPDATES_SUBTITLE),
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

/**
 * A module, as a shape.
 *
 * The dashed border and the two shimmering bars are the whole message: something is intended here
 * and none of it exists. A tile that looked finished and did nothing would be worse than one that
 * says so, which is why the skeleton is not a placeholder for a design decision — it *is* the
 * design decision.
 */
@Composable
private fun ModuleTile(
    module: Module,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Non-null only for the live module. What it says is decided by [tileVerdictOf], not here. */
    verdict: TileVerdict? = null,
) {
    // **Every tile is a real one now**, so the branch that dressed a placeholder differently - dashed
    // outline, dimmer container, two shimmering bars instead of a sentence - is gone with the eight
    // shapes it was drawn for. What is left is what a working tile always looked like.
    //
    // Read outside the semantics lambda: it runs off the composition, so a resource lookup inside it
    // would be reading from the wrong place.
    val resources = LocalResources.current
    val description = verdict?.let { descriptionFor(it, resources) }.orEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 132.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .testTag(moduleTileTag(module.id))
            .then(
                if (verdict == null) Modifier
                else Modifier.semantics(mergeDescendants = true) {
                    contentDescription = description
                },
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(verdictColour(verdict), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(module.iconRes),
                contentDescription = null,
                tint = when {
                    verdict == null || verdict.level == TileLevel.NotChecked ->
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onPrimary
                },
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(module.nameRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            // The fact, not the glance. A tile whose only signal is a hue needs a legend, and there
            // is nothing on a launcher to compare it against.
            Text(
                text = when {
                    // The module's OWN words when it has no verdict, rather than a sentence borrowed
                    // from whichever module the fallback was written for.
                    verdict == null -> module.taglineRes?.let { stringResource(it) } ?: ""
                    verdict.busy -> stringResource(R.string.reach_tile_checking)
                    verdict.level == TileLevel.NotChecked ->
                        stringResource(R.string.reach_tile_not_checked)
                    else -> stringResource(
                        R.string.reach_tile_count,
                        verdict.available,
                        verdict.total,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceSubtle,
                modifier = Modifier.testTag(TAG_REACHABILITY_SUBTITLE),
            )
        }
    }
}

// `SkeletonBar` and `SHIMMER_SWEEP` were deleted here on 2026-07-31, with the eight placeholder tiles
// they shimmered on. `Modifier.dashedBorder` and its file went too - grepped first, and neither had
// any other caller.
//
// They were not decoration: REQ-0004's argument was that a tile which looked finished and did nothing
// would be worse than one that says so, and the dashes and bars were how it said so. That argument
// ends with the tiles, because every tile on this launcher now reaches something that exists.

/**
 * The verdict as a colour, and the only place a tone is chosen.
 *
 * Amber for the middle rather than a new colour role: it is what this app already uses for
 * "update available" and for the Degraded verdict, and "neither fine nor broken" is exactly what it
 * means in both. Grey for zero is the owner's own word. Neutral for not-yet-checked, because the
 * fixed green it replaces was green before anything had been asked.
 */
@Composable
private fun verdictColour(verdict: TileVerdict?): Color = when (verdict?.level) {
    null, TileLevel.NotChecked -> MaterialTheme.colorScheme.surfaceContainerHigh
    TileLevel.None -> AppTheme.colors.onSurfaceSubtle
    TileLevel.Some -> MaterialTheme.colorScheme.primary
    TileLevel.Most -> AppTheme.colors.success
}

/** The count, never the colour. A screen reader that says "green" has told the owner nothing. */
private fun descriptionFor(verdict: TileVerdict, resources: android.content.res.Resources): String =
    if (verdict.level == TileLevel.NotChecked) {
        resources.getString(R.string.reach_tile_description_not_checked)
    } else {
        resources.getString(R.string.reach_tile_description, verdict.available, verdict.total)
    }

internal fun moduleTileTag(id: String) = "module_tile_$id"

internal const val TAG_PULL_REFRESH = "home_pull_refresh"
internal const val TAG_REACHABILITY_SUBTITLE = "reachability_tile_subtitle"
internal const val TAG_HOME_GRID = "home_grid"
internal const val TAG_HOME_SUBTITLE = "home_subtitle"
internal const val TAG_UPDATES_ENTRY = "updates_entry"
internal const val TAG_UPDATE_BANNER = "update_banner"
internal const val TAG_OPEN_SETTINGS = "home_open_settings"
internal const val TAG_HOME_FOOTER = "home_footer"
internal const val TAG_UPDATES_SUBTITLE = "updates_tile_subtitle"

@Preview(name = "Launcher", widthDp = 412, heightDp = 892)
@Composable
private fun HomeScreenPreview() {
    AppTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            HomeScreen(
                modules = ModuleRegistry.all,
                installedVersion = versionLabel(),
                updateStatus = UpdateStatus.Idle,
                onOpenUpdates = {},
                onOpenModule = {},
                onOpenSettings = {},
                noticeTag = "v0.6.0",
            )
        }
    }
}

/**
 * The two tiles the owner was looking at, with a live verdict on one of them.
 *
 * **This is the case they reported**, and it is worth its own preview because the full launcher above
 * draws every tile at its shortest: the difference only appears once one subtitle wraps and the other
 * does not, which is what `10 of 10 available` beside a tagline does.
 *
 * Drawn at fontScale 1.0 and 1.3.
 *
 * **This preview does not verify anything, and nothing else in this repository does either.** There is
 * no Robolectric here, so no test can measure a laid-out tile; the emulator is dead, so `androidTest`
 * compiles and does not run; and this renders inside an IDE that neither the worker nor the PM is
 * looking through. It is here because it is the right thing to leave for whoever next opens this file
 * with one — not because it is evidence.
 *
 * **The owner's screen is the instrument.** The equal-height claim is unverified until the build is on
 * their phone. See REQ-0011's acceptance section, which was rewritten after an earlier draft named a
 * preview and a screenshot as though either could be produced here — that would have let a green gate
 * stand in for a check nobody performed, which is the exact failure this project keeps paying for.
 */
@Preview(name = "Live tiles, 1.0", widthDp = 412, heightDp = 420, fontScale = 1.0f)
@Preview(name = "Live tiles, 1.3", widthDp = 412, heightDp = 420, fontScale = 1.3f)
@Composable
private fun LiveTileHeightsPreview() {
    AppTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            HomeScreen(
                // Every module is a real one now, so this is the whole registry — which is also the
                // pair the owner was looking at when they reported the uneven heights.
                modules = ModuleRegistry.all,
                installedVersion = versionLabel(),
                updateStatus = UpdateStatus.Idle,
                onOpenUpdates = {},
                onOpenModule = {},
                onOpenSettings = {},
                // The exact subtitle the owner was looking at: `10 of 10 available`, which is the one
                // that wraps where the other tile's tagline does not.
                verdict = TileVerdict(level = TileLevel.Most, available = 10, total = 10, busy = false),
            )
        }
    }
}

/**
 * The one module a reachability verdict describes.
 *
 * Named here rather than compared inline, so the next module that goes live cannot inherit a number
 * that was never about it.
 */
private const val REACHABILITY_MODULE_ID = "reachability"
