package dev.isachivka.bewareofsugar.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * What the Android Auto host binds - REQ-0044.
 *
 * ### The host is checked, and it is the only caller that passes
 *
 * The service is exported because the host is another process. An exported service that any app may
 * bind is an app that any app may drive: the terminal read, the keys pressed. So the validator is the
 * car library's allowlist of Google's host signatures, and nothing else binds. The spike that preceded
 * this admitted everyone; `CarManifestTest` makes sure that name never comes back.
 *
 * ### Navigation, on purpose
 *
 * The manifest declares the navigation category because that is the one whose surface Android Auto
 * lends to an app to draw on. This is not a navigation app and does not pretend to be one anywhere
 * but in that line; it is the price of a terminal that is a terminal rather than a list of cards.
 */
class TerminalCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session = TerminalCarSession()
}

/**
 * One connection to the car, and the holder that lives exactly as long as it does.
 *
 * The holder is made when the first screen is asked for, because `carContext` is not there before,
 * and released when the host ends the session, because a connection to the laptop with nobody to
 * read it is a thing that should not exist in a parked car.
 */
class TerminalCarSession : Session() {

    private var holder: CarSessionsHolder? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                holder?.release()
                holder = null
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val h = holder ?: CarSessionsHolder.forCar(carContext).also { holder = it }
        return SessionsCarScreen(carContext, h)
    }
}
