package fr.forumhfr.redface2.core.data.flags

import fr.forumhfr.redface2.core.data.forum.RestListEnvelope
import fr.forumhfr.redface2.core.data.forum.RestTopic
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.error.HfrRestBlockedException
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.model.FlagFailureKind
import fr.forumhfr.redface2.core.network.FlagReadDeadlineException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** DTO defaults are convenient elsewhere, but a missing list is not a valid empty flags response. */
internal fun decodeFlagPage(json: Json, body: String): RestListEnvelope<RestTopic> {
    val root = json.parseToJsonElement(body) as? JsonObject
    val resource = root?.get("resource") as? JsonObject
    if (resource == null || "resources" !in resource || "results_count" !in resource) {
        throw SerializationException("Missing flags list schema")
    }
    return json.decodeFromString<RestListEnvelope<RestTopic>>(body)
}

internal class FlagPageCapException : IllegalStateException("Flags page limit reached")

internal fun checkFlagSession(expired: AtomicBoolean?) {
    if (expired?.get() == true) throw SessionExpiredException("[redacted]")
}

internal fun Throwable.toFlagFailureKind(): FlagFailureKind = when (this) {
    is SessionExpiredException -> FlagFailureKind.SESSION
    is HfrRestBlockedException -> FlagFailureKind.BLOCKED
    is HfrServerException -> FlagFailureKind.SERVER
    is SerializationException -> FlagFailureKind.SCHEMA
    is FlagPageCapException -> FlagFailureKind.PAGE_CAP
    is FlagReadDeadlineException -> FlagFailureKind.DEADLINE
    is IOException -> FlagFailureKind.NETWORK
    else -> FlagFailureKind.UNKNOWN
}
