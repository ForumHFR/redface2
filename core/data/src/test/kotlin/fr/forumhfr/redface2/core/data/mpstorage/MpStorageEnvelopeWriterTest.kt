package fr.forumhfr.redface2.core.data.mpstorage

import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MPStorage read-modify-WRITE core (#6, ADR-014 §4). The mutation operates on the RAW JSON tree of
 * the verbatim `rawEnvelope`, never on the projected model — so the cross-userscript namespaces must
 * survive every round-trip. These are the highest-value tests of the write chantier (Codex : RMW > UI).
 *
 * The output is re-parsed (not string-compared) so the assertions are robust to key ordering /
 * whitespace : what matters is the semantic shape, not the exact bytes (NOT OBSERVED LIVE — no real
 * HFR round-trip to byte-match against). The clock is fixed so `lastUpdate` is deterministic.
 */
class MpStorageEnvelopeWriterTest {

    private val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(NOW_MILLIS), ZoneOffset.UTC)
    private val writer = MpStorageEnvelopeWriter(fixedClock)
    private val json = Json

    private fun mutate(raw: String, entry: MpStorageFlagEntry): MpStorageEnvelopeWriter.Outcome =
        writer.upsertFlag(raw, entry)

    private fun bodyOf(outcome: MpStorageEnvelopeWriter.Outcome): JsonObject {
        val mutated = outcome as MpStorageEnvelopeWriter.Outcome.Mutated
        return json.parseToJsonElement(mutated.body).jsonObject
    }

    private fun JsonObject.v01Entry(): JsonObject =
        this["data"]!!.jsonArray.map { it.jsonObject }
            .first { it["version"]?.jsonPrimitive?.content == "0.1" }

    private fun JsonObject.flagList(): JsonArray =
        v01Entry()["mpFlags"]!!.jsonObject["list"]!!.jsonArray

    @Test
    fun `a real change stamps sourceName and lastUpdate at the three levels`() {
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [] } } ] }"""

        val body = bodyOf(mutate(raw, MpStorageFlagEntry(threadId = 1, page = 1, numreponse = 1, uri = null)))

        // Level 1 — root.
        assertEquals("Redface2", body["sourceName"]!!.jsonPrimitive.content)
        assertEquals(NOW_MILLIS, body["lastUpdate"]!!.jsonPrimitive.content.toLong())
        // Level 2 — v0.1 entry.
        val v01 = body.v01Entry()
        assertEquals("Redface2", v01["sourceName"]!!.jsonPrimitive.content)
        assertEquals(NOW_MILLIS, v01["lastUpdate"]!!.jsonPrimitive.content.toLong())
        // Level 3 — the mpFlags block.
        val mpFlags = v01["mpFlags"]!!.jsonObject
        assertEquals("Redface2", mpFlags["sourceName"]!!.jsonPrimitive.content)
        assertEquals(NOW_MILLIS, mpFlags["lastUpdate"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `a no-op (target position unchanged) returns the original content byte-fidele without stamping`() {
        // The entry is ALREADY at this exact position (post/page/href/uri all match) → no change.
        val raw = """{"data":[{"version":"0.1","mpFlags":{"list":[{"post":1,"page":2,"href":"t9","uri":"/u"}]}}]}"""

        val outcome = mutate(raw, MpStorageFlagEntry(threadId = 1, page = 2, numreponse = 9, uri = "/u"))

        val noOp = outcome as MpStorageEnvelopeWriter.Outcome.NoOp
        // Byte-fidèle : the original text is returned verbatim, no re-serialisation, no lastUpdate.
        assertEquals(raw, noOp.body)
        assertTrue("no sourceName must be injected on a no-op", !noOp.body.contains("Redface2"))
        assertTrue("no lastUpdate must be injected on a no-op", !noOp.body.contains("lastUpdate"))
    }

    @Test
    fun `upsert preserves an unknown third-party top-level key and a sibling tool key in the v01 entry`() {
        val raw = """
            {
              "data": [
                { "version": "0.1",
                  "hfr4k": { "opaque": [1, 2, 3], "nested": { "k": "v" } },
                  "mpFlags": { "list": [] } }
              ],
              "someOtherTool": { "deep": [ { "x": true } ] }
            }
        """.trimIndent()

        val body = bodyOf(mutate(raw, MpStorageFlagEntry(threadId = 999, page = 2, numreponse = 7, uri = "/u")))

        // Third-party TOP-LEVEL key survives verbatim ; sourceName/lastUpdate are (re)stamped by us.
        assertTrue("unknown top-level tool key must survive", body.containsKey("someOtherTool"))
        assertEquals(
            true,
            body["someOtherTool"]!!.jsonObject["deep"]!!.jsonArray.first().jsonObject["x"]!!.jsonPrimitive.content
                .toBoolean(),
        )

        // The sibling tool key INSIDE the v0.1 entry survives untouched.
        val v01 = body.v01Entry()
        assertTrue("sibling tool key in v0.1 entry must survive", v01.containsKey("hfr4k"))
        assertEquals(
            "v",
            v01["hfr4k"]!!.jsonObject["nested"]!!.jsonObject["k"]!!.jsonPrimitive.content,
        )

        // The new flag was appended.
        assertEquals(1, body.flagList().size)
        assertEquals(999, body.flagList().first().jsonObject["post"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `upsert preserves a sibling data entry written by another tool (version != 0_1)`() {
        val raw = """
            { "data": [
                { "version": "0.2", "somethingNew": true },
                { "version": "0.1", "mpFlags": { "list": [] } }
            ] }
        """.trimIndent()

        val body = bodyOf(mutate(raw, MpStorageFlagEntry(threadId = 5, page = 1, numreponse = null, uri = null)))

        val entries = body["data"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, entries.size)
        val v02 = entries.first { it["version"]?.jsonPrimitive?.content == "0.2" }
        assertEquals(true, v02["somethingNew"]!!.jsonPrimitive.content.toBoolean())
        // The sibling 0.2 entry is NOT stamped — only the v0.1 entry and the root carry our metadata.
        assertNull(v02["sourceName"])
        assertEquals(1, body.flagList().size)
    }

    @Test
    fun `updating an existing entry by threadId overwrites wire fields and preserves its extra keys`() {
        val raw = """
            { "data": [ { "version": "0.1", "mpFlags": { "list": [
                { "uri": "/old", "post": 12345, "page": 3, "href": "t1980000001", "p": 2, "custom": "keepme" },
                { "post": 67890, "page": 1, "href": "t42", "p": 9 }
            ] } } ] }
        """.trimIndent()

        val body = bodyOf(
            mutate(raw, MpStorageFlagEntry(threadId = 12345, page = 7, numreponse = 555, uri = "/new")),
        )

        // SAME list size — this is an UPDATE, not an append.
        val list = body.flagList()
        assertEquals(2, list.size)
        val updated = list.map { it.jsonObject }.first { it["post"]!!.jsonPrimitive.content.toInt() == 12345 }
        assertEquals(7, updated["page"]!!.jsonPrimitive.content.toInt())
        assertEquals("t555", updated["href"]!!.jsonPrimitive.content)
        assertEquals("/new", updated["uri"]!!.jsonPrimitive.content)
        // The non-owned key on the matched item survives.
        assertEquals("keepme", updated["custom"]!!.jsonPrimitive.content)
        // The OTHER entry is untouched.
        val other = list.map { it.jsonObject }.first { it["post"]!!.jsonPrimitive.content.toInt() == 67890 }
        assertEquals("t42", other["href"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a new threadId is appended without disturbing existing entries`() {
        val raw = """
            { "data": [ { "version": "0.1", "mpFlags": { "list": [
                { "post": 111, "page": 1, "href": "t1" }
            ] } } ] }
        """.trimIndent()

        val body = bodyOf(mutate(raw, MpStorageFlagEntry(threadId = 222, page = 4, numreponse = 2, uri = null)))

        val list = body.flagList()
        assertEquals(2, list.size)
        assertEquals(111, list[0].jsonObject["post"]!!.jsonPrimitive.content.toInt())
        assertEquals(222, list[1].jsonObject["post"]!!.jsonPrimitive.content.toInt())
        assertEquals("t2", list[1].jsonObject["href"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a null numreponse and null uri are emitted as JSON null`() {
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [] } } ] }"""

        val body = bodyOf(mutate(raw, MpStorageFlagEntry(threadId = 7, page = 1, numreponse = null, uri = null)))

        val item = body.flagList().first().jsonObject
        assertNull(item["href"]!!.jsonPrimitive.contentOrNullSafe())
        assertNull(item["uri"]!!.jsonPrimitive.contentOrNullSafe())
    }

    @Test
    fun `an envelope with a data array but no v01 entry gets a minimal v01 entry appended`() {
        val raw = """{ "data": [ { "version": "0.2", "foo": 1 } ] }"""

        val body = bodyOf(mutate(raw, MpStorageFlagEntry(threadId = 9, page = 1, numreponse = 3, uri = null)))

        // The pre-existing non-v0.1 entry is kept, a v0.1 entry is created and stamped.
        assertEquals(2, body["data"]!!.jsonArray.size)
        assertEquals("Redface2", body["sourceName"]!!.jsonPrimitive.content)
        assertEquals(9, body.flagList().first().jsonObject["post"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `an envelope with no data array at all gets the minimal v01 structure created`() {
        val raw = """{ "sourceName": "DTCloud", "lastUpdate": 1 }"""

        val body = bodyOf(mutate(raw, MpStorageFlagEntry(threadId = 4, page = 2, numreponse = 6, uri = "/x")))

        // Third-party keys are (re)written to Redface2 since this is a real change ; data[]/v0.1/mpFlags/list created.
        assertEquals("Redface2", body["sourceName"]!!.jsonPrimitive.content)
        assertEquals(NOW_MILLIS, body["lastUpdate"]!!.jsonPrimitive.content.toLong())
        assertEquals(1, body["data"]!!.jsonArray.size)
        assertEquals(1, body.flagList().size)
        assertEquals(4, body.flagList().first().jsonObject["post"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `string-typed post from a JS-written list is matched as an update, not appended`() {
        // The original userscript serialises loosely — an existing entry may carry post as a String.
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [
            { "post": "12345", "page": "1", "href": "t1" }
        ] } } ] }"""

        val body = bodyOf(mutate(raw, MpStorageFlagEntry(threadId = 12345, page = 9, numreponse = 8, uri = null)))

        assertEquals(1, body.flagList().size)
        assertEquals(9, body.flagList().first().jsonObject["page"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a content_form mutation over the cap is rejected as TooLarge`() {
        // Stuff a huge third-party blob so the mutated body crosses the cap. The mutation itself is
        // tiny — the cap protects against an uncontrolled POST when the storage is already huge
        // (the DTCloud list is never pruned — ADR-014 risk).
        val filler = "x".repeat(MpStorageRepository.MAX_CONTENT_FORM_BYTES + 1024)
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [] } } ], "blob": "$filler" }"""

        val outcome = mutate(raw, MpStorageFlagEntry(threadId = 1, page = 1, numreponse = 1, uri = null))

        assertTrue(outcome is MpStorageEnvelopeWriter.Outcome.TooLarge)
        val sizeBytes = (outcome as MpStorageEnvelopeWriter.Outcome.TooLarge).sizeBytes
        assertTrue(sizeBytes > MpStorageRepository.MAX_CONTENT_FORM_BYTES)
    }

    @Test
    fun `a mutation just under the cap is accepted`() {
        // Build a body that lands comfortably under the cap after the mutation.
        val filler = "x".repeat(32 * 1024)
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [] } } ], "blob": "$filler" }"""

        val outcome = mutate(raw, MpStorageFlagEntry(threadId = 1, page = 1, numreponse = 1, uri = null))

        assertTrue(outcome is MpStorageEnvelopeWriter.Outcome.Mutated)
    }

    @Test
    fun `non-JSON-object content yields NotJsonEnvelope, never a repaired default`() {
        assertEquals(
            MpStorageEnvelopeWriter.Outcome.NotJsonEnvelope,
            mutate("Bonjour, ceci est un MP normal.", MpStorageFlagEntry(1, 1, 1, null)),
        )
        // A JSON array (not an object) is also rejected.
        assertEquals(
            MpStorageEnvelopeWriter.Outcome.NotJsonEnvelope,
            mutate("[1, 2, 3]", MpStorageFlagEntry(1, 1, 1, null)),
        )
        assertEquals(
            MpStorageEnvelopeWriter.Outcome.NotJsonEnvelope,
            mutate("   ", MpStorageFlagEntry(1, 1, 1, null)),
        )
    }

    // --- UPDATE-ONLY mode (#597 auto trigger) -----------------------------------------------------

    @Test
    fun `updateOnly skips an absent threadId without appending — anti-pollution`() {
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [
            { "post": 111, "page": 1, "href": "t1" }
        ] } } ] }"""

        val entry = MpStorageFlagEntry(threadId = 999, page = 4, numreponse = 2, uri = "/u")
        val outcome = writer.upsertFlag(raw, entry, updateOnly = true)

        // The unknown threadId is NOT added; the verbatim original is returned for the caller to skip the POST.
        val skipped = outcome as MpStorageEnvelopeWriter.Outcome.SkippedNotPresent
        assertEquals(raw, skipped.body)
    }

    @Test
    fun `updateOnly skips when there is no v01 entry or list at all (never creates)`() {
        assertTrue(
            writer.upsertFlag(
                """{ "sourceName": "DTCloud" }""",
                MpStorageFlagEntry(threadId = 1, page = 1, numreponse = 1, uri = null),
                updateOnly = true,
            ) is MpStorageEnvelopeWriter.Outcome.SkippedNotPresent,
        )
        assertTrue(
            writer.upsertFlag(
                """{ "data": [ { "version": "0.2", "foo": 1 } ] }""",
                MpStorageFlagEntry(threadId = 1, page = 1, numreponse = 1, uri = null),
                updateOnly = true,
            ) is MpStorageEnvelopeWriter.Outcome.SkippedNotPresent,
        )
    }

    @Test
    fun `updateOnly updates a present threadId (page, anchor, uri) and preserves third-party keys`() {
        val raw = """
            { "data": [ { "version": "0.1",
                "hfr4k": { "opaque": 1 },
                "mpFlags": { "list": [
                    { "post": 12345, "page": 3, "href": "t1", "uri": "/old", "p": 2 }
                ] } } ],
              "someOtherTool": { "deep": true } }
        """.trimIndent()

        val entry = MpStorageFlagEntry(threadId = 12345, page = 7, numreponse = 555, uri = "/new")
        val body = bodyOf(writer.upsertFlag(raw, entry, updateOnly = true))

        val updated = body.flagList().first().jsonObject
        assertEquals(7, updated["page"]!!.jsonPrimitive.content.toInt())
        assertEquals("t555", updated["href"]!!.jsonPrimitive.content)
        assertEquals("/new", updated["uri"]!!.jsonPrimitive.content)
        assertEquals("2", updated["p"]!!.jsonPrimitive.content)
        // Third-party keys survive both at the v0.1 entry and the top level.
        assertTrue(body.v01Entry().containsKey("hfr4k"))
        assertTrue(body.containsKey("someOtherTool"))
    }

    @Test
    fun `updateOnly with a null anchor PRESERVES the existing href and uri (never nulls them)`() {
        // The auto trigger landed on a page but has no current anchor → it must keep the known anchor/uri.
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [
            { "post": 7, "page": 2, "href": "t99", "uri": "/keep" }
        ] } } ] }"""

        val entry = MpStorageFlagEntry(threadId = 7, page = 5, numreponse = null, uri = null)
        val body = bodyOf(writer.upsertFlag(raw, entry, updateOnly = true))

        val updated = body.flagList().first().jsonObject
        // Page advanced…
        assertEquals(5, updated["page"]!!.jsonPrimitive.content.toInt())
        // …but the existing anchor and uri are kept verbatim, NOT nulled.
        assertEquals("t99", updated["href"]!!.jsonPrimitive.content)
        assertEquals("/keep", updated["uri"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the manual path (updateOnly = false) still appends an absent threadId and nulls absent fields`() {
        // Regression guard: the default behaviour is unchanged by #597.
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [] } } ] }"""

        val body = bodyOf(mutate(raw, MpStorageFlagEntry(threadId = 222, page = 1, numreponse = null, uri = null)))

        // Appended (not skipped), and the absent anchor/uri are written as JSON null (historical).
        val item = body.flagList().single().jsonObject
        assertEquals(222, item["post"]!!.jsonPrimitive.content.toInt())
        assertNull(item["href"]!!.jsonPrimitive.contentOrNullSafe())
        assertNull(item["uri"]!!.jsonPrimitive.contentOrNullSafe())
    }

    @Test
    fun `C4 - a mutated body carrying an astral code point fails closed (UnsafeContent, no Mutated)`() {
        // C4 — a third-party key holds an emoji (an astral, non-BMP code point). HFR silently truncates
        // a posted body at the first such character (#114), so POSTing the re-emitted document would
        // CORRUPT the shared cross-userscript storage. MPStorage must NOT strip (third-party data) →
        // the writer fails closed with UnsafeContent so the caller refuses the POST and leaves the
        // document byte-fidèle.
        val raw = """{ "data": [ { "version": "0.1", "hfr4k": "note 😀", "mpFlags": { "list": [
            { "post": 42, "page": 1, "href": "t1" }
        ] } } ] }"""

        // A real change (page 1 → 2) so the writer reaches the re-emit + detection, not the no-op path.
        val outcome = mutate(raw, MpStorageFlagEntry(threadId = 42, page = 2, numreponse = 5, uri = null))

        assertEquals(MpStorageEnvelopeWriter.Outcome.UnsafeContent, outcome)
    }

    @Test
    fun `C4 - a pure-BMP body (accents, BBCode) is never flagged unsafe`() {
        // Belt-and-braces: the detection must not over-trigger on legitimate BMP content (accented
        // Latin, high-BMP private-use chars) — only astral / lone surrogates fail closed.
        val raw = """{ "data": [ { "version": "0.1", "hfr4k": "café œuvre ", "mpFlags": { "list": [
            { "post": 42, "page": 1, "href": "t1" }
        ] } } ] }"""

        val outcome = mutate(raw, MpStorageFlagEntry(threadId = 42, page = 2, numreponse = 5, uri = null))

        assertTrue("pure-BMP content must mutate normally", outcome is MpStorageEnvelopeWriter.Outcome.Mutated)
    }

    /** `JsonPrimitive.content` is "null" for a literal null — distinguish it from the string "null". */
    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    private companion object {
        private const val NOW_MILLIS = 1_718_064_000_000L
    }
}
