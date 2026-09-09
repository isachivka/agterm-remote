package dev.isachivka.bewareofsugar.ui.module

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.isachivka.bewareofsugar.R
import dev.isachivka.bewareofsugar.ui.AppIcons

/**
 * One tile on the launcher, and every one of them reaches something that exists.
 *
 * ### The eight illustrative modules are gone, and this supersedes REQ-0004
 *
 * REQ-0004's central statement was eight illustrative shapes — dashed borders, shimmering skeleton
 * bars — so the launcher said what the app intends to become. The owner deleted that on 2026-07-31,
 * after a day of living with it: *"модули которых у нас нет продолжают рендериться — убери все модули
 * которых у нас нет"*. **REQ-0012 overrides REQ-0004 here**, on their authority, and it is not
 * re-argued.
 *
 * Note what REQ-0011 got right and what it could not have: it kept these tiles, reading *"all modules
 * that exist in the app should show on the home page"* as a request for the list to be unfilterable
 * rather than shorter. That was the right reading of the sentence available then. They have since said
 * the other half out loud.
 *
 * ### There is no `live` flag any more
 *
 * There was one, and it earned its place: the launcher's count asked which tiles were real, and
 * `Screen.screenFor` sent a live module to its own destination and everything else to a placeholder.
 * With no placeholders left it would be **true for every row** — and a flag that is true for every row
 * today is a flag whose next reader will get it wrong.
 *
 * What replaced it is stronger than what it did. `screenFor` is now total over this list, and
 * `ModuleRegistryTest` walks the registry asserting every entry resolves to a screen. A module with no
 * destination is a failing test rather than a tile that silently lands on a placeholder — the same
 * shape of mechanism as the agterm command allowlist.
 *
 * [nameRes] rather than a `String`, because every user-visible string in this app lives in
 * `strings.xml`.
 */
data class Module(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:DrawableRes val iconRes: Int,
    /**
     * What a LIVE tile says under its name.
     *
     * Carried by the module rather than chosen at the tile, because the alternative was one string
     * shared by whichever modules happened to be live - which is how the Terminal tile came to read
     * "What you can reach from this network", a sentence about homelab services, under a list of
     * terminal sessions. Same defect as the verdict beside it, same cause: a value describing
     * something other than the thing it names.
     */
    @param:StringRes val taglineRes: Int? = null,
)

/**
 * Every module this app has, which is every module it draws.
 *
 * One list in one file, so adding a module later is an entry plus a screen rather than a refactor.
 * **Adding one without a screen is a failing test**, not a placeholder — see `ModuleRegistryTest`.
 */
object ModuleRegistry {

    val all: List<Module> = listOf(
        // First, because it is the older of the two and the launcher leads with it.
        Module(
            "reachability", R.string.module_reachability, AppIcons.NetworkCheck,
            taglineRes = R.string.module_reachability_tagline,
        ),
        // REQ-0009's session list. It took the Terminal icon from what was then a placeholder called
        // Logs, on the grounds that a real feature should not carry a worse icon so a shape could keep
        // the right one. Logs is deleted now, so the argument is settled rather than merely won.
        Module(
            "agterm", R.string.module_agterm, AppIcons.Terminal,
            taglineRes = R.string.module_agterm_tagline,
        ),
    )

    fun byId(id: String): Module? = all.firstOrNull { it.id == id }
}

// `TileCount`, `tileCount` and `UPDATES_TILE` lived here and were deleted on 2026-07-31 with the
// launcher subtitle that consumed them.
//
// They existed to answer "how many of these tiles are real", which was a fair question while eight of
// ten were shapes. With only real modules left the answer is "all of them", and the line read
// `homelab · 2 of 2 modules live` — a ratio whose halves are now necessarily equal, which is a count
// of nothing anybody wondered about. REQ-0012 Decision 3.
//
// Nothing was invented to fill the space: the subtitle says `homelab` and the footer is gone.
