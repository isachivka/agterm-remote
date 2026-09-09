package dev.isachivka.agtermremote.ui.nav

/**
 * Where the app can be.
 *
 * Two destinations, neither carrying an argument. Deliberately not `navigation-compose`: two
 * destinations, no arguments, no deep links and no dynamic routes do not earn a dependency plus a
 * serialisation format.
 *
 * The trigger for revisiting: the first destination that carries an argument, or the first deep link.
 */
sealed interface Screen {

    /**
     * What `rememberSaveable` writes down.
     *
     * This is the "serialisation format" a routing library would have brought with it, and it is a
     * line rather than a dependency - but it is still real code that can be got wrong, so
     * `BackStackTest` round-trips every route rather than trusting it by inspection.
     */
    val route: String

    /** The session list and the terminal box. Where the app opens. */
    data object Agterm : Screen {
        override val route = "agterm"
    }

    /** Pairing this phone with a Mac, and the one switch that is not about pairing. */
    data object Settings : Screen {
        override val route = "settings"
    }

    companion object {
        /** Null for anything unrecognised - a route saved by an older version, most likely. */
        fun fromRoute(route: String): Screen? = when (route) {
            Agterm.route -> Agterm
            Settings.route -> Settings
            else -> null
        }
    }
}
