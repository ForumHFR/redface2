package fr.forumhfr.redface2.core.domain.error

import java.io.IOException

/**
 * HFR answered the HTTP exchange with a non-2xx status (#324): the server was reached —
 * DNS, TCP and TLS all succeeded — but it refused or failed to serve the resource. [code]
 * lets consumers tell an HFR outage (5xx → [HfrErrorKind.ServerDown]) apart from a
 * client-side rejection (4xx → [HfrErrorKind.Other]); see [classifyHfrError].
 *
 * Genuine connectivity failures (airplane mode, DNS resolution, timeouts) keep surfacing
 * as the plain [IOException] subclasses OkHttp throws — this type is raised ONLY when an
 * HTTP response actually came back. It extends [IOException] so every existing
 * `catch (e: IOException)` site keeps treating it as a transport-level failure.
 *
 * Lives in `:core:domain` (not `:core:network`) so feature modules can type-check it
 * without importing the network layer, which the Konsist architecture test forbids — same
 * precedent as [fr.forumhfr.redface2.core.domain.auth.SessionExpiredException].
 *
 * @param code the HTTP status HFR answered with.
 * @param url the requested URL, embedded in the default message for diagnostics. Callers
 * sitting on a privacy boundary may pass a redacted placeholder (cf. the search
 * repository, whose URLs carry the user's query).
 * @param detailMessage optional richer diagnostic line (e.g. the REST client's body
 * excerpt) preserved verbatim as the exception message; `null` keeps the canonical
 * `"HFR returned <code> for <url>"` shape.
 */
class HfrServerException(
    val code: Int,
    url: String,
    detailMessage: String? = null,
) : IOException(detailMessage ?: "HFR returned $code for $url")
