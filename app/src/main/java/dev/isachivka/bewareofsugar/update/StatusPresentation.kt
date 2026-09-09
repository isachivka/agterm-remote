package dev.isachivka.bewareofsugar.update

import androidx.annotation.DrawableRes
import dev.isachivka.bewareofsugar.ui.AppIcons

/**
 * How loudly a status card speaks.
 *
 * The design colours five states by hand. The model has thirteen, and the other eight are not
 * allowed to fall through to a default nobody chose — so this is an exhaustive `when` and a
 * fourteenth state will fail to compile rather than render grey.
 *
 * The rule for which tone, stated once because it is the thing worth arguing about:
 *
 * > **Loud styling is for something the owner just did that did not work.**
 *
 * That is REQ-0003's quiet-failure rule generalised. A background check that failed is not the
 * owner's doing and is usually not their problem, so it gets a sentence and no alarm. The error
 * colours appear in exactly two places in this app, and neither is here: a token GitHub rejected
 * while the owner watched, and a download that failed after they tapped Update.
 */
enum class StatusTone {
    /** Nothing is wrong and nothing is required. Also where every transient failure lands. */
    Neutral,

    /** Up to date. */
    Success,

    /** There is a newer release. The one state the launcher also announces. */
    UpdateAvailable,

    /** There is something to do with the token before updates can work. */
    NeedsToken,
}

/**
 * @param spinner true when the icon should be a moving progress indicator instead. The design draws
 * a static `sync` glyph for the checking state, and a spinner that does not spin is worse than no
 * spinner at all.
 */
data class StatusPresentation(
    val tone: StatusTone,
    @param:DrawableRes val icon: Int,
    val spinner: Boolean = false,
)

/** Pure, so all thirteen branches are a unit test rather than thirteen screenshots. */
fun presentationOf(status: UpdateStatus): StatusPresentation = when (status) {
    UpdateStatus.Idle ->
        StatusPresentation(StatusTone.Neutral, AppIcons.Schedule)

    UpdateStatus.Checking ->
        StatusPresentation(StatusTone.Neutral, AppIcons.Sync, spinner = true)

    is UpdateStatus.UpToDate ->
        StatusPresentation(StatusTone.Success, AppIcons.CheckCircle)

    is UpdateStatus.Available ->
        StatusPresentation(StatusTone.UpdateAvailable, AppIcons.SystemUpdateAlt)

    // The design's own choice for "no token", and the four below say the same sentence in different
    // words - there is something to do with the token - so they are treated the same way.
    UpdateStatus.NoToken ->
        StatusPresentation(StatusTone.NeedsToken, AppIcons.KeyOff)

    UpdateStatus.TokenUnreadable ->
        StatusPresentation(StatusTone.NeedsToken, AppIcons.KeyOff)

    UpdateStatus.TokenExpired ->
        StatusPresentation(StatusTone.NeedsToken, AppIcons.KeyOff)

    UpdateStatus.TokenNotAccepted ->
        StatusPresentation(StatusTone.NeedsToken, AppIcons.KeyOff)

    UpdateStatus.NoRepoAccess ->
        StatusPresentation(StatusTone.NeedsToken, AppIcons.KeyOff)

    UpdateStatus.InsufficientPermission ->
        StatusPresentation(StatusTone.NeedsToken, AppIcons.KeyOff)

    // Transient and nobody's fault. GitHub is busy, the train went into a tunnel, or a release has
    // a tag this app cannot parse - none of which the owner did, and none of which is worth alarm.
    is UpdateStatus.RateLimited ->
        StatusPresentation(StatusTone.Neutral, AppIcons.Schedule)

    UpdateStatus.Offline ->
        StatusPresentation(StatusTone.Neutral, AppIcons.Error)

    is UpdateStatus.GitHubUnavailable ->
        StatusPresentation(StatusTone.Neutral, AppIcons.Error)

    UpdateStatus.UnreadableRelease ->
        StatusPresentation(StatusTone.Neutral, AppIcons.Error)
}
