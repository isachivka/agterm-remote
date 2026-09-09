package dev.isachivka.bewareofsugar.update.play

import dev.isachivka.bewareofsugar.ui.AppIcons
import dev.isachivka.bewareofsugar.update.StatusPresentation
import dev.isachivka.bewareofsugar.update.StatusTone

/**
 * How loudly the Play status card speaks — REQ-0050.
 *
 * Reuses [StatusPresentation] and [StatusTone] rather than inventing a parallel vocabulary, so the
 * two paths cannot drift into looking like two different apps while both exist.
 *
 * The rule is the one the GitHub path already states and this one inherits unchanged:
 *
 * > **Loud styling is for something the owner just did that did not work.**
 *
 * Which is why a failed check is [StatusTone.Neutral] here too. Play being unreachable is not the
 * owner's doing, and an update screen that shouts about a tunnel teaches them to stop reading it.
 *
 * Pure, so every branch is a unit test rather than a screenshot.
 */
fun presentationOf(status: PlayUpdateStatus): StatusPresentation = when (status) {
    PlayUpdateStatus.Idle ->
        StatusPresentation(StatusTone.Neutral, AppIcons.Schedule)

    PlayUpdateStatus.Checking ->
        StatusPresentation(StatusTone.Neutral, AppIcons.Sync, spinner = true)

    is PlayUpdateStatus.UpToDate ->
        StatusPresentation(StatusTone.Success, AppIcons.CheckCircle)

    is PlayUpdateStatus.Available ->
        StatusPresentation(StatusTone.UpdateAvailable, AppIcons.SystemUpdateAlt)

    // Play is already doing it. Update-coloured rather than success-coloured, because something IS
    // happening and the owner should not read this as "nothing to do".
    is PlayUpdateStatus.InProgress ->
        StatusPresentation(StatusTone.UpdateAvailable, AppIcons.Sync, spinner = true)

    // The one state with something for the owner to act on, so it borrows the tone that used to
    // mean "there is something to do about the token". Nothing here is broken - the app simply
    // arrived by a route that no longer receives updates, and the car will not run it either.
    PlayUpdateStatus.NotFromPlay ->
        StatusPresentation(StatusTone.NeedsToken, AppIcons.KeyOff)

    // Not Success, deliberately, and this is the distinction the whole state exists for: Play saying
    // it does not know is not Play saying there is nothing.
    is PlayUpdateStatus.Unknown ->
        StatusPresentation(StatusTone.Neutral, AppIcons.Error)

    is PlayUpdateStatus.Failed ->
        StatusPresentation(StatusTone.Neutral, AppIcons.Error)
}
