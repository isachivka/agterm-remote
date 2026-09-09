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
}
