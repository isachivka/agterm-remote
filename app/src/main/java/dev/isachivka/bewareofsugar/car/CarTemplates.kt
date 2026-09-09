package dev.isachivka.bewareofsugar.car

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.InputCallback
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.signin.InputSignInMethod
import androidx.car.app.model.signin.SignInTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import dev.isachivka.bewareofsugar.R
import dev.isachivka.bewareofsugar.agterm.BridgeSession
import dev.isachivka.bewareofsugar.agterm.SessionStatus

/**
 * What the terminal screen does when the host reports a press on its own strips. Only two things are
 * left there: the way back to the list, and pan mode. The keys, the microphone and the keyboard are
 * buttons the painter draws - see [CarKeyRow].
 */
class CarTerminalActions(
    val onSessions: () -> Unit,
    val onPanMode: (Boolean) -> Unit,
)

/**
 * Every template this app sends to the car, as functions of plain values.
 *
 * ### Why they are here and not in the screens
 *
 * The car library validates a template when it is BUILT: a fifth action on a strip, an empty list, a
 * title where none is allowed - each is an exception from the builder, and in a car that exception is
 * a closed app. A builder in a Screen callback is one nobody can run without a host. A builder here
 * runs in `CarTemplatesTest` on a plain device, where the library's own checks are the test.
 *
 * The screens hand these the state and the callbacks and send back what comes out.
 */
object CarTemplates {

    /** The list before the bridge has answered. The library forbids a list that is both loading and filled. */
    fun loading(context: Context): ListTemplate =
        ListTemplate.Builder()
            .setHeader(header(context))
            .setLoading(true)
            .build()

    /** One sentence, and the app icon above it. Used for every state that is not a list of sessions. */
    fun message(context: Context, text: CharSequence): MessageTemplate =
        MessageTemplate.Builder(text)
            .setHeader(header(context))
            .build()

    /**
     * The sessions, one section per workspace. [sections] must already be capped - see [carSections] -
     * and must not be empty, because an empty list is a builder exception; the screen shows [message]
     * for that.
     */
    fun sessions(context: Context, sections: List<CarSection>, onOpen: (BridgeSession) -> Unit): ListTemplate {
        val builder = ListTemplate.Builder().setHeader(header(context))
        for (section in sections) {
            val items = ItemList.Builder()
            for (session in section.sessions) {
                items.addItem(
                    Row.Builder()
                        .setTitle(sessionLabel(context, session))
                        .addText(context.getString(statusLabel(session.status)))
                        .setBrowsable(true)
                        .setOnClickListener { onOpen(session) }
                        .build(),
                )
            }
            builder.addSectionedList(SectionedItemList.create(items.build(), section.title))
        }
        return builder.build()
    }

    /**
     * The terminal: the surface underneath is painted by [CarTerminalPainter], buttons included; this
     * is what the host still puts on top of it.
     *
     * The navigation template refuses to build without an action strip, so the strip carries the one
     * thing that must exist and may fade: the way back to the list, because a navigation template has
     * no header to put a back arrow in. The map strip carries pan, which is the host's own gesture
     * mode and cannot be drawn by us. Everything else moved into the band on the owner's word on the
     * head unit - see [CarKeyRow].
     */
    fun terminal(context: Context, actions: CarTerminalActions): NavigationTemplate {
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(icon(context, R.drawable.ic_view_list))
                    .setOnClickListener { actions.onSessions() }
                    .build(),
            )
            .build()
        val map = ActionStrip.Builder()
            .addAction(Action.PAN)
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(map)
            .setPanModeListener { inPanMode -> actions.onPanMode(inPanMode) }
            .setBackgroundColor(CarColor.DEFAULT)
            .build()
    }

    /**
     * The car's keyboard, with the draft already in the field.
     *
     * A sign-in template with a text field rather than the search template: the search template's
     * keyboard ends in a magnifying glass, and the owner asked for "Ввод". The field's own submit is
     * what closes this; what was typed becomes the draft, whole, and Enter in the band sends it. The
     * host shows either template only while parked, which is its rule and the owner's.
     */
    fun draft(context: Context, initial: String, onSubmit: (String) -> Unit): SignInTemplate {
        val field = InputSignInMethod.Builder(
            object : InputCallback {
                override fun onInputSubmitted(text: String) = onSubmit(text)
                override fun onInputTextChanged(text: String) {}
            },
        )
            .setDefaultValue(initial)
            .setHint(context.getString(R.string.car_keyboard_hint))
            .setKeyboardType(InputSignInMethod.KEYBOARD_DEFAULT)
            .setShowKeyboardByDefault(true)
            .build()
        return SignInTemplate.Builder(field)
            .setTitle(context.getString(R.string.car_keyboard_title))
            .setInstructions(context.getString(R.string.car_keyboard_instructions))
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun header(context: Context): Header =
        Header.Builder()
            .setTitle(context.getString(R.string.car_sessions_title))
            .setStartHeaderAction(Action.APP_ICON)
            .build()

    private fun icon(context: Context, @DrawableRes drawable: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(context, drawable)).build()

    /** The name, then the title, then a label saying there is neither - a row must have a title. */
    fun sessionLabel(context: Context, session: BridgeSession): String =
        session.name.ifBlank { session.title }.ifBlank { context.getString(R.string.car_session_unnamed) }

    private fun statusLabel(status: SessionStatus): Int = when (status) {
        SessionStatus.Running -> R.string.agterm_status_running
        SessionStatus.NeedsYou -> R.string.agterm_status_needs_you
        SessionStatus.Done -> R.string.agterm_status_done
        SessionStatus.Idle -> R.string.agterm_status_idle
    }
}
