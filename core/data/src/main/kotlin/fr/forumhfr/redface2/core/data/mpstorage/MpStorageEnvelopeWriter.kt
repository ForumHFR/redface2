package fr.forumhfr.redface2.core.data.mpstorage

import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import java.time.Clock
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MPStorage (#6, ADR-014 §4) — the read-modify-WRITE core, kept as a pure, side-effect-free
 * transform so it is the part with the real test value (Codex : parser/RMW > UI).
 *
 * It MUTATES THE RAW JSON TREE of [fr.forumhfr.redface2.core.model.mpstorage.MpStorageDocument.rawEnvelope]
 * — it NEVER rebuilds the envelope from the projected `MpStorageDocument` model (the parser projects,
 * it does not reconstruct ; rebuilding from the model would silently drop every key Redface 2 does not
 * consume). Concretely : parse the verbatim text into a [JsonObject], walk to the v0.1 `data[]` entry's
 * `mpFlags.list[]`, upsert one entry, and re-serialise. Every key it does not touch — third-party
 * top-level keys (`sourceName`, `lastUpdate`, `hfr4k`, …), sibling `data[]` entries (a `version != 0.1`
 * entry written by another tool), and unknown keys WITHIN the v0.1 entry or within a list item — is
 * carried through untouched.
 *
 * TWO-PHASE write (verify-after-write contract) :
 *  - if the upsert does not actually change the target position ([Outcome.NoOp]), the writer returns
 *    the original content BYTE-FIDÈLE and DOES NOT stamp `sourceName` / `lastUpdate` — the caller skips
 *    the POST entirely (writing an identical body would needlessly bump the document) ;
 *  - on a real change ([Outcome.Mutated]) it stamps `sourceName = "Redface2"` and `lastUpdate = now` at
 *    the THREE levels DTCloud writes them (root, the v0.1 entry, and its `mpFlags` block) so the storage
 *    advertises Redface 2 as the last writer, exactly like the original userscript.
 *
 * NOT OBSERVED LIVE : the bytes produced here have never been round-tripped against a real HFR
 * `bdd.php cat=prive` POST (device down). The output is validated against
 * [MpStorageRepository.MAX_CONTENT_FORM_BYTES] (fail-closed) ; whether HFR accepts it is unconfirmed.
 */
class MpStorageEnvelopeWriter @Inject constructor(
    private val clock: Clock,
) {

    /**
     * Compact, stable serialiser. The envelope is machine-written by userscripts, so we do not try
     * to preserve the source's exact whitespace on write (only reads keep the verbatim text) — we
     * emit canonical compact JSON. `encodeDefaults`/`explicitNulls` are irrelevant : we only ever
     * serialise an already-built [JsonElement] tree, never @Serializable models.
     */
    private val json = Json { prettyPrint = false }

    /** Result of the pure mutation : the mutated body, a byte-fidèle no-op, or a typed failure. */
    sealed interface Outcome {
        /** A real change : [body] carries the mutated JSON with the 3-level `sourceName`/`lastUpdate` stamp. */
        data class Mutated(val body: String) : Outcome

        /**
         * The target position already equals [entry] : nothing changed. [body] is the ORIGINAL content,
         * byte-fidèle (no re-serialisation, no stamp) — the caller skips the POST so the document is left
         * exactly as read.
         */
        data class NoOp(val body: String) : Outcome

        data object NotJsonEnvelope : Outcome
        data class TooLarge(val sizeBytes: Int) : Outcome
    }

    /**
     * Upserts [entry] into the v0.1 `mpFlags.list[]` of [rawEnvelope] and returns the mutated text.
     *
     *  - matching is by `post` == [MpStorageFlagEntry.threadId] : an existing list item is UPDATED in
     *    place (its `post`/`page`/`href`/`uri` are overwritten, every OTHER key it holds — e.g. `p`,
     *    or a tool-specific key — is preserved) ; no match → a new item is APPENDED ;
     *  - a missing `data` array / missing v0.1 entry / missing `mpFlags` / missing `list` is CREATED
     *    minimally, respecting the v0.1 shape, without disturbing sibling keys ;
     *  - when the resulting list is IDENTICAL to the original (the entry was already at this exact
     *    position), the writer returns [Outcome.NoOp] with the verbatim original text — no stamp, no
     *    re-serialisation ;
     *  - on a real change it stamps `sourceName = "Redface2"` + `lastUpdate = now` at the 3 levels and
     *    rejects the result ([Outcome.TooLarge]) when its UTF-8 size exceeds
     *    [MpStorageRepository.MAX_CONTENT_FORM_BYTES].
     *
     * Returns [Outcome.NotJsonEnvelope] when [rawEnvelope] is not a JSON object (the caller maps this
     * to "unreadable" and NEVER writes a repaired default — ADR-014 §3).
     */
    @Suppress("ReturnCount") // Guard (not-JSON) + no-op short-circuit + cap rejection + the mutated return.
    fun upsertFlag(rawEnvelope: String, entry: MpStorageFlagEntry): Outcome {
        val root = parseObjectOrNull(rawEnvelope) ?: return Outcome.NotJsonEnvelope

        val withFlag = mutateEnvelope(root, entry)
        if (withFlag == root) {
            // The upsert produced an identical tree : the target position did not change. Return the
            // ORIGINAL text byte-fidèle (no stamp, no re-serialisation) so the caller skips the POST.
            return Outcome.NoOp(rawEnvelope)
        }

        val stamped = stampLastWriter(withFlag)
        val body = json.encodeToString(JsonObject.serializer(), stamped)

        val sizeBytes = body.toByteArray(Charsets.UTF_8).size
        if (sizeBytes > MpStorageRepository.MAX_CONTENT_FORM_BYTES) {
            return Outcome.TooLarge(sizeBytes)
        }
        return Outcome.Mutated(body)
    }

    /**
     * Whether [rawEnvelope] still parses as a JSON object. Used by the write-back verify step to tell
     * a HEALTHY document (valid JSON — our write, or a concurrent valid write by another client) from
     * a CORRUPTED one (truncated / non-JSON, the HFR non-UTF-8 truncation), so a restore only fires on
     * real corruption and never clobbers a legitimate concurrent update (Codex review).
     */
    fun isJsonEnvelope(rawEnvelope: String): Boolean = parseObjectOrNull(rawEnvelope) != null

    private fun parseObjectOrNull(rawEnvelope: String): JsonObject? {
        if (rawEnvelope.isBlank()) return null
        return try {
            json.parseToJsonElement(rawEnvelope) as? JsonObject
        } catch (@Suppress("SwallowedException") error: kotlinx.serialization.SerializationException) {
            null
        }
    }

    /** Rebuilds the top-level object preserving every key, replacing only `data` with the mutated array. */
    private fun mutateEnvelope(root: JsonObject, entry: MpStorageFlagEntry): JsonObject {
        val dataArray = root["data"] as? JsonArray ?: JsonArray(emptyList())
        val mutatedData = mutateDataArray(dataArray, entry)
        return JsonObject(root.toMutableMap().apply { put("data", mutatedData) })
    }

    /**
     * Stamps `sourceName = "Redface2"` + `lastUpdate = now` at the three levels DTCloud maintains :
     * the root object, the v0.1 `data[]` entry, and that entry's `mpFlags` block. Every other key is
     * preserved. Called ONLY on a real change (the no-op path leaves the document untouched).
     */
    private fun stampLastWriter(root: JsonObject): JsonObject {
        val now = clock.millis()
        val dataArray = root["data"] as? JsonArray ?: JsonArray(emptyList())
        val stampedData = JsonArray(
            dataArray.map { element ->
                val entry = element as? JsonObject ?: return@map element
                if (isV01(entry)) stampV01Entry(entry, now) else element
            },
        )
        return JsonObject(
            root.toMutableMap().apply {
                put("data", stampedData)
                put("sourceName", JsonPrimitive(SOURCE_NAME))
                put("lastUpdate", JsonPrimitive(now))
            },
        )
    }

    /** Stamps the v0.1 entry and its `mpFlags` block (levels 2 and 3), preserving every other key. */
    private fun stampV01Entry(v01Entry: JsonObject, now: Long): JsonObject {
        val mpFlags = v01Entry["mpFlags"] as? JsonObject ?: JsonObject(emptyMap())
        val stampedMpFlags = JsonObject(
            mpFlags.toMutableMap().apply {
                put("sourceName", JsonPrimitive(SOURCE_NAME))
                put("lastUpdate", JsonPrimitive(now))
            },
        )
        return JsonObject(
            v01Entry.toMutableMap().apply {
                put("sourceName", JsonPrimitive(SOURCE_NAME))
                put("lastUpdate", JsonPrimitive(now))
                put("mpFlags", stampedMpFlags)
            },
        )
    }

    private fun isV01(entry: JsonObject): Boolean =
        entry["version"]?.let { it is JsonPrimitive && it.content == V01 } == true

    /**
     * Replaces (or creates) the v0.1 entry inside `data[]`, preserving the order and every sibling
     * entry. When no v0.1 entry exists yet, a minimal one is appended (other entries untouched).
     */
    private fun mutateDataArray(dataArray: JsonArray, entry: MpStorageFlagEntry): JsonArray {
        val v01Index = dataArray.indexOfFirst { (it as? JsonObject)?.let(::isV01) == true }
        return if (v01Index >= 0) {
            val v01Entry = dataArray[v01Index] as JsonObject
            JsonArray(
                dataArray.toMutableList().apply { this[v01Index] = mutateV01Entry(v01Entry, entry) },
            )
        } else {
            JsonArray(dataArray + mutateV01Entry(emptyV01Entry(), entry))
        }
    }

    private fun emptyV01Entry(): JsonObject = buildJsonObject { put("version", V01) }

    /** Preserves every key of the v0.1 entry, replacing only its `mpFlags` (itself merged, not clobbered). */
    private fun mutateV01Entry(v01Entry: JsonObject, entry: MpStorageFlagEntry): JsonObject {
        val mpFlags = v01Entry["mpFlags"] as? JsonObject ?: JsonObject(emptyMap())
        val list = mpFlags["list"] as? JsonArray ?: JsonArray(emptyList())
        val mutatedList = upsertList(list, entry)
        val mutatedMpFlags = JsonObject(mpFlags.toMutableMap().apply { put("list", mutatedList) })
        return JsonObject(v01Entry.toMutableMap().apply { put("mpFlags", mutatedMpFlags) })
    }

    /**
     * Upserts the item whose `post` matches [MpStorageFlagEntry.threadId]. On an UPDATE, only the
     * four owned wire fields are written ; all other keys on the matched item survive verbatim.
     */
    private fun upsertList(list: JsonArray, entry: MpStorageFlagEntry): JsonArray {
        val matchIndex = list.indexOfFirst { item ->
            (item as? JsonObject)?.get("post")?.asIntOrNull() == entry.threadId
        }
        return if (matchIndex >= 0) {
            val existing = list[matchIndex] as JsonObject
            JsonArray(
                list.toMutableList().apply { this[matchIndex] = writeWireFields(existing, entry) },
            )
        } else {
            JsonArray(list + writeWireFields(JsonObject(emptyMap()), entry))
        }
    }

    /**
     * Overwrites the four owned wire fields on [base] (preserving any other key, e.g. `p`):
     * `post`, `page`, `href` (= `"t<numreponse>"`, omitted/null when [MpStorageFlagEntry.numreponse]
     * is null), `uri` (omitted/null when absent). Numbers are emitted as JSON numbers (the read parser
     * tolerates both, the original userscript writes numbers).
     */
    private fun writeWireFields(base: JsonObject, entry: MpStorageFlagEntry): JsonObject =
        JsonObject(
            base.toMutableMap().apply {
                put("post", JsonPrimitive(entry.threadId))
                put("page", JsonPrimitive(entry.page))
                put("href", entry.numreponse?.let { JsonPrimitive("t$it") } ?: JsonNull)
                put("uri", entry.uri?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        )

    private fun JsonElement.asIntOrNull(): Int? = (this as? JsonPrimitive)?.content?.toIntOrNull()

    private companion object {
        private const val V01 = "0.1"
        private const val SOURCE_NAME = "Redface2"
    }
}
