package fr.forumhfr.redface2.core.domain.error

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import java.io.IOException

/**
 * Coarse classification of a read-path failure (#324), shared by every reading screen
 * (topic, forum/catégories, drapeaux, recherche, MP, profil) so an HFR outage and a local
 * connectivity cut are rendered consistently. The matching user-facing strings live in
 * `:core:ui` (`error_hfr_server_down` / `error_no_connection`); [Other] keeps each
 * screen's existing generic message.
 */
enum class HfrErrorKind {
    /** HFR itself answered with a 5xx — the site is reachable but failing server-side. */
    ServerDown,

    /** No usable HTTP response came back: connectivity, DNS, timeout, TLS failure. */
    Network,

    /** Anything else: 4xx rejections, session expiry, parse errors, programming errors. */
    Other,
}

/**
 * Pure classifier mapping a read-path [Throwable] to its [HfrErrorKind]. The branch order
 * is load-bearing (every listed type extends [IOException]):
 *
 * 1. [SessionExpiredException] → [HfrErrorKind.Other]. Screens with a dedicated session
 *    treatment (e.g. the drapeaux reconnect CTA) branch on the exception type BEFORE
 *    consulting this classifier and keep their behaviour; screens without one must not
 *    present an expired session as a network cut.
 * 2. [HfrServerException] with a 5xx code → [HfrErrorKind.ServerDown]; any other code
 *    (4xx) → [HfrErrorKind.Other] (the request was wrong, not the server).
 * 3. Any other [IOException] → [HfrErrorKind.Network] — the transport failures OkHttp
 *    raises when no HTTP response came back.
 * 4. Everything else → [HfrErrorKind.Other].
 */
fun classifyHfrError(error: Throwable): HfrErrorKind = when (error) {
    is SessionExpiredException -> HfrErrorKind.Other
    is HfrServerException ->
        if (error.code >= HTTP_SERVER_ERROR_MIN) HfrErrorKind.ServerDown else HfrErrorKind.Other
    is IOException -> HfrErrorKind.Network
    else -> HfrErrorKind.Other
}

/** Lower bound of the HTTP 5xx server-error class. */
private const val HTTP_SERVER_ERROR_MIN = 500
