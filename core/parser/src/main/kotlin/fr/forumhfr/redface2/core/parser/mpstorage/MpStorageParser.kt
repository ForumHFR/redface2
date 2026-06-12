package fr.forumhfr.redface2.core.parser.mpstorage

import fr.forumhfr.redface2.core.model.mpstorage.MpStorageDocument
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * MPStorage (#6, ADR-014) — tolerant parser of the v0.1 envelope found in the first post of
 * the dedicated storage MP (the raw `content_form` text, NOT rendered HTML).
 *
 * Tolerance contract :
 *  - the envelope is written by JavaScript userscripts — numbers may arrive as strings and
 *    vice versa, so every numeric field goes through [asIntOrNull] ;
 *  - UNKNOWN KEYS ARE NEVER AN ERROR : other tools (HFR4K, …) freely add their own keys to
 *    the shared v0.1 entry. The projection only reads what Redface 2 consumes ; the verbatim
 *    text is kept in [MpStorageDocument.rawEnvelope] for the future read-modify-write ;
 *  - a `data` array without any `version == "0.1"` entry is a VALID document with no
 *    consumable section (a future v0.2 written by another tool must not read as corruption) ;
 *  - anything that is not a JSON object with a `data` array is a failure. Per ADR-014 the
 *    caller surfaces it explicitly and NEVER writes a "repaired" default over it (the
 *    original library's destructive-reset trap).
 *
 * The sample shapes exercised in tests follow the published library contract
 * (`MPStorage.user.js` + the Wiripse documentation page) — this is a third-party JSON
 * contract, not scraped HFR HTML, hence no HTML fixture requirement.
 */
class MpStorageParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Suppress("ReturnCount") // Guard clauses (empty / not-JSON / not-object / no-data) + the success return.
    fun parse(contentForm: String): Result<MpStorageDocument> {
        if (contentForm.isBlank()) {
            return Result.failure(IllegalArgumentException("MPStorage document is empty"))
        }
        val root = try {
            json.parseToJsonElement(contentForm)
        } catch (error: kotlinx.serialization.SerializationException) {
            return Result.failure(IllegalArgumentException("MPStorage document is not JSON", error))
        }
        val envelope = root as? JsonObject
            ?: return Result.failure(IllegalArgumentException("MPStorage envelope is not a JSON object"))
        val dataArray = envelope["data"] as? JsonArray
            ?: return Result.failure(IllegalArgumentException("MPStorage envelope has no data array"))

        val v01Entry = dataArray
            .filterIsInstance<JsonObject>()
            .firstOrNull { (it["version"] as? JsonPrimitive)?.content == "0.1" }

        return Result.success(
            MpStorageDocument(
                sourceName = (envelope["sourceName"] as? JsonPrimitive)?.content,
                mpFlags = parseFlagEntries(v01Entry?.get("mpFlags")),
                // VERBATIM, including surrounding whitespace — the future read-modify-write
                // must round-trip exactly what the edit form served (ADR-014).
                rawEnvelope = contentForm,
            ),
        )
    }

    /**
     * DTCloud's `mpFlags.list[]`. Entries missing a usable `post` are skipped (not fatal —
     * the rest of the list stays consumable) ; `page` falls back to 1 ; `href` is the
     * `"t<numreponse>"` anchor and degrades to null on any unexpected shape.
     */
    private fun parseFlagEntries(mpFlags: JsonElement?): List<MpStorageFlagEntry> {
        val list = (mpFlags as? JsonObject)?.get("list") as? JsonArray ?: return emptyList()
        return list.mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            val threadId = entry["post"].asIntOrNull() ?: return@mapNotNull null
            MpStorageFlagEntry(
                threadId = threadId,
                page = entry["page"].asIntOrNull() ?: 1,
                numreponse = (entry["href"] as? JsonPrimitive)?.content
                    ?.removePrefix("t")
                    ?.toIntOrNull(),
                uri = (entry["uri"] as? JsonPrimitive)?.content,
            )
        }
    }

    /** JS userscripts serialise numbers loosely — accept both `42` and `"42"`. */
    private fun JsonElement?.asIntOrNull(): Int? =
        (this as? JsonPrimitive)?.content?.toIntOrNull()
}
