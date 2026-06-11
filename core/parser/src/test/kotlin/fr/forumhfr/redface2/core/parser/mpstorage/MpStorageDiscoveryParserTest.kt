package fr.forumhfr.redface2.core.parser.mpstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MPStorage discovery (#6, ADR-014) — both fixtures captured live 2026-06-11 from the
 * authenticated `cat=prive` subject search (cf. their `.source.txt`).
 */
class MpStorageDiscoveryParserTest {

    private val parser = MpStorageDiscoveryParser()

    @Test
    fun `a hit listing yields the first conversation's thread id`() {
        // First row of the listing (HFR default sort : last-message date) — renumbered 9000003.
        val html = readFixture("mp_storage_search_hit.html")
        assertEquals(9_000_003, parser.parseFirstThreadId(html))
    }

    @Test
    fun `the no-results page yields null — the account has no storage MP`() {
        val html = readFixture("mp_storage_search_no_results.html")
        assertNull(parser.parseFirstThreadId(html))
    }

    private fun readFixture(name: String): String {
        val stream = requireNotNull(
            MpStorageDiscoveryParserTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
        ) { "Fixture not found: fixtures/$name" }
        return stream.bufferedReader().use { it.readText() }
    }
}
