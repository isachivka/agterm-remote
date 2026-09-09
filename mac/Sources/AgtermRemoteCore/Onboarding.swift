import Foundation

/// Which of the three things a person must get right is not right yet.
///
/// **Never a percentage and never a checklist with ticks.** Each step is a prerequisite for the one
/// after it: an address for a terminal that is not running is a value nothing will read, and a code
/// for an address that does not exist is a code that cannot work. So the screen shows one thing at a
/// time, in the only order they can be established in.
public enum OnboardingStep: String, CaseIterable, Equatable, Sendable {
    /// agterm is not there. Nothing else on this screen matters, and nothing else is offered.
    case agtermMissing
    /// No address a phone could dial. **The step this whole product is careful about** — see
    /// `OnboardingCopy`.
    case address
    /// There is an address, and no phone has ever come through it. The scan is the next act, and it
    /// is also what proves the address.
    case pairing
    /// All three hold.
    case done
}

/// The three things that must hold before a phone can reach this Mac, in the order they hold in.
///
/// ### The first fact is the caller's to establish, not this type's
///
/// `agtermSocketExists` is a `Bool` handed in rather than something looked up here, and that is a
/// boundary rather than a convenience. This app **may not name or open agterm's control socket** —
/// `BoundaryTests` fails the build if any source in either target so much as spells the path — because
/// every command a phone can cause has to argue its way past the allowlist inside the bridge, and a
/// menu-bar app that spoke to agterm directly would walk around that allowlist entirely. What the app
/// can honestly observe is that agterm is *running*, which is in any case the fact that matters: a
/// socket file left behind by a crashed terminal is not a terminal.
///
/// ### An address that will not parse is not an address
///
/// It is the same step as having none, and the same screen fixes it, so it is not a fifth state. The
/// parser is `Address.parse`, which is `DialAddress`'s parser narrowed — never a second reading of the
/// same string, because a store that accepts what the editor refuses holds a value nobody could have
/// typed.
public struct Onboarding: Equatable, Sendable {

    public let agtermIsThere: Bool
    /// The stored address, parsed. Nil when there is none, or when what is stored is not one.
    public let address: Address?
    public let isPaired: Bool

    /// - Parameter agtermSocketExists: whether agterm is there. Named for the fact the design
    ///   document names; see the note above for what the caller may actually look at.
    /// - Parameter address: exactly what is in the preference, unparsed, including the empty string
    ///   and the malformed leftovers of an earlier version.
    /// - Parameter isPaired: whether a phone has completed enrolment **through the address that is
    ///   stored now**. See `AddressPreference.provenAt`: proof does not survive an address change,
    ///   because it was never proof about the new one.
    public init(agtermSocketExists: Bool, address: String?, isPaired: Bool) {
        self.agtermIsThere = agtermSocketExists
        self.address = address.flatMap { try? Address.parse($0).get() }
        self.isPaired = isPaired
    }

    /// The one unfinished thing, or `done`.
    ///
    /// Written as a ladder rather than as a set of conditions that could be evaluated in any order.
    /// **A paired phone never carries somebody past a missing address**: that combination is ordinary
    /// — pair, then clear or mistype the address — and what they need is the address screen, not a
    /// code for a destination that no longer exists.
    ///
    /// agterm comes first because it is the prerequisite for the product doing anything at all. It is
    /// **not** the prerequisite for finishing setup — see [setup].
    public var step: OnboardingStep {
        guard agtermIsThere else { return .agtermMissing }
        guard address != nil else { return .address }
        guard isPaired else { return .pairing }
        return .done
    }

    /// **What is left to SET UP, which is not the same question as what is unresolved right now.**
    ///
    /// agterm's absence is a runtime state: the terminal can be quit and started again all day, and
    /// none of it changes what has been configured. Storing an address needs nothing from agterm, and
    /// neither does pairing a phone — the bridge dials agterm when a session is asked for, not when a
    /// code is scanned.
    ///
    /// Treating it as a setup step cost two things, both of them met the hard way. A finished owner
    /// whose Mac started this app at login before agterm was up got the setup window over their work,
    /// every time. And whoever it was addressed to was trapped: the pane offered one button, *look
    /// again*, which is an answer for a terminal that is about to start and no answer at all for one
    /// that this app cannot recognise — a build run from source, say. So the ladder that decides
    /// whether the setup window opens skips agterm, and agterm's absence is reported where a runtime
    /// fact belongs: the menu.
    public var setup: OnboardingStep {
        guard address != nil else { return .address }
        guard isPaired else { return .pairing }
        return .done
    }
}

/// What the address screen says, in the project's own voice.
///
/// ### Reachability is the owner's problem, and saying so is the feature
///
/// This app integrates with no tunnel and no DNS provider, and it recommends none of them. It says
/// what is needed and lists what people use, because the alternative — picking one — makes this a
/// front end for somebody else's service and makes every other route feel unsupported. The list is
/// held in `OnboardingTests` so it cannot quietly become an endorsement of one entry.
///
/// ### There is no button here that tries the address, and there must never be one
///
/// The argument in full is in `OnboardingWindow.swift`, beside the place a button would be added,
/// because that is where the next contributor will meet the question. The short of it: a Mac cannot
/// honestly test its own public address, and a bind that succeeds is not a port that is yours.
public enum OnboardingCopy {

    public static let addressHeading = "The address your phone will dial"

    /// The sentence. Not "configure networking" — this names whose job it is, in the second person,
    /// because somebody is about to go and do it.
    public static let addressExplanation =
        "Your phone needs an address it can reach this Mac at, and arranging that is your job. This "
            + "app does not set it up for you and does not integrate with any provider — it stores what "
            + "you type and builds the pairing code from it."

    /// What people use, in no order of preference. Each line says when it applies rather than how
    /// good it is.
    public static let addressRoutes = [
        "A port forward on your router, and the address written as IP:PORT — when the address does "
            + "not change.",
        "A port forward on your router, and a dynamic-DNS name — when it does.",
        "Tailscale or WireGuard, which carry the connection to this Mac themselves.",
        "Cloudflare Tunnel, a reverse proxy, or a router that publishes this Mac rather than "
            + "forwarding to it — tell the app so below, because these are reached differently.",
    ]

    /// The example in the field, and it is an RFC 5737 documentation address on purpose. A real one
    /// in a placeholder is an address somebody will keep by accident, and it would also be somebody's
    /// home.
    public static let addressPlaceholder = "agterm.example-homelab.invalid:8443"

    /// **The two ports, in one line.** Somebody with a straight port forward should read this and
    /// stop reading.
    public static let arrivalPortHeading = "The port traffic arrives on at this Mac"

    public static let arrivalPortExplanation =
        "The address above is what your phone dials — your router's. This is where that traffic comes "
            + "out on this Mac. If your router forwards the port straight through, leave this empty and "
            + "it follows the address above. Fill it in when the two differ: a router that publishes "
            + "one port and delivers to another, a tunnel, or a reverse proxy."

    /// **What binding every interface actually costs, said to the person it costs it to.**
    ///
    /// The bridge binds the wildcard, which is what makes every route above work — a tunnel gives
    /// this Mac an interface of its own, and something bound to loopback is invisible on it. The
    /// consequence is that the port is open on every network this Mac joins and not only on the one
    /// the address goes through, and **only one of the four routes involves deliberately opening a
    /// port at all.** Somebody on Tailscale arranged the opposite and is owed the sentence anyway.
    ///
    /// It is disclosure, not an alarm: what gets past the port is a phone presenting the certificate
    /// that was paired, and nothing else.
    public static let addressExposure =
        "While the bridge is running it accepts connections on that port on every network this Mac "
            + "joins — home, work, a café — not only through the address above. Only a phone you have "
            + "paired gets past it: anything else is refused before it can ask for anything. Stop the "
            + "bridge from the menu when you would rather it were not listening."

    /// **Why nothing here says whether the address works.**
    ///
    /// Said on the screen and not only in the source, because the absence of a check is exactly the
    /// kind of thing a person reads as an oversight.
    public static let addressIsUnprovenUntilAPhoneArrives =
        "Nothing on this screen can tell you whether that address reaches this Mac. This Mac is the "
            + "one machine that cannot answer that question honestly — from behind your own router the "
            + "answer is wrong in both directions. The address stays marked unproven until a phone "
            + "actually connects through it, and the scan on the next step is what proves it."
}
