package fr.forumhfr.redface2.core.parser.mpstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MPStorage (#6, ADR-014). The samples follow the published v0.1 contract
 * (`MPStorage.user.js` + Wiripse doc) — a third-party JSON contract, not HFR HTML.
 */
class MpStorageParserTest {

    private val parser = MpStorageParser()

    @Test
    fun `parses a DTCloud document and projects the mpFlags entries`() {
        val document = """
            {
              "data": [
                {
                  "version": "0.1",
                  "mpFlags": {
                    "list": [
                      { "uri": "/forum2.php?config=hfr.inc&cat=prive&post=12345&page=3#t1980000001",
                        "post": 12345, "page": 3, "href": "t1980000001", "p": 2 },
                      { "uri": "/forum2.php?config=hfr.inc&cat=prive&post=67890&page=1",
                        "post": 67890, "page": 1, "href": "", "p": 1 }
                    ]
                  }
                }
              ],
              "sourceName": "DTCloud",
              "lastUpdate": 1718064000000
            }
        """.trimIndent()

        val parsed = parser.parse(document).getOrThrow()

        assertEquals("DTCloud", parsed.sourceName)
        assertEquals(2, parsed.mpFlags.size)
        val first = parsed.mpFlags.first()
        assertEquals(12345, first.threadId)
        assertEquals(3, first.page)
        assertEquals(1_980_000_001, first.numreponse)
        assertTrue(first.uri!!.contains("post=12345"))
        // Empty href degrades to a null anchor, the entry stays consumable.
        assertNull(parsed.mpFlags[1].numreponse)
        // The verbatim text survives for the future read-modify-write.
        assertEquals(document, parsed.rawEnvelope)
    }

    @Test
    fun `tolerates string-typed numbers from JS serialisation`() {
        val document = """
            { "data": [ { "version": "0.1",
                "mpFlags": { "list": [ { "post": "12345", "page": "2", "href": "t42" } ] } } ] }
        """.trimIndent()

        val parsed = parser.parse(document).getOrThrow()

        assertEquals(12345, parsed.mpFlags.first().threadId)
        assertEquals(2, parsed.mpFlags.first().page)
        assertEquals(42, parsed.mpFlags.first().numreponse)
    }

    @Test
    fun `unknown tool keys are not an error and survive in the raw envelope`() {
        val document = """
            { "data": [ { "version": "0.1",
                "hfr4k": { "opaque": [1, 2, 3] },
                "mpFlags": { "list": [] } } ],
              "sourceName": "HFR4K" }
        """.trimIndent()

        val parsed = parser.parse(document).getOrThrow()

        assertEquals(emptyList<Any>(), parsed.mpFlags)
        assertTrue("third-party keys must survive verbatim", parsed.rawEnvelope.contains("hfr4k"))
    }

    @Test
    fun `a data array without a v01 entry is a valid document with no consumable section`() {
        // A future format written by another tool must NOT read as corruption (ADR-014 :
        // corruption triggers an explicit failure, and failures must never be "repaired").
        val document = """{ "data": [ { "version": "0.2", "somethingNew": true } ] }"""

        val parsed = parser.parse(document).getOrThrow()

        assertEquals(emptyList<Any>(), parsed.mpFlags)
        assertNull(parsed.sourceName)
    }

    @Test
    fun `entries without a usable post id are skipped not fatal`() {
        val document = """
            { "data": [ { "version": "0.1",
                "mpFlags": { "list": [ { "page": 1 }, { "post": 777, "page": 4 } ] } } ] }
        """.trimIndent()

        val parsed = parser.parse(document).getOrThrow()

        assertEquals(1, parsed.mpFlags.size)
        assertEquals(777, parsed.mpFlags.first().threadId)
    }

    @Test
    fun `page falls back to 1 when absent`() {
        val document = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [ { "post": 5 } ] } } ] }"""

        assertEquals(1, parser.parse(document).getOrThrow().mpFlags.first().page)
    }

    @Test
    fun `rawEnvelope is verbatim — surrounding whitespace is preserved`() {
        // Codex review of #406 : the future read-modify-write must round-trip exactly what
        // the edit form served, including leading/trailing whitespace.
        val document = "\n  { \"data\": [], \"sourceName\": \"DTCloud\" }  \n"

        val parsed = parser.parse(document).getOrThrow()

        assertEquals(document, parsed.rawEnvelope)
    }

    @Test
    fun `non-JSON content fails explicitly`() {
        assertTrue(parser.parse("Bonjour, ceci est un MP normal.").isFailure)
    }

    @Test
    fun `a JSON object without a data array fails explicitly`() {
        assertTrue(parser.parse("""{ "sourceName": "DTCloud" }""").isFailure)
    }

    @Test
    fun `empty content fails explicitly`() {
        assertTrue(parser.parse("   ").isFailure)
    }
}
