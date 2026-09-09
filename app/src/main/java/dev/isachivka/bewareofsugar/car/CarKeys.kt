package dev.isachivka.bewareofsugar.car

import androidx.annotation.StringRes
import dev.isachivka.bewareofsugar.R
import dev.isachivka.bewareofsugar.agterm.ENTER_KEY

/** One key in the car's button row: the bridge's name for it, and the label the owner reads. */
data class CarKey(val wire: String, @param:StringRes val label: Int)

/**
 * The keys the car carries, in the phone's order.
 *
 * *"я хочу нашу клавиатуру с кнопкой Enter, Ctrl-C и так далее"* — REQ-0044. The phone's bar keeps
 * these four and a macro, and after two weeks of daily use the owner had pressed nothing else
 * (REQ-0028); Backspace joined on the head unit. They are drawn by [CarKeyRow] in the band with the draft, which is where the owner
 * asked for them once he had seen the host's own strip fade away.
 *
 * The wire names are the bridge's closed set, spelled as `KEYS_ON_THE_BAR` spells them; the test
 * checks each against that list, so a name the bridge might refuse cannot be discovered in a car.
 */
val CAR_KEYS: List<CarKey> = listOf(
    CarKey(ENTER_KEY, R.string.agterm_key_enter),
    CarKey("interrupt", R.string.agterm_key_interrupt),
    CarKey("escape", R.string.agterm_key_escape),
    CarKey("tab", R.string.agterm_key_tab),
    // Asked for on the head unit: *"кнопка «Backspace» не помешала бы"*. A fifth key was never a
    // problem for a row we draw ourselves.
    CarKey("backspace", R.string.agterm_key_backspace),
)
