package dev.isachivka.bewareofsugar.update.play

import dev.isachivka.bewareofsugar.update.UpdateStatus

/**
 * What the launcher's Updates tile shows, now that the check behind it is Play's — REQ-0050.
 *
 * `HomeScreen` reads exactly two things about updates: whether the app is up to date, and the tag to
 * announce when something newer exists. It takes them in the GitHub path's vocabulary, and this is
 * the adapter that keeps that contract while the channel underneath changes.
 *
 * **This file exists to be deleted.** It is here so that hiding the GitHub updater did not also
 * require rewriting `HomeScreen` and every test that pins its tile - which would have made one
 * change into two, and neither reviewable. When the GitHub path goes, `HomeScreen` takes the Play
 * types directly and this file goes with it.
 */

/**
 * The tile's own state.
 *
 * Only [UpdateStatus.UpToDate] is produced, because it is the only case `HomeScreen` distinguishes;
 * everything else is [UpdateStatus.Idle], which draws nothing. In particular an *available* update
 * is NOT mapped to [UpdateStatus.Available] - that case carries a GitHub `Release`, an object Play
 * never gives us, and inventing one to satisfy a type is how a fake version number ends up on the
 * launcher. Availability travels as the notice tag instead.
 */
fun homeUpdateStatusOf(status: PlayUpdateStatus): UpdateStatus = when (status) {
    is PlayUpdateStatus.UpToDate -> UpdateStatus.UpToDate(status.checkedAtEpochSeconds)
    else -> UpdateStatus.Idle
}

/**
 * The tag the launcher announces, or null for silence.
 *
 * Silence in every state but one. A failed check, a Play that will not answer, and a copy that did
 * not come from Play are all reasons the owner has nothing to do about right now, and REQ-0003's
 * rule that the updater must be ignorable outlives the channel it was written for.
 *
 * [PlayUpdateStatus.InProgress] is silent too, and that is a deliberate choice rather than an
 * oversight: Play is already installing, so announcing an update would invite the owner to start one
 * on top of it.
 */
fun homeNoticeTagOf(status: PlayUpdateStatus): String? = when (status) {
    is PlayUpdateStatus.Available ->
        // Play speaks in version codes. The launcher speaks in version names, and falls back to the
        // raw code rather than to a name that never existed.
        versionNameOfCode(status.versionCode)?.let { "v$it" } ?: status.versionCode.toString()

    else -> null
}
