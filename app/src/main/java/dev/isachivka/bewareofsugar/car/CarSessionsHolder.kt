package dev.isachivka.bewareofsugar.car

import android.content.Context
import dev.isachivka.bewareofsugar.agterm.AgtermSessions
import dev.isachivka.bewareofsugar.agterm.BridgeConnection
import dev.isachivka.bewareofsugar.agterm.BridgeSession
import dev.isachivka.bewareofsugar.pairing.PairedLaptop
import dev.isachivka.bewareofsugar.pairing.PhoneIdentity
import dev.isachivka.bewareofsugar.settings.StyledScreenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The car's own [AgtermSessions], and the scope it runs in.
 *
 * ### One holder, shared with nothing — REQ-0044 decision 3
 *
 * The phone keeps its holder in a ViewModel, which lives as long as the activity's screen does. The
 * car's lives as long as the host's session does, which is a different thing with a different end,
 * and the two may overlap: the owner may leave the phone open on a session and get into the car. So
 * the car gets its own connection to the bridge, which accepts a dozen at once, and its own polling
 * loop. Nothing is shared because nothing has the same lifetime.
 *
 * The state machine inside is the phone's, unchanged. What the car can ask of it is this class's
 * surface and no more: refresh, watch, stop, release. Create, rename, close, files and macros are
 * gestures for a person holding a phone, not a wheel, and they are not reachable from here.
 */
class CarSessionsHolder(
    val sessions: AgtermSessions,
    private val scope: CoroutineScope,
) {

    fun start() {
        sessions.refresh()
    }

    fun open(session: BridgeSession) = sessions.watch(session)

    fun leave() = sessions.stopWatching()

    /** The host's session is over. The connection goes, and so does everything it was polling with. */
    fun release() {
        sessions.release()
        scope.cancel()
    }

    companion object {
        /** Wired the way AgtermViewModel wires the phone's: same pairing, same identity, same switch. */
        fun forCar(context: Context): CarSessionsHolder {
            val appContext = context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val sessions = AgtermSessions(
                connect = {
                    val profile = PairedLaptop(appContext).read()
                    profile?.let { BridgeConnection.open(it, PhoneIdentity.keyManager()) }
                },
                keyState = { PhoneIdentity.signingState() },
                scope = scope,
                // Read from disk on every poll, as the phone does, so the switch in Settings reaches the
                // car on the next reply without the two holders sharing anything.
                styled = { StyledScreenStore(appContext.filesDir.path).read() },
            )
            return CarSessionsHolder(sessions, scope)
        }
    }
}
