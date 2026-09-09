package dev.isachivka.bewareofsugar.ui.nav

import dev.isachivka.bewareofsugar.ui.module.Module
import dev.isachivka.bewareofsugar.ui.module.ModuleRegistry

/**
 * Where the app can be.
 *
 * A sealed interface rather than an enum, because [ModulePlaceholder] carries which module was
 * tapped, and an enum constant cannot carry anything. That is the whole reason this changed shape.
 * Five destinations, one of which carries data.
 *
 * Still deliberately not `navigation-compose`. Five destinations, one argument, no deep links and
 * no dynamic routes do not earn a dependency plus a serialisation format — and `MainActivity`'s old
 * comment named exactly the conditions under which to reconsider, of which precisely one has now
 * fired. Building a back stack because an enum cannot carry an id is the honest response to that
 * one; importing a router because the screen count went up is not.
 *
 * The trigger for revisiting stands: a sixth destination carrying arguments, or the first deep link.
 */
sealed interface Screen {

    /**
     * What `rememberSaveable` writes down.
     *
     * This is the "serialisation format" a routing library would have brought with it, and it is
     * ten lines rather than a dependency — but it is still real code that can be got wrong, so
     * `BackStackTest` round-trips every route rather than trusting it by inspection.
     */
    val route: String

    data object Home : Screen {
        override val route = "home"
    }

    data object Updates : Screen {
        override val route = "updates"
    }

    data object Token : Screen {
        override val route = "token"
    }

    /** Turning modules off. Arrives with the button that opens it, not before it. */
    data object ModulesSettings : Screen {
        override val route = "modules"
    }

    /**
     * A module's placeholder. The destination that made this a sealed interface rather than an enum.
     *
     * Carries the [Module] and not its id, so that "the module this screen is about" cannot be
     * absent. Holding an id would mean a lookup at render time that can never fail, and a branch
     * for the failure that can never run — and code for an impossible case is code nobody can test
     * or reason about. Here the only way to build one is from a registry entry, and restoring one
     * goes through [fromRoute], which does the lookup once at the boundary.
     */
    /**
     * The first module that does something.
     *
     * Its route sits in a `module/` namespace that once held placeholders too, because it *is* that
     * module — the difference was which screen the id resolved to, not where it lived. The
     * placeholders are gone as of REQ-0012 and the namespace is kept: routes are persisted state, and
     * renaming one is a migration for no benefit.
     */
    data object Reachability : Screen {
        override val route = "${MODULE_PREFIX}reachability"
    }

    /** REQ-0009's session list and terminal box. Same namespace, same reasoning. */
    data object Agterm : Screen {
        override val route = "${MODULE_PREFIX}agterm"
    }

    companion object {
        private const val MODULE_PREFIX = "module/"

        /** Null for anything unrecognised — a route saved by an older version, most likely. */
        fun fromRoute(route: String): Screen? = when {
            route == Home.route -> Home
            route == Updates.route -> Updates
            route == Token.route -> Token
            route == ModulesSettings.route -> ModulesSettings
            // Validated against the registry rather than accepted on shape. A module that has been
            // removed since the state was saved is not a screen this build can show, which is the
            // same version-skew case as an unrecognised route and gets the same answer.
            // Validated against the registry rather than accepted on shape, and now doubly so: a
            // module deleted since the state was saved - which is eight of them, as of REQ-0012 -
            // resolves to null and lands back at Home, the same answer an unrecognised route gets.
            route.startsWith(MODULE_PREFIX) ->
                ModuleRegistry.byId(route.removePrefix(MODULE_PREFIX))?.let(::screenFor)
            else -> null
        }

        /**
         * Where tapping a module goes, or null for one this build has no screen for.
         *
         * One place, used by the launcher and by [fromRoute], so a tap and a restore cannot disagree.
         *
         * ### It used to fall back to a placeholder, and losing that is the improvement
         *
         * The `else` branch sent anything unrecognised to `ModulePlaceholder`, which was right while
         * eight of the ten modules were illustrative shapes. It also meant a module that should have
         * had a screen and did not would land on a "not built yet" card **silently** — the failure
         * `ReachabilityTileTest` existed to make impossible by asserting every live module resolved to
         * something else.
         *
         * With the placeholders deleted there is no `else` to fall to, so the check moved from a test
         * watching for a bad fallback to **there being no fallback at all**. A module with no screen
         * returns null and `ModuleRegistryTest` fails on it, which is the same shape of mechanism as
         * the agterm command allowlist: the wrong thing is not caught, it is unsayable.
         */
        fun screenFor(module: Module): Screen? = when (module.id) {
            "reachability" -> Reachability
            "agterm" -> Agterm
            else -> null
        }
    }
}
