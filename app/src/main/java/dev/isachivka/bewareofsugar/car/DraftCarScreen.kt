package dev.isachivka.bewareofsugar.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Template

/**
 * The car's keyboard, over the draft.
 *
 * Reached only through a parked-only button, so the host has already decided the car is standing.
 * What is submitted replaces the draft whole and this screen goes away; the back arrow leaves the
 * draft as it was. Nothing typed here is kept anywhere but the draft in the terminal screen.
 */
class DraftCarScreen(
    carContext: CarContext,
    private val initial: String,
    private val onSubmit: (String) -> Unit,
) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        CarTemplates.draft(carContext, initial) { text ->
            onSubmit(text)
            screenManager.pop()
        }
}
