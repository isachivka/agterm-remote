package dev.isachivka.bewareofsugar.update

/**
 * Whether the launcher says anything about updates, and what tag it names.
 *
 * ## The problem this solves
 *
 * The app remembered *when* it last checked and not *what it found*. So an update announced at
 * 10:00 was gone at 10:05 after a restart — and the once-a-day rule then stopped the app from
 * rediscovering the answer it had already had. That was tolerable when the notice was one line
 * under a greeting. REQ-0004 makes it a banner on the launcher, which is the first thing the owner
 * sees, and a banner that forgets itself reads as a bug.
 *
 * ## A cache, never a claim
 *
 * The remembered tag is not evidence that an update exists now. It is evidence that one existed at
 * the last successful check. Every rule below follows from that one sentence:
 *
 * - **Revalidated on read.** The tag is compared against what is installed every single time. Once
 *   the owner updates, it stops being newer and stops being announced — it is not "cleared on
 *   install", because nothing tells this app an install happened.
 * - **A fresh answer always wins.** A live [UpdateStatus.Available] is announced from the live
 *   release, and a live [UpdateStatus.UpToDate] silences the notice outright — a check that just
 *   said "nothing newer" is not to be argued with by a memory.
 * - **A failed check creates nothing.** Nothing here turns a failure into a notice: on failure the
 *   remembered tag is whatever the last *successful* check wrote, unchanged. Being offline says
 *   nothing about whether a release exists, which is the same reasoning REQ-0003 uses for not
 *   dropping a stored token when the network is down.
 * - **It never decides whether to check.** `CheckSchedule` does, and does not consult this.
 *
 * ## Why the Updates screen does not use it
 *
 * The banner names a tag. The Updates screen shows release notes and offers a download, and a tag
 * alone cannot produce either — so that screen shows only what a live check returned. Tapping the
 * banner therefore asks for a real check on the way in, which is what "tap to see what changed"
 * has to mean.
 */
object UpdateNotice {

    /**
     * @param rememberedTag from [TokenStore.lastFoundTag].
     * @param installed what is running now, from `BuildConfig.VERSION_NAME`.
     * @return the tag to announce, or null to say nothing.
     */
    fun tagToAnnounce(
        status: UpdateStatus,
        rememberedTag: String?,
        installed: AppVersion?,
    ): String? = when (status) {
        // A live answer, and the only one that carries a release the Updates screen can use.
        is UpdateStatus.Available -> status.release.tag

        // A check that just succeeded and found nothing newer. Silences the memory rather than
        // being overruled by it.
        is UpdateStatus.UpToDate -> null

        // Nothing to check with, so nothing to act on either. Announcing an update the owner cannot
        // download until they deal with a token is a notice that only nags.
        UpdateStatus.NoToken -> null
        UpdateStatus.TokenUnreadable -> null

        // Everything else - not checked yet, mid-check, or a check that failed - leaves the last
        // successful answer standing. It is still the best thing known, and none of these states
        // contradicts it.
        else -> rememberedTag.takeIf { isNewerThanInstalled(it, installed) }
    }

    /**
     * Deliberately strict about what it will announce. A tag that cannot be parsed is not compared
     * optimistically — an unreadable version is not a newer one, and REQ-0003 already has a state
     * for a release the app cannot read.
     */
    private fun isNewerThanInstalled(tag: String?, installed: AppVersion?): Boolean {
        if (tag == null || installed == null) return false
        val remembered = AppVersion.parse(tag) ?: return false
        return remembered > installed
    }
}
