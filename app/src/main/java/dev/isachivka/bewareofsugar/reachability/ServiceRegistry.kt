package dev.isachivka.bewareofsugar.reachability

/**
 * One of the owner's services, as far as this app is concerned.
 *
 * An id, a name and an address to try. Deliberately no credential field — not an empty one, not a
 * nullable one, not a commented-out one. The app authenticates to nothing, so there is no state in
 * which a password would help, and a field that exists is a field somebody eventually fills in.
 *
 * [name] is a plain `String` rather than a `@StringRes`, which is an exception to this project's
 * rule that user-visible text lives in `strings.xml`. "Navidrome", "qBittorrent" and "Paperless-ngx"
 * are proper nouns of third-party software: never translated, never reworded. Everything this app
 * *says about* a service is a string resource; only the software's own name is not.
 */
data class HomeService(
    val id: String,
    val name: String,
    /** The remote address, always HTTPS. Never a LAN address — see [ServiceRegistry]. */
    val url: String,
)

/**
 * The owner's services, as this app is allowed to see them.
 *
 * Transcribed by hand from `~/pets/home-router/app/services.ts`, and not generated from it. That file
 * carries twelve plaintext logins and passwords beside the addresses, and a generator is a program
 * whose input is those passwords — its safety would rest on a filter being correct forever. Not
 * reading the file at build time is a stronger guarantee than filtering it carefully, and it keeps
 * CI, which has no copy of that repository, building the same way as a laptop that does.
 *
 * `scripts/check-no-credentials.sh` is the other half of that argument: the guarantee is enforced
 * rather than promised, and a credential-shaped line reaching any tracked file fails the build.
 */
object ServiceRegistry {

    /**
     * Every host the app is permitted to contact, other than GitHub.
     *
     * REQ-0004 said "github.com and nowhere else"; REQ-0005 lifts that for the owner's own services
     * and for nothing else. This constant is what makes the lift narrow rather than nominal —
     * `ServiceRegistryTest` pins every address to it, so an eleventh entry pointing somewhere else
     * fails a test rather than quietly widening what the app talks to.
     */
    const val PERMITTED_HOST_SUFFIX = ".bewareofsugar.keenetic.link"

    /**
     * The ten services with a remote address, in the order `services.ts` lists them.
     *
     * All ten resolve to one IP on one port behind one wildcard certificate, which is a fact about
     * the diagnosis rather than about this list: ten simultaneous failures at the same layer are one
     * closed door, not ten broken services.
     *
     * Ids are the subdomain rather than a slug of the name, so an id cannot drift from the address it
     * belongs to.
     */
    val remote: List<HomeService> = listOf(
        HomeService("torrent", "qBittorrent", "https://torrent.bewareofsugar.keenetic.link:8443"),
        HomeService("files", "FileService", "https://files.bewareofsugar.keenetic.link:8443/files/"),
        HomeService("immich", "Immich", "https://immich.bewareofsugar.keenetic.link:8443"),
        HomeService("n8n", "n8n", "https://n8n.bewareofsugar.keenetic.link:8443"),
        HomeService("inventory", "Inventory", "https://inventory.bewareofsugar.keenetic.link:8443"),
        HomeService("ha", "Home Assistant", "https://ha.bewareofsugar.keenetic.link:8443"),
        HomeService("docs", "Paperless-ngx", "https://docs.bewareofsugar.keenetic.link:8443/"),
        HomeService("frigate", "Frigate", "https://frigate.bewareofsugar.keenetic.link:8443"),
        HomeService("navidrome", "Navidrome", "https://navidrome.bewareofsugar.keenetic.link:8443"),
        // The registry gives Jellyfin a cleartext address. That address 302s to this one, which
        // answers from the same ingress under the same certificate as the other nine - so the
        // registry entry is stale rather than a statement that Jellyfin is HTTP-only, and this app
        // needs neither a cleartext exception nor to drop the service. Measured, see REQ-0005.
        HomeService("jellyfin", "Jellyfin", "https://jellyfin.bewareofsugar.keenetic.link:8443"),
    )

    /**
     * The ten services with no remote address, by name only.
     *
     * They are here because a list that silently omits half the estate reads as "these are all my
     * services". They are **names only** because their addresses must not ship, and the reason is
     * not merely that they are local:
     *
     * - Two of them are not local at all. Grafana points at Grafana Cloud and 3x-ui (nurenberg) at a
     *   public VPS, so "LAN-only" is the registry's framing rather than the truth, and this list is
     *   named for what is actually true of all ten — no remote address in `services.ts`.
     * - Both 3x-ui entries carry an unguessable panel path in their URL. That is access control by
     *   obscurity, which makes the path a credential in everything but name.
     *
     * Spelled as the owner spells them, including "nurenberg", so a name here matches the dashboard
     * they already read rather than a tidier version of it.
     */
    val withoutRemoteAddress: List<String> = listOf(
        "Plex",
        "Baby Tracker",
        "Ozon Orders v2",
        "Grafana",
        "Music Files",
        "Audiobookshelf",
        "OpenVPN (home)",
        "3x-ui (home)",
        "3x-ui (nurenberg)",
        "NanoClaw Files",
    )

    fun byId(id: String): HomeService? = remote.firstOrNull { it.id == id }
}
