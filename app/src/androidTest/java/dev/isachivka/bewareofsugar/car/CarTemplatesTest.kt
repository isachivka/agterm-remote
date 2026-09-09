package dev.isachivka.bewareofsugar.car

import androidx.car.app.model.Action
import androidx.car.app.model.signin.InputSignInMethod
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.isachivka.bewareofsugar.agterm.AgtermUiState
import dev.isachivka.bewareofsugar.agterm.BridgeSession
import dev.isachivka.bewareofsugar.agterm.BridgeWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every template the car is sent, built on a device, where the library's own checks are the test.
 *
 * The car library validates a template when it is BUILT - action counts, empty lists, titles where
 * none are allowed - and throws. In a car that exception is a closed app; here it is a red test. A
 * device rather than the JVM because a click listener is a Binder, and the JVM's android.jar throws
 * on constructing one.
 */
@RunWith(AndroidJUnit4::class)
class CarTemplatesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun session(id: String, ws: String = "w1") =
        BridgeSession(id = id, workspaceId = ws, workspace = "", name = "session $id", title = "", active = false)

    private val actions = CarTerminalActions(onSessions = {}, onPanMode = {})

    @Test
    fun loadingBuilds() {
        assertTrue(CarTemplates.loading(context).isLoading)
    }

    @Test
    fun messageBuilds() {
        assertEquals("Not paired", CarTemplates.message(context, "Not paired").message.toString())
    }

    /** One action the host may fade - the way back - and pan on the map strip. The keys are painted. */
    @Test
    fun terminalCarriesOnlyTheWayBackAndPan() {
        val template = CarTemplates.terminal(context, actions)
        val strip = template.actionStrip
        assertNotNull(strip)
        assertEquals(1, strip!!.actions.size)
        val map = template.mapActionStrip
        assertNotNull(map)
        assertEquals(listOf(Action.TYPE_PAN), map!!.actions.map { it.type })
        assertNotNull(template.panModeDelegate)
    }

    @Test
    fun sessionsBuildsSectionsAndKeepsTheCap() {
        val many = (1..200).map { session("s$it", ws = if (it % 2 == 0) "w1" else "w2") }
        val state = AgtermUiState.Sessions(many, listOf(BridgeWorkspace("w1", "pets"), BridgeWorkspace("w2", "")))
        val sections = carSections(state, limit = 6, unnamed = context.getString(dev.isachivka.bewareofsugar.R.string.agterm_workspace_unnamed))
        val template = CarTemplates.sessions(context, sections) {}
        val rows = template.sectionedLists.sumOf { it.itemList.items.size }
        assertEquals(6, rows)
        assertEquals(listOf("pets"), template.sectionedLists.map { it.header.toString() })
    }

    @Test
    fun draftBuildsWithTheTextInTheField() {
        val template = CarTemplates.draft(context, "git status") {}
        val field = template.signInMethod as InputSignInMethod
        assertEquals("git status", field.defaultValue)
        assertTrue(field.isShowKeyboardByDefault)
    }
}
