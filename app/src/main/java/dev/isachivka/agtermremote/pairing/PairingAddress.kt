package dev.isachivka.agtermremote.pairing

/**
 * The address a person compares against the laptop, rendered exactly one way.
 *
 * ### Why this is a function and not a string built at each call site
 *
 * The fingerprint cannot catch a wrong host. `bridgecert qr` reads the laptop's existing identity
 * rather than minting one, so **every QR this laptop generates carries the same certificate and shows
 * the same fingerprint** — a code built for the wrong host looks perfectly correct. The host and port
 * are the only fields in the payload that discriminate, which makes this string the one thing standing
 * between the owner and a pairing that completes and then never connects. That happened on
 * 2026-08-09, twice, and cost an afternoon.
 *
 * So the owner will read this off the phone and off the laptop and check that the two agree. **Two
 * renderings of one address is a comparison they perform correctly and still get wrong** — one padded
 * and one not, one hiding a port it considered default, one quietly stripping a trailing dot. This
 * function is the single source of that string, and the bridge face is expected to consume the same
 * one when it grows a window that shows the QR. [PairingAddressTest] is the contract both sides are
 * held to.
 *
 * ### Nothing here normalises
 *
 * No lowercasing, no trailing-dot stripping, no punycode, no eliding a "default" port. Every one of
 * those makes two *different* addresses render *identically*, which is precisely the failure this
 * exists to prevent. The rule is: **if two profiles would connect to different places, they must look
 * different on screen.** A host is displayed as the bytes the payload carried.
 *
 * ### It is for the screen, and only the screen
 *
 * [ConnectionProfile.toString] deliberately redacts the host and the port, because that type reaches
 * exception messages and log lines by accident. This function is the deliberate exception, for the one
 * place a person is being asked to look. **Its result must not be logged, must not enter an exception
 * message, and must not be written to disk.**
 */
object PairingAddress {

    /** The address inside a decoded pairing code, as the owner will read it. */
    fun of(profile: ConnectionProfile): String = of(profile.host, profile.port)

    /**
     * Host and port, always both.
     *
     * An IPv6 literal is bracketed, because otherwise the colon before the port is one of several and
     * the reader cannot tell where the address ends. A host that arrives already bracketed is left
     * alone rather than bracketed twice.
     */
    fun of(host: String, port: Int): String =
        if (needsBrackets(host)) "[$host]:$port" else "$host:$port"

    /**
     * A literal IPv6 address contains colons; a DNS name never does. Bracketing is decided by what the
     * string is, not by asking whether it parses — an unparseable host still has to be shown to the
     * owner rather than swallowed.
     */
    private fun needsBrackets(host: String): Boolean =
        host.contains(':') && !(host.startsWith("[") && host.endsWith("]"))

    /**
     * **The mirror of [of], and it lives in this file for that reason.**
     *
     * The address is one string everywhere it appears — the code's payload, this screen, the box the
     * owner edits it in. Reading one back is the same contract in the other direction, so both halves
     * sit in one file, are held to one case table, and change together. Split apart they drift, and a
     * formatter and a parser that disagree turn one destination into two.
     *
     * It is the Kotlin half of `DialAddress.parse`, case for case. The two cannot be linked, so the
     * table in [PairingAddressTest] is what holds them together.
     *
     * ### What it does not do
     *
     * It does not lowercase, does not strip a trailing dot, does not supply a default port and does
     * not guess. The single exception is the **brackets around an IPv6 literal**, which are removed:
     * they are the syntax that makes `host:port` unambiguous, not part of the name, and `[2001:db8::1]`
     * and `2001:db8::1` are the same machine. [of] puts them back.
     *
     * Whitespace around the outside is removed. It cannot be part of an address and no two
     * destinations differ by it — a phone keyboard adds one after a paste more often than not.
     */
    fun parse(text: String): TypedAddress {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return TypedAddress.Refused(AddressRefusal.Empty)

        val host: String
        val portText: String

        if (trimmed.startsWith("[")) {
            val close = trimmed.indexOf(']')
            if (close < 0) return TypedAddress.Refused(AddressRefusal.UnclosedBracket)
            host = trimmed.substring(1, close)
            val rest = trimmed.substring(close + 1)
            if (!rest.startsWith(":")) return TypedAddress.Refused(AddressRefusal.NoPort)
            portText = rest.substring(1)
        } else {
            when (trimmed.count { it == ':' }) {
                0 -> return TypedAddress.Refused(AddressRefusal.NoPort)
                1 -> Unit
                else -> return TypedAddress.Refused(AddressRefusal.AmbiguousWithoutBrackets)
            }
            host = trimmed.substringBefore(':')
            portText = trimmed.substringAfter(':')
        }

        if (host.isEmpty()) return TypedAddress.Refused(AddressRefusal.Empty)
        if (portText.isEmpty()) return TypedAddress.Refused(AddressRefusal.NoPort)
        // `toIntOrNull` and not a regex: it refuses a leading sign, spaces and anything non-decimal,
        // and it refuses a value too large for an Int rather than wrapping it into range.
        val port = portText.toIntOrNull()
            ?: return TypedAddress.Refused(AddressRefusal.PortNotANumber)
        if (port !in 1..65535) return TypedAddress.Refused(AddressRefusal.PortOutOfRange)
        return TypedAddress.Read(host, port)
    }
}

/**
 * An address a person typed, once it has been read — or the reason it was not.
 *
 * Sealed and exhaustive, so a new refusal cannot inherit somebody else's sentence. The screen that
 * consumes this is `PairedLaptopSection`, and the sentence is the whole of what it shows.
 */
sealed interface TypedAddress {

    /** Host and port, exactly as typed. Nothing here has been normalised — see [PairingAddress]. */
    data class Read(val host: String, val port: Int) : TypedAddress

    data class Refused(val why: AddressRefusal) : TypedAddress
}

/**
 * Every way `host:port` can fail to be an address, with the sentence that says what to do about it.
 *
 * **One sentence per reason, and each names an action.** The same house rule as [PairingOutcome], for
 * the same reason: the failure this project meets most often is a screen reporting something the
 * owner cannot act on. `AddressRefusalTest` asserts the property rather than the wording.
 *
 * The cases are `DialAddress.ParseFailure` on the Mac, one for one. That is the contract: the two
 * cannot be linked, so the *case table* is what holds them together, and a reader here that accepted
 * what the editor there refuses would store an address nobody could have typed.
 */
enum class AddressRefusal(val sentence: String) {

    Empty(
        "Type the address your Mac answers on, as host and port — for example " +
            "agterm.example-homelab.invalid:8443.",
    ),

    /** No colon at all, so no port. The field that most often differs between the two ends. */
    NoPort("That address has no port. Add one after a colon, as host:port."),

    PortNotANumber("What follows the colon is not a number. Write the port in digits, as host:port."),

    PortOutOfRange("A port has to be between 1 and 65535. Check the port on your Mac and type it again."),

    /**
     * More than one colon and no brackets. **This is the whole reason brackets exist**, and guessing
     * which colon separates the port would silently connect somebody somewhere else.
     */
    AmbiguousWithoutBrackets(
        "An IPv6 address needs square brackets so the port can be told apart. Write it as " +
            "[2001:db8::1]:8443.",
    ),

    UnclosedBracket(
        "That address opens a square bracket and never closes it. Write it as [2001:db8::1]:8443.",
    ),
}
