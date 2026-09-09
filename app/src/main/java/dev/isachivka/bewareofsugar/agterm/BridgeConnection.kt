package dev.isachivka.bewareofsugar.agterm

import dev.isachivka.bewareofsugar.limits.LimitsSnapshot
import dev.isachivka.bewareofsugar.limits.parseLimits
import dev.isachivka.bewareofsugar.pairing.ConnectionProfile
import dev.isachivka.bewareofsugar.wire.TlsDriver
import dev.isachivka.bewareofsugar.wire.WebSocketStream
import dev.isachivka.bewareofsugar.wire.WireException
import dev.isachivka.bewareofsugar.wire.WireFailure
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager

/**
 * The two verbs, spoken down the stack iteration 2 built.
 *
 * ```
 * WebSocketStream   the router's own TLS, ordinary public-CA validation
 *   └─ TlsDriver    byte-exact pinned mTLS, terminating on the LAPTOP
 *        └─ here    newline-delimited JSON, two verbs and no way to ask for a third
 * ```
 *
 * **This layer adds no trust decision and must never acquire one.** It receives a stream that is
 * already pinned and already the right laptop; if this file ever needs to know about certificates,
 * something below it has stopped doing its job.
 *
 * Failures arrive as [WireException] from the layers below and are passed through untouched — the
 * whole point of [WireFailure] is that the copy branches on it, and re-describing a verdict here
 * would be a second vocabulary for the same facts.
 */
class BridgeConnection private constructor(
    private val transport: AutoCloseable?,
    private val reader: BufferedReader,
    private val writer: OutputStream,
) : AutoCloseable {

    /**
     * The sessions on the laptop.
     *
     * A refusal from the bridge is [BridgeRefused] rather than a [WireException]: the laptop answered,
     * and saying "cannot reach" about a machine that replied is the exact error this project has a
     * house rule about. What it means in practice is that the bridge could not talk to agterm, but
     * this code cannot observe that, so it does not claim it.
     */
    fun sessions(): SessionListing {
        val reply = exchange(JSONObject().put("verb", "sessions"))
        val array = reply.optJSONArray("sessions")
        val rows = if (array == null) emptyList() else (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let {
                BridgeSession(
                    id = it.optString("id"),
                    workspaceId = it.optString("workspace_id"),
                    workspace = it.optString("workspace"),
                    name = it.optString("name"),
                    title = it.optString("title"),
                    active = it.optBoolean("active"),
                    // **False when absent, which is what an older bridge means.** A session whose
                    // bridge cannot say gets no pane toggle, which is the same as a session with no
                    // split - and is the safe direction, because a toggle offering a pane that is not
                    // there would refuse on every press.
                    //
                    // **The key is `split` and the field is `splitPane`, deliberately.** The wire name
                    // outlived its meaning: renaming the key would make this phone read nothing from a
                    // Mac app the owner has not updated yet and offer the toggle NEVER, which is worse
                    // than the defect REQ-0034 fixes. Read the bridge's `api.Session.SplitPane`.
                    splitPane = it.optBoolean("split"),
                    // `optString` returns "" for a field that is absent, and absent is exactly what
                    // an idle session sends - so the two arrive here as the same thing and both
                    // decode to Idle. Anything unrecognised does too; see statusFromWire, where that
                    // is a decision rather than a fallback.
                    status = statusFromWire(it.optString("status")),
                )
            }
        }
        // **The width setting rides on this reply**, so the phone knows the state before the owner
        // touches anything. It used to come only from a resize response, and the toggle is gated on
        // having seen it - a deadlock where learning required pressing and pressing required knowing.
        // **Absent stays null rather than becoming an empty list.** A bridge too old to publish
        // workspaces and a laptop with no workspaces at all would otherwise arrive here identically,
        // and the second cannot happen - agterm keeps at least one. The grouping falls back to
        // deriving headings from the sessions when this is null; an empty list would wipe them.
        val spaces = reply.optJSONArray("workspaces")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let {
                    BridgeWorkspace(id = it.optString("id"), name = it.optString("name"))
                }
            }
        }
        return SessionListing(
            sessions = rows,
            workspaces = spaces,
            fitEnabled = readTriState(reply),
            fitColumns = reply.optInt("columns"),
        )
    }

    /**
     * Creates a workspace and returns its id.
     *
     * **No name is sent and none comes back.** The owner names it by renaming it afterwards, and
     * agterm's create replies carry an id and nothing else — established against the live socket on
     * 2026-07-31, after a first attempt read a name that was never there. The default label is read
     * from the next listing, which this screen fetches anyway.
     */
    fun createWorkspace(): String =
        exchange(JSONObject().put("verb", "workspace.create")).createdId()

    /**
     * Creates a session in one workspace and returns its id.
     *
     * **This moves the owner's laptop.** agterm focuses what it creates and offers no flag to suppress
     * it, so pressing + on the phone changes what their Mac is showing. Measured, not assumed.
     *
     * No command and no cwd are sent, and there is no parameter here that could carry one.
     */
    fun createSession(workspaceId: String): String =
        exchange(
            JSONObject().put("verb", "session.create").put("workspace", workspaceId),
        ).createdId()

    /** Renames one workspace. The bridge validates both the id and the label; so does this app. */
    fun renameWorkspace(workspaceId: String, label: String) {
        exchange(
            JSONObject()
                .put("verb", "workspace.rename")
                .put("workspace", workspaceId)
                .put("label", label),
        )
    }

    /**
     * Closes one of the owner's sessions. **Destroys their work; there is no undo.**
     *
     * Only ever the id of a row the phone drew and the owner long-pressed, then pressed Delete in.
     * The bridge refuses anything that is not a canonical UUID, because a partial target resolves to
     * `active` on agterm's side and would close whatever they are working in.
     */
    fun closeSession(sessionId: String) {
        exchange(JSONObject().put("verb", "session.close").put("session", sessionId))
    }

    /**
     * Deletes one of the owner's workspaces **and every session inside it**.
     *
     * Measured 2026-07-31: agterm takes the sessions with it, silently, and answers ok. The modal that
     * leads here names the count, because nothing on the far side will.
     */
    fun deleteWorkspace(workspaceId: String) {
        exchange(JSONObject().put("verb", "workspace.delete").put("workspace", workspaceId))
    }

    /** Renames one session. See [renameWorkspace]. */
    fun renameSession(sessionId: String, label: String) {
        exchange(
            JSONObject()
                .put("verb", "session.rename")
                .put("session", sessionId)
                .put("label", label),
        )
    }

    /**
     * The id a create answered with.
     *
     * A reply that claims success and names nothing is malformed rather than an empty success: the
     * caller is about to open a rename dialog on whatever came back, and doing that with an empty id
     * would address `active` at the far end - the owner's live session.
     */
    private fun JSONObject.createdId(): String {
        val id = optJSONObject("created")?.optString("id").orEmpty()
        if (id.isEmpty()) throw malformed()
        return id
    }


    /**
     * One session's last [lines] lines.
     *
     * [digest] is what this phone already holds. The bridge answers `unchanged` when it matches, and
     * that reply carries no text at all — 95% of idle polls, measured, which is the entire reason the
     * field exists rather than a refinement.
     */
    fun screen(
        sessionId: String,
        /**
         * **Required, and deliberately not defaulted** — REQ-0032.
         *
         * A default here is how this went wrong in the first place: with no pane named, agterm gives a
         * read the on-screen half and a keystroke primary, so the phone showed one and typed into the
         * other. A parameter the compiler will not let a caller omit is the smallest mechanism that
         * makes "both calls agree" something other than a thing to remember.
         */
        pane: Pane,
        lines: Int = DEFAULT_LINES,
        digest: String? = null,
        /**
         * Ask for the text with its colours, through the zmx daemon behind the pane. A preference the
         * bridge may decline silently — a pane without a daemon answers plain — which is why the reply's
         * own `styled` flag, not this argument, says what came back.
         */
        styled: Boolean = false,
    ): ScreenUpdate {
        val request = JSONObject()
            .put("verb", "screen")
            .put("session", sessionId)
            .put("pane", pane.wire)
            .put("lines", lines)
        if (!digest.isNullOrEmpty()) request.put("digest", digest)
        if (styled) request.put("styled", true)

        val reply = exchange(request)
        // **Read off the same reply as the text, including the `unchanged` one.** The setting is not a
        // property of there being new output, so taking it only from the text branch would leave it
        // uncorrected through the 95% of polls that convey nothing.
        val fit = FitState(enabled = readTriState(reply), columns = reply.optInt("columns"))
        if (reply.optBoolean("unchanged")) return ScreenUpdate(ScreenText.Unchanged, fit)
        // A reply that is neither `unchanged` nor carrying text is not something to paper over with an
        // empty string: it would render as a session that has gone blank, which is a thing a terminal
        // can genuinely be, so the two must not look alike.
        val body = if (reply.has("text")) reply.optString("text") else throw malformed()
        return ScreenUpdate(ScreenText.Text(body, reply.optString("digest"), styled = reply.optBoolean("styled")), fit)
    }

    /**
     * Sends a file to the laptop and returns the path the BRIDGE chose for it.
     *
     * **This app does not choose the path and could not be allowed to.** It sends a basename and the
     * bytes; the bridge decides where they land, under a directory of its own with a random component.
     * A caller that can name a path can name any path.
     *
     * The returned path goes into the DRAFT. Nothing here runs it — choosing a file must not execute a
     * command, which is why this returns a string rather than typing one.
     */
    fun sendFile(name: String, content: ByteArray): String {
        val reply = exchange(
            JSONObject()
                .put("verb", "file")
                .put("name", name)
                // Standard base64 with padding, no line wrapping: the bridge decodes strictly, and a
                // wrapped payload would arrive with newlines inside a newline-delimited protocol.
                .put("content", android.util.Base64.encodeToString(content, android.util.Base64.NO_WRAP)),
        )
        return reply.optString("path")
    }

    /**
     * Asks the laptop for this session's right-hand pane, **creating one if there is none** — REQ-0035.
     *
     * ### One call, two outcomes, and the reply does not say which
     *
     * Measured over agterm's control socket on 2026-08-25, on throwaway sessions created and closed
     * for the purpose. The same command:
     *
     *  - **creates** a right pane, running a login shell, when the session has never had one;
     *  - **reveals** the existing pane when one is there but collapsed on the Mac — same surface, and
     *    a marker typed into its shell survived, so nothing respawns;
     *  - **does nothing** when the pane is already on screen.
     *
     * The reply carries the session id in every case and no indication of which happened. So the
     * caller cannot report what it did from the answer, and [AgtermSessions.togglePane] does not try:
     * it asks the laptop what exists BEFORE calling, which is the only honest way to know.
     *
     * ### It starts a process on the owner's Mac
     *
     * The only call in this file that does. There is no confirmation in front of it, which is the
     * owner's own ruling — *"если сессия есть мы её показываем, если её нет мы её создаём и потом
     * показываем"* — taken after the mis-tap risk was put to him. **What bounds the damage is the
     * operation rather than a gesture:** it is idempotent, measured, so a repeated mis-tap cannot
     * produce a second shell.
     */
    fun openPane(sessionId: String) {
        exchange(JSONObject().put("verb", "pane.open").put("session", sessionId))
    }

    /**
     * How much of each subscription is left, from the bridge's cache — REQ-0045.
     *
     * **The `fresh` key is sent only when true.** The bridge's decoder refuses any key it does not
     * know with `malformed request`, so a routine poll that spelled out `"fresh": false` would be
     * refused by an older bridge with a sentence about the request's shape. Without the key an older
     * bridge says `unknown verb`. [dev.isachivka.bewareofsugar.limits.LimitsResults] reads both as
     * "update the bridge".
     */
    fun limits(fresh: Boolean): LimitsSnapshot {
        val request = JSONObject().put("verb", "limits")
        if (fresh) request.put("fresh", true)
        return parseLimits(exchange(request))
    }

    /**
     * Shows one pane at the full width of the terminal area on his Mac — REQ-0042.
     *
     * The owner: *"при переключении мы держим их фуллскрин"*. Showing a pane maximizes it, both
     * directions, and **nothing is restored afterwards** — no undo when he leaves the session, and no
     * putting his split back when the phone stops looking.
     *
     * ### It moves focus on his machine, and that is ordinary
     *
     * Every earlier design here routed around a rule that the phone must never do that. The rule was
     * invented on this side and he was never asked; when he was, he said *"не было никаких
     * ограничений, ты их придумал"*. Recorded rather than deleted, because the next person to find a
     * call that moves his focus should know it was authorised.
     *
     * ### A separate verb from [openPane], and not a flag on it
     *
     * `pane` was already a field the bridge understood, so a Mac app that predates this would accept
     * it on a `pane.open` and ignore it — asking to maximize the LEFT pane would silently CREATE a
     * split instead. An unknown verb is refused by name, which is loud and recoverable.
     *
     * **This one creates nothing.** A session with no split is refused by agterm with `session has no
     * split`, measured, and that refusal comes back rather than being swallowed.
     */
    fun showPane(sessionId: String, pane: Pane) {
        exchange(
            JSONObject()
                .put("verb", "pane.show")
                .put("session", sessionId)
                .put("pane", pane.wire),
        )
    }

    /**
     * Types into a session: literal [text], or a named [key] from the bridge's closed set.
     *
     * **Exactly one of the two**, because the bridge refuses both and refuses neither, and a phone
     * that guessed which one it meant would be inventing an intention.
     *
     * Nothing comes back but an acknowledgement. What the owner typed becomes visible when the next
     * poll returns it from the laptop — the only source of terminal content in this design. A reply
     * carrying the screen would make the phone's own keystroke look like output the laptop produced,
     * at the one moment nobody could tell the difference.
     */
    fun type(
        sessionId: String,
        /** Required for the same reason as [screen]'s, and it must be the SAME value. REQ-0032. */
        pane: Pane,
        text: String? = null,
        key: String? = null,
        paste: String? = null,
    ) {
        val request = JSONObject()
            .put("verb", "type")
            .put("session", sessionId)
            .put("pane", pane.wire)
        text?.let { request.put("text", it) }
        key?.let { request.put("key", it) }
        // **Its own field, and the bridge refuses more than one of the three** - REQ-0017. What makes
        // a paste different is not how it is sent but what it MEANS: the bridge wraps it in the
        // bracketed paste markers, so the far end puts the line breaks in its editor instead of
        // running them.
        paste?.let { request.put("paste", it) }
        exchange(request)
    }

    /**
     * Sends what this phone measured and returns the column count the LAPTOP measured.
     *
     * **The returned count is the measured one, not the requested one.** The bridge's search is
     * bounded, so it can stop a column or two short, and echoing the request back would tell this app
     * it had forty columns when it had thirty-eight — the exact lie that makes a wide line wrap where
     * nobody expects it.
     *
     * This is the only thing this app can ask the laptop to CHANGE. It cannot type, cannot run
     * anything, and cannot reach a session's contents; the bridge's allowlist fails its own build if
     * that stops being true.
     */
    /**
     * Applies a fit the laptop ALREADY HAS for this geometry, and asks it to measure nothing —
     * REQ-0041.
     *
     * **The automatic path, and it never calibrates.** A calibration is a dozen resizes over several
     * seconds across a window the owner may not be looking at; starting one because he tapped a row in
     * a list is a surprise from a machine he is not watching. An unmeasured geometry comes back as
     * [Refit.NeedsFit] and he is asked to press.
     *
     * **A bridge that predates `cached_only` refuses the whole request**, because the bridge disallows
     * unknown fields. That is the safe direction — no surprise calibration — and it is why every
     * failure here is swallowed by the caller: against an older bridge, switching sessions behaves
     * exactly as it did before this existed.
     */
    fun refit(
        sessionId: String,
        pane: Pane,
        boxWidthDp: Int,
        characterWidthMilliDp: Int,
        marginDp: Int,
    ): Refit {
        val reply = exchange(fitRequest(sessionId, pane, boxWidthDp, characterWidthMilliDp, marginDp)
            .put("cached_only", true))
        // **An ordinary reply, not a failure.** It rides on `ok:true` precisely so it cannot reach the
        // owner through the path that once replaced his terminal with a full-page error.
        if (reply.optBoolean("needs_fit")) return Refit.NeedsFit
        return Refit.Applied(FitState(enabled = readTriState(reply), columns = reply.optInt("columns")))
    }

    /** The fields both fit calls send. One builder, so the two cannot drift apart. */
    private fun fitRequest(
        sessionId: String,
        pane: Pane,
        boxWidthDp: Int,
        characterWidthMilliDp: Int,
        marginDp: Int,
    ): JSONObject = JSONObject().put("verb", "resize").put("session", sessionId)
        .put("character_width_milli_dp", characterWidthMilliDp)
        .put("box_width_dp", boxWidthDp)
        .put("margin_dp", marginDp)
        .put("pane", pane.wire)

    fun resize(
        sessionId: String,
        pane: Pane,
        boxWidthDp: Int,
        characterWidthMilliDp: Int,
        marginDp: Int,
        // **The long press, REQ-0016.** The bridge verifies a cached fit on every apply and corrects
        // itself when the terminal contradicts it; this is the owner saying "measure it again anyway".
        // Self-healing must not be the only escape from a wrong entry - that was the position they
        // were in on 2026-08-06, when the only way out was a text editor on the laptop.
        recalibrate: Boolean = false,
    ): FitState {
        val reply = exchange(
            JSONObject().put("verb", "resize").put("session", sessionId)
                // **Two MEASUREMENTS, and no column count.** The phone no longer asserts how many
                // columns fit - it says how wide its box is and how wide a character is, both read
                // from the layout and the font that actually draw, and the laptop answers with the
                // count its terminal really rendered.
                .put("character_width_milli_dp", characterWidthMilliDp)
                // **The measured box width is half the bridge's cache key.** It is what the layout
                // reported, never a figure computed here - computing it is what let our own edits move
                // the key while the truth stayed put, silently discarding the owner's calibration.
                .put("box_width_dp", boxWidthDp)
                // Recorded beside the fit so a human reading that file can see WHY a key changed.
                .put("margin_dp", marginDp)
                // **Which half he is looking at, because that is what the fit is FOR** — REQ-0036.
                //
                // The fit's target is the pane, not the window. Measured on his own machine: a session
                // whose divider sits at 0.286 renders 47 columns on the left and 121 on the right, so
                // a fit that did not know the side would be right for one of them and 2.5x wrong for
                // the other. **A REQUIRED argument, like [screen] and [type] take** — the same defect
                // that made those two required is this one, one layer up: a pane that defaults is a
                // pane that defaults DIFFERENTLY somewhere else.
                .put("pane", pane.wire)
                // Sent ONLY when asked for, so an ordinary press puts nothing new on the wire and an
                // older bridge sees exactly the request it has always seen.
                .apply { if (recalibrate) put("recalibrate", true) },
        )
        return FitState(
            enabled = readTriState(reply),
            columns = reply.optInt("columns"),
        )
    }

    /**
     * Puts the owner's window back the way they had it.
     *
     * No session, because there is nothing to measure — the bridge kept the geometry it captured
     * before the first resize, including zoom, which changes underneath a resize and would otherwise
     * leave the window in a state they did not choose.
     */
    fun restoreWindow(): FitState {
        // No box width means "stop adapting and put the window back" - see the bridge handler.
        val reply = exchange(JSONObject().put("verb", "resize"))
        return FitState(enabled = readTriState(reply), columns = 0)
    }

    /**
     * The setting as three states, because it has three and a boolean has two.
     *
     * **Absent or null is NOT-ANSWERED, and false is OFF.** The bridge sends the field always; an
     * older bridge that omits it is honestly unknown rather than off. Reading absence as false is the
     * mirror of the bug that made the toggle dead - the wire could not say off, so the phone assumed
     * unknown for ever.
     */
    private fun readTriState(reply: JSONObject): Boolean? =
        if (!reply.has("fit_enabled") || reply.isNull("fit_enabled")) null
        else reply.optBoolean("fit_enabled")

    /**
     * One request, one reply, on the one connection.
     *
     * The bridge answers a single JSON object per line. Anything unparseable is [WireFailure.Malformed]
     * — the far end spoke and what it said was not the protocol — which is a different fact from the
     * far end not speaking, and the copy says different things about them.
     */
    private fun exchange(request: JSONObject): JSONObject {
        writer.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
        writer.flush()

        val line = reader.readLine() ?: throw WireException(WireFailure.CannotReach)
        val reply = try {
            JSONObject(line)
        } catch (e: JSONException) {
            throw WireException(WireFailure.Malformed, e)
        }
        if (!reply.optBoolean("ok")) {
            // **Two kinds of no, and they are not interchangeable** - REQ-0017. A bridge that says
            // `refusal: "content"` is telling us the laptop is fine and this one request will never
            // work as sent; anything else, including an older bridge that says nothing, means what it
            // always meant. Reading the absence as "content" would be the dangerous direction: a dead
            // connection reported as a small notice.
            if (reply.optString("refusal") == REFUSAL_CONTENT) {
                throw ContentRefused(reply.optString("error"))
            }
            // `detail` is the far end's own words, present only when `error` is a sentence the BRIDGE
            // wrote. It is carried so it can be findable on the screen without being the screen — see
            // PLAN-0023, and the day the owner's terminal said "failed to read surface buffer" and
            // nothing else.
            throw BridgeRefused(reply.optString("error"), reply.optString("detail"))
        }
        return reply
    }

    private fun malformed() = WireException(WireFailure.Malformed)

    override fun close() {
        // Only the outermost thing needs closing: the driver holds no resource of its own, and the
        // WebSocket owns the socket underneath both.
        transport?.close()
    }

    companion object {
        /**
         * Bounded, and smaller than the bridge's own ceiling of 500.
         *
         * A phone shows a few dozen lines of large monospace text at most, and the honest number is
         * the one that fills the box a couple of times over rather than the largest the protocol
         * allows — every line past that is metered data moved to be scrolled past.
         *
         * **This is also the reach of text selection, and that makes it a limit somebody meets rather
         * than a number in a config.** Since REQ-0026 the terminal's text is selectable, and what a
         * finger can select is exactly what this constant fetched: the last 120 lines. More than a
         * screenful, and **not the session's history** — older output is not on the phone to be
         * selected, and no gesture can reach it.
         *
         * Raising it is not free in the direction it looks free: it is metered data on every poll,
         * for output the owner is not looking at. Reaching further back deliberately would be a
         * different feature — fetch on demand, not a bigger default.
         */
        const val DEFAULT_LINES = 120

        /**
         * Opens the whole stack for one paired laptop.
         *
         * The stream factory is injectable so tests can drive the two verbs over a plain pipe without
         * standing up TLS; **the pinning is not**, because it is built from the profile's own
         * certificate inside [TlsDriver.open] where no caller can substitute a weaker one.
         */
        fun open(
            profile: ConnectionProfile,
            identity: KeyManager,
            openStream: (String) -> WebSocketStream = { WebSocketStream.open(it) },
        ): BridgeConnection {
            val stream = openStream("wss://${profile.host}:${profile.port}/")
            val driver = TlsDriver.open(
                transportIn = stream.input,
                transportOut = stream.output,
                pinnedBridgeCertificate = profile.bridgeCertificate(),
                identity = identity,
            )
            return of(stream, driver.input, driver.output)
        }

        private fun of(transport: AutoCloseable?, input: InputStream, output: OutputStream) =
            BridgeConnection(transport, BufferedReader(InputStreamReader(input, Charsets.UTF_8)), output)

        /**
         * The seam the tests use: the two verbs over any pair of streams.
         *
         * It bypasses the transport and **not the protocol** — which is the point. What it cannot
         * bypass is the pinning, because that is built inside [open] from the profile's own
         * certificate, so no test and no caller can reach this class with a weaker trust decision
         * than the real one.
         */
        internal fun ofStreams(input: InputStream, output: OutputStream) = of(null, input, output)

        private fun ConnectionProfile.bridgeCertificate(): X509Certificate =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(bridgeCertificate.inputStream()) as X509Certificate
    }
}

/**
 * The bridge answered and said no.
 *
 * **Deliberately not a [WireFailure].** Those describe a connection; this describes a laptop that was
 * reached, authenticated and replied. Folding it in would let the copy say "your laptop is not
 * answering" about a machine that answered, which is the mistake this project keeps catching in
 * different clothes.
 *
 * The [reason] comes from the bridge's own closed set of messages. It is not shown to the owner: the
 * app cannot observe why agterm did not answer the bridge, and repeating a string as though it were a
 * diagnosis is asserting a cause by proxy.
 */
class BridgeRefused(val reason: String, val detail: String = "") : Exception(reason)

/** The bridge's word for a no that is about the request rather than about the laptop. */
const val REFUSAL_CONTENT = "content"

/**
 * The laptop is fine; **this request will never work as sent.**
 *
 * A separate type rather than a flag on [BridgeRefused], because the two demand opposite handling and
 * a boolean is something a `catch` can forget to read. REQ-0017: a complaint about the owner's TEXT
 * used to arrive as [BridgeRefused], which drops the connection and replaces the screen — so pasting
 * a message with a line break in it looked exactly like the laptop falling over.
 *
 * **[reason] is the bridge's own words, and it is for the log.** It names byte offsets and our
 * internal key vocabulary; it is not a sentence to put in front of the owner. What they see is
 * written on the phone, from what the phone knows.
 */
class ContentRefused(val reason: String) : Exception(reason)
