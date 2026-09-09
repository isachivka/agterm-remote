package dev.isachivka.bewareofsugar.update

/**
 * When a launch check is allowed to run.
 *
 * REQ-0003: on launch, at most once a day, plus a manual check that always runs. A pure function of
 * two timestamps so the boundary is testable without waiting a day.
 */
object CheckSchedule {

    const val ONE_DAY_SECONDS: Long = 24 * 60 * 60

    /**
     * @param lastCheckedAtEpochSeconds 0 when no check has ever run.
     * @param manual true when the owner asked, which always wins — REQ-0003 requires a manual check
     * to be available, and one that answers "not yet, come back later" is not one.
     */
    fun isDue(lastCheckedAtEpochSeconds: Long, nowEpochSeconds: Long, manual: Boolean): Boolean = when {
        manual -> true
        lastCheckedAtEpochSeconds <= 0L -> true
        // The clock moved backwards - a timezone change, a manual clock set, or a reboot before NTP
        // caught up. Treating the stored time as being in the future would suppress every check
        // until real time caught up with it, which could be years. Check instead.
        nowEpochSeconds < lastCheckedAtEpochSeconds -> true
        else -> nowEpochSeconds - lastCheckedAtEpochSeconds >= ONE_DAY_SECONDS
    }
}
