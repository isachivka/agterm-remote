import Foundation

/// The address the phone will dial, kept in this app's own preferences.
///
/// ### Why the app owns it rather than a file beside the bridge's certificates
///
/// This value belongs to whoever is looking at the pairing window, and it is the app that asks for
/// it, refuses a wrong one in words, and shows it back. The bridge never reads it: the bridge listens
/// on an address, and **the listen address is not the dial address** — a bridge bound to `0.0.0.0` is
/// reachable at whatever name the router forwards, which is the half only a person knows. Putting the
/// dial address in the bridge's configuration would put the two beside each other and invite exactly
/// the confusion that makes a pairing code pair perfectly and then never connect.
///
/// ### One value, one shape
///
/// Stored as `displayed` renders it and read back through `DialAddress.parse`, which are the two
/// halves of one contract living in one file. Storing host and port as separate keys would be a
/// second shape for an address whose entire point is that it is **one string a person compares
/// against another one string** — the phone shows `host:port`, the code carries `host:port`, and the
/// box they type it into holds `host:port`.
public enum AddressPreference {

    /// Missing is not an error. It is the first run, and the caller has a screen for it.
    public enum ReadFailure: Error, Equatable {
        case unset
        /// Present and not an address. Carries nothing from the store: a malformed address has no
        /// business travelling in an error, and there is no half of one worth showing to anybody.
        case malformed
    }

    /// The one key. Named for what it is from the phone's point of view, because that is the only
    /// point of view from which this value means anything.
    ///
    /// It lives in this app's own defaults domain, `dev.isachivka.agtermremote`, so the fully
    /// qualified name of the preference is that domain and this key. The design document spells it
    /// as one string; it is one value either way, and renaming the key now would orphan every
    /// address already stored by a shipped build.
    public static let key = "dialAddress"

    /// When the address currently stored was stored. Written by [write], and **only** when the
    /// address actually changes: re-saving the same address is not a new address and must not
    /// discard the proof it has already earned.
    public static let storedAtKey = "dialAddressStoredAt"

    /// The port traffic arrives on at this Mac, when it is not the port the phone dials.
    ///
    /// **Absent means follow the dial port**, which is the straight-through case and the common one.
    /// Storing a copy of the dial port instead would go stale the moment somebody edited the address,
    /// and the person it went stale for would be the person with the simplest setup.
    public static let listenPortKey = "listenPort"

    /// When a phone last completed enrolment through the address stored now.
    ///
    /// **Nil is the ordinary state and it is not an error.** It says the address is unproven, which
    /// is what every address is until something has actually come through it — see [provenAt].
    public static let provenKey = "addressProvenAt"

    /// Reads the address, or says why not.
    public static func read(from defaults: UserDefaults = .standard) -> Result<DialAddress, ReadFailure> {
        guard let stored = defaults.string(forKey: key), !stored.isEmpty else { return .failure(.unset) }
        // The same parser every typed address goes through. A second, more forgiving reader here would
        // accept a value the editor refuses, and the stored address would then be one the owner could
        // not have entered.
        switch DialAddress.parse(stored) {
        case .success(let address): return .success(address)
        case .failure: return .failure(.malformed)
        }
    }

    /// Exactly what is in the store, unparsed, or nil if nothing is.
    ///
    /// For the one caller that must be able to see a value the parser rejects: onboarding decides
    /// which pane a person is on, and a malformed leftover is the same situation as an empty store —
    /// but reading the key directly at that call site would make a second reader of the one key, and
    /// that is how a box and a code end up disagreeing about what is saved.
    public static func stored(in defaults: UserDefaults = .standard) -> String? {
        defaults.string(forKey: key)
    }

    /// The arrival port the owner set, or nil when they have not set one and it follows the dial port.
    ///
    /// A stored value outside 1–65535 is read as nil rather than handed on: `writeListenPort` refuses
    /// to store one, so anything else in there was not put there by this app, and the safe reading of
    /// a nonsense port is *there is no override*.
    public static func listenPort(from defaults: UserDefaults = .standard) -> Int? {
        guard defaults.object(forKey: listenPortKey) != nil else { return nil }
        let stored = defaults.integer(forKey: listenPortKey)
        return (1...65535).contains(stored) ? stored : nil
    }

    /// Sets the arrival port, or clears it back to following the dial port.
    public static func writeListenPort(_ port: Int?, to defaults: UserDefaults = .standard) throws {
        guard let port else { return defaults.removeObject(forKey: listenPortKey) }
        guard (1...65535).contains(port) else { throw WriteRefusal.wouldNotReadBack("\(port)") }
        defaults.set(port, forKey: listenPortKey)
    }

    /// Refused before anything is stored.
    ///
    /// Only one shape, because there is only one thing that can be wrong here: what would be written
    /// is not what would be read back. Carries the rendering, never a half-parsed field.
    public enum WriteRefusal: Error, Equatable {
        case wouldNotReadBack(String)
    }

    /// Writes the address, **after proving it survives its own round trip**.
    ///
    /// `DialAddress.init` is public and validates nothing, so a caller can hand this a value the
    /// reader will not accept. That is not hypothetical: `DialAddress(host: "-", port: 0)` is already
    /// constructed elsewhere in this package as a placeholder for a status line, `("h", 70000)` has a
    /// port no reader will take, and `("[fe80::1]", 8443)` and `(" h ", 1)` round-trip to a
    /// *different host* — the brackets and the spaces belong to the syntax, not to the name.
    ///
    /// Without this check the invariant this type claims — a stored address is always one somebody
    /// could have typed — was a sentence in a comment. Writing goes through `displayed` and reading
    /// through `parse`, so the honest test is to run both and compare: anything that does not come
    /// back identical is refused, and the store never holds a value the editor would reject.
    ///
    /// This is also what makes `throws` real. `UserDefaults` itself reports no failure, so before
    /// this the seam existed only to be injected in tests; now the one thing that can actually stop a
    /// write is checked here, and `SaveAddress` reports it as *the address is the problem, not the
    /// store* — a different sentence from a refusal, and a different one from a failed save.
    public static func write(
        _ address: DialAddress, to defaults: UserDefaults = .standard, at when: Date = Date(),
    ) throws {
        let rendered = address.displayed
        guard case .success(let readBack) = DialAddress.parse(rendered), readBack == address else {
            throw WriteRefusal.wouldNotReadBack(rendered)
        }
        // **A new address inherits nothing.** Proof is proof about one destination: a phone that came
        // through the old one says nothing about this one, and carrying the date across would mark an
        // address proven that nothing has ever reached. Re-saving the SAME address is not a change —
        // somebody pressing Save twice, or confirming a suffix — so its proof survives.
        if defaults.string(forKey: key) != rendered {
            defaults.removeObject(forKey: provenKey)
            defaults.set(when, forKey: storedAtKey)
        }
        defaults.set(rendered, forKey: key)
    }

    /// When a phone completed enrolment through the address stored now, or nil while it is unproven.
    public static func provenAt(from defaults: UserDefaults = .standard) -> Date? {
        defaults.object(forKey: provenKey) as? Date
    }

    /// Records what the trust store says, **after checking that it says it about this address**.
    ///
    /// The whole rule is the comparison: an enrolment is proof of the address that was stored when it
    /// happened. An enrolment older than the address it is being credited to belongs to the previous
    /// one, and crediting it would put a proven mark on a destination nothing has ever reached — the
    /// same class of mistake as a green tick for an address that merely resolves.
    ///
    /// ### The address that was already there
    ///
    /// An address stored by a build from before this rule existed has no moment to compare against.
    /// Answering *unproven* forever was the first attempt and it contradicted the reason this file
    /// gives for keeping the key name: shipped builds hold addresses, and the owner of one of them
    /// has very likely had a phone paired for weeks. They would have been sent back into setup at
    /// every launch, by a rule invented after their setup was finished.
    ///
    /// So an address with no timestamp is **backfilled as older than any enrolment**, once, here.
    /// That credits it with an enrolment it did earn: the only address this app has ever stored is
    /// the one in the store, and the only thing that writes a trust store is a phone completing
    /// enrolment through it. From the next save onwards the ordinary rule applies.
    @discardableResult
    public static func recordEnrolment(
        at enrolment: Date?, in defaults: UserDefaults = .standard,
    ) -> Date? {
        if defaults.string(forKey: key) != nil, defaults.object(forKey: storedAtKey) == nil {
            defaults.set(Date.distantPast, forKey: storedAtKey)
        }
        let proof = proof(enrolment: enrolment, addressStoredAt: defaults.object(forKey: storedAtKey) as? Date)
        if let proof {
            defaults.set(proof, forKey: provenKey)
        } else {
            defaults.removeObject(forKey: provenKey)
        }
        return proof
    }

    /// The rule on its own, so it can be read and tested without a store.
    static func proof(enrolment: Date?, addressStoredAt: Date?) -> Date? {
        guard let enrolment, let addressStoredAt, enrolment >= addressStoredAt else { return nil }
        return enrolment
    }
}

/// **The one thing this app can observe about pairing today**, and exactly how much it means.
///
/// The bridge writes its trust store when a phone completes enrolment, and nothing else in this
/// product writes that file. So its existence is the Go side's own record that a phone came through,
/// and its modification date is when.
///
/// ### What this deliberately does not do
///
/// It does not read the file. The format belongs to `bridge/internal/trust`, and a Swift decoder for
/// it would be a second reader of a shape only one side owns — the first thing two readers do is
/// disagree, and this one would disagree about which phone may drive the owner's Mac.
///
/// ### Two limits, written down rather than discovered later
///
///  - **Unpairing is not modelled**, because nothing on this side can unpair yet. When it can, an
///    emptied trust store will still be a file with a recent date, and this must start asking the
///    bridge how many peers it holds rather than asking the file system whether the file is there.
///  - **A phone connecting is not an enrolment.** A phone that keeps its pinned peer through an
///    address change proves the new address in practice and writes nothing here, so the address stays
///    marked unproven until somebody re-scans. That is the safe direction and re-scanning is offered
///    for exactly this reason.
public enum EnrolmentRecord {

    /// Named by the Go side; kept here as one constant so there is one place to change when the
    /// bridge's state directory changes shape.
    static let fileName = "peers.json"

    /// When a phone last completed enrolment, according to the bridge's own record, or nil if it has
    /// never written one.
    public static func recordedAt(
        inStateDirectory directory: URL, fileManager: FileManager = .default,
    ) -> Date? {
        let path = directory.appending(path: fileName).path
        guard let attributes = try? fileManager.attributesOfItem(atPath: path) else { return nil }
        return attributes[.modificationDate] as? Date
    }
}
