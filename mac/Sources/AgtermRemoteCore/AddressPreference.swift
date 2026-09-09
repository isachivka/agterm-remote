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
    public static let key = "dialAddress"

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

    /// Writes the address.
    ///
    /// `throws` describes the store's contract rather than this implementation: `UserDefaults` reports
    /// no failure, so this particular body cannot throw. The seam stays because the outcome that says
    /// *the address is fine and saving it is what failed* is a different sentence from a refusal, and
    /// a caller that could not distinguish them would have to tell somebody their correct address was
    /// wrong.
    public static func write(_ address: DialAddress, to defaults: UserDefaults = .standard) throws {
        defaults.set(address.displayed, forKey: key)
    }
}
