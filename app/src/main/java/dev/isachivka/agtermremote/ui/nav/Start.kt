package dev.isachivka.agtermremote.ui.nav

/**
 * Where the app opens, and the one fact that decides it.
 *
 * ### Why this is not simply the terminal
 *
 * The terminal is what the app is FOR, and for a paired phone it is the only sensible answer - the
 * owner opened this to read a session, not to admire a menu. But a phone with no laptop cannot show
 * a session at all. It opens on a failure card, and the single thing it needs to do next is behind a
 * settings button, which is a strange place to hide the first step of using an application.
 *
 * So the destination is conditional on one fact, and the fact is not "has the owner seen an
 * onboarding screen" or "is this the first run". Those are *guesses about a person*, they need their
 * own storage, and they go wrong in both directions: a reinstall shows the tour again to somebody who
 * knows it, and a cleared app looks experienced while holding nothing. **Whether a laptop is paired
 * is a fact about the device, it is already stored because the pairing needs it anyway, and it is the
 * same fact the terminal itself acts on.** One truth, read in two places, with nothing to keep in
 * step.
 *
 * ### Two, and no third
 *
 * Sealed, so a `when` over it is exhaustive and a third destination cannot be added without every
 * caller deciding what it means. `StartDestinationTest` spends that guarantee deliberately: its
 * `when` has no `else`, so a third variant stops the test suite compiling rather than silently
 * inheriting whatever the first branch does.
 */
sealed interface Start {

    /** No laptop yet. The app opens on the screen that pairs one. */
    data object Pairing : Start

    /** A laptop is paired, so the app opens on what it is for. */
    data object Terminal : Start
}

/**
 * The rule, as a function of the only thing it depends on.
 *
 * A `Boolean` parameter rather than a `PairedLaptop`, `Context` or store: this is the whole of the
 * decision, and taking the store would make the rule untestable without a filesystem while adding
 * nothing to it. Reading the store is the caller's job, and it happens once, at the point the app is
 * deciding what to draw.
 */
fun startDestination(hasPairedLaptop: Boolean): Start =
    if (hasPairedLaptop) Start.Terminal else Start.Pairing
