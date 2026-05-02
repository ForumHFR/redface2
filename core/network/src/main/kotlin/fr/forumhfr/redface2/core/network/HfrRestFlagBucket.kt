package fr.forumhfr.redface2.core.network

/**
 * Whitelisted REST URI segments for the user's drapeaux buckets. Lives in `:core:network`
 * (and not `:core:model`) on purpose : `:core:network` must not depend on the domain
 * model, and the only thing the network client needs is a safe, server-recognised URI
 * segment. Mapping this enum to / from `FlagType` is a `:core:data` concern.
 *
 * Endpoints — confirmed by ADR-003 and the captured `rest_cat23_participated.*` source :
 *
 * - [PARTICIPATED] → `topics/participated/`
 * - [READ] → `topics/read/`
 * - [FAVORITES] → `topics/favorites/`
 *
 * Adding a new bucket requires extending this enum (and updating the auth REST flag
 * mapper). Free-form strings are intentionally not accepted by [HfrApiClient] so a
 * caller cannot hit an unrelated REST URI.
 */
enum class HfrRestFlagBucket(val uriSegment: String) {
    PARTICIPATED("participated"),
    READ("read"),
    FAVORITES("favorites"),
}
