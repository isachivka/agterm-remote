package dev.isachivka.agtermremote.agterm

/**
 * What "back" means inside the agterm screen, stated once.
 *
 * ### The bug
 *
 * *"если я нахожусь в сессии и использую жест назад стандартный андроидовский то меня выбрасывает на
 * хоумскрин а не назад к списку сессий"*. The back arrow in the header did the right thing and the
 * system gesture left the app, because the screen held a notion of where the owner was that Android
 * knew nothing about: the app-level [BackStack] sees one agterm destination, and an open session is a
 * place *inside* it.
 *
 * ### One definition, used by both
 *
 * The arrow and the gesture call the same function. Two functions that do the same thing today do
 * different things by next week — and this screen already had that shape, with the arrow knowing about
 * an open session and the gesture not.
 *
 * ### The order, and why it is here rather than emerging from whoever intercepts first
 *
 * 1. **The keyboard**, which Android almost always consumes before this code runs. Nothing here has
 *    to handle it, and nothing here should pretend to.
 * 2. **The typing bar**, if open — and with the *same* cleanup the toggle does, keyboard and focus
 *    included. Back must never leave a keyboard hanging over a closed bar; that is the complaint the
 *    owner already made about dismissal leaving focus behind.
 * 3. **The session**, back to the list.
 * 4. **Only then** does back mean leave the app, and from the list it behaves exactly as it does
 *    today — this file changes nothing about that.
 *
 * ### Enabled only when there is something to do
 *
 * [handles] is what keeps the gesture working everywhere else. A handler that is always on swallows
 * back on the list and strands the owner inside the app, which is a worse bug than the one being
 * fixed.
 */
object BackWithin {

    /** What a back press should do, given where the owner is. */
    enum class Action {
        /** Put the input bar away, with the keyboard and the focus that came with it. */
        CloseTyping,

        /** Leave the session, back to the list. */
        CloseSession,

        /** Nothing this screen owns — let the app's own back stack answer, as it does today. */
        LeaveScreen,
    }

    /**
     * The stack, innermost first.
     *
     * The typing bar is checked before the session because it is *inside* it: closing the session
     * while the bar is open would take away both in one press and leave the owner wondering which one
     * the gesture meant.
     */
    fun action(typingOpen: Boolean, inSession: Boolean): Action = when {
        typingOpen -> Action.CloseTyping
        inSession -> Action.CloseSession
        else -> Action.LeaveScreen
    }

    /**
     * Whether this screen should intercept back at all.
     *
     * **False on the list, and that is the important half.** The system gesture has to keep working
     * for its normal purpose; a screen that always intercepts is a screen the owner cannot leave.
     */
    fun handles(typingOpen: Boolean, inSession: Boolean): Boolean =
        action(typingOpen, inSession) != Action.LeaveScreen
}
