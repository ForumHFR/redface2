package fr.forumhfr.redface2.core.parser.search

import fr.forumhfr.redface2.core.model.search.SearchCategoryScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2G-A (#150 partiel) — tests for [SearchResultParser] driven by the four
 * fixtures captured anonymously on 2026-05-22 :
 *
 *  - `search_no_results.html`             — minimal `.hop` page.
 *  - `search_kotlin_pivot_single.html`    — pivot dropdown with one option.
 *  - `search_android_pivot_multi.html`    — pivot dropdown with 18 options.
 *  - `search_kotlin_explicit_cat.html`    — no pivot, listing only.
 *
 * Each `@Test` exercises one invariant. We deliberately avoid synthetic HTML
 * for the four happy paths so the parser remains anchored to real HFR markup.
 */
class SearchResultParserTest {

    private val parser = SearchResultParser()

    @Test
    fun `no-results fixture returns an empty page without throwing`() {
        val html = readFixture("search_no_results.html")
        val page = parser.parse(html, query = "xqzkbm9wj4abc", requestedCategory = SearchCategoryScope.All)
        assertEquals(emptyList<Any>(), page.topics)
        assertEquals(emptyList<Any>(), page.pivotCategories)
        assertNull(page.selectedCategory)
        assertEquals(1, page.currentPage)
        assertEquals(1, page.totalPages)
    }

    @Test
    fun `pivot single fixture exposes one pivot option and three topic rows`() {
        val html = readFixture("search_kotlin_pivot_single.html")
        val page = parser.parse(html, query = "kotlin", requestedCategory = SearchCategoryScope.All)

        assertEquals(1, page.pivotCategories.size)
        val onlyPivot = page.pivotCategories.single()
        assertEquals(10, onlyPivot.id)
        assertEquals("Programmation", onlyPivot.label)
        assertTrue(onlyPivot.isSelected)
        assertEquals(onlyPivot, page.selectedCategory)

        assertEquals(3, page.topics.size)
        val first = page.topics.first()
        assertEquals(148_695, first.topicId)
        assertEquals("[Langages fonctionnels] Mes débuts en kotlin (android)", first.title)
        assertEquals(10, first.cat)
        // Title-search responses don't carry a numreponse anchor — must NOT
        // be invented (the prompt's risk list calls this out explicitly).
        assertNull(first.numreponse)
        assertNull(first.page)
        assertFalse(first.isLocked)
        assertEquals("Lt Ripley", first.author)
        assertEquals(2, first.replyCount)
        assertEquals(2635, first.viewCount)
        assertEquals("Langages-fonctionnels", first.subcategorySlug)
        assertEquals("Programmation", first.categorySlug)
    }

    @Test
    fun `pivot multi fixture exposes all 18 hit categories and finds the locked topic`() {
        val html = readFixture("search_android_pivot_multi.html")
        val page = parser.parse(html, query = "android", requestedCategory = SearchCategoryScope.All)

        assertEquals(18, page.pivotCategories.size)
        val byId = page.pivotCategories.associateBy { it.id }
        // Spot-check the four canonical ids the prompt requires.
        assertEquals("Hardware", byId[1]?.label)
        assertEquals("Programmation", byId[10]?.label)
        assertEquals("Technologies Mobiles", byId[23]?.label)
        assertEquals("Discussions", byId[13]?.label)
        // Only the first option (Hardware, cat=1) should be `selected`.
        assertEquals(1, page.selectedCategory?.id)
        assertEquals(1, page.pivotCategories.count { it.isSelected })

        // Multi-cat listings only show the rows of the FIRST hit category — here
        // Hardware, where topic 1059847 is locked.
        val locked = page.topics.firstOrNull { it.topicId == 1_059_847 }
        assertNotNull("expected topic 1059847 in the Hardware results", locked)
        assertTrue("topic 1059847 should be flagged as locked", locked!!.isLocked)
        // All topics in the listing carry the same cat = 1 (Hardware).
        assertTrue(page.topics.all { it.cat == 1 })
    }

    @Test
    fun `explicit-cat fixture has no pivot and reuses the requested category`() {
        val html = readFixture("search_kotlin_explicit_cat.html")
        val page = parser.parse(
            html,
            query = "kotlin",
            requestedCategory = SearchCategoryScope.Category(id = 10, name = "Programmation"),
        )

        // No banner → no pivot. The footer `form#goto select[name=cat]` (present
        // on every forum1.php page) uses plain integer values and lives OUTSIDE
        // `div.search` — must not be picked up as a pivot.
        assertEquals(emptyList<Any>(), page.pivotCategories)
        assertNull(page.selectedCategory)

        assertEquals(3, page.topics.size)
        assertTrue("all rows should inherit the requested cat", page.topics.all { it.cat == 10 })
        // Same first topic as the pivot-single capture (deterministic listing).
        assertEquals(148_695, page.topics.first().topicId)
    }

    @Test
    fun `lastReplyAt is normalised of HFR's non-breaking spaces`() {
        val html = readFixture("search_kotlin_pivot_single.html")
        val first = parser.parse(html, query = "kotlin", requestedCategory = SearchCategoryScope.All).topics.first()
        // HFR renders « 24-09-2025 à 06:48 » ; the parser substitutes regular spaces
        // so consumers don't have to. Author lands as the `<b>` child.
        assertFalse("expected NBSP to be stripped, got <${first.lastReplyAt}>", first.lastReplyAt.contains(' '))
        assertTrue(
            "expected the date prefix in lastReplyAt, got <${first.lastReplyAt}>",
            first.lastReplyAt.startsWith("24-09-2025"),
        )
        assertEquals("Lt Ripley", first.lastReplyAuthor)
    }

    @Test
    fun `a page with rows but no detectable cat raises ParseException`() {
        // Synthetic minimal HTML : one row, no `div.search` pivot, no banner.
        // Combined with a `SearchCategoryScope.All` request, the parser cannot
        // attribute a cat to the row and must fail typed.
        val syntheticHtml = """
            <html><body>
              <div class="mesdiscussions" id="mesdiscussions">
                <table class="main">
                  <tr class="cBackHeader fondForum1Description"><th>Sujet</th></tr>
                  <tr class="sujet">
                    <td class="sujetCase1"><img src="x.gif"/></td>
                    <td class="sujetCase2"><img src="y.gif"/></td>
                    <td class="sujetCase3"><a href="/hfr/Fake/Fake/x-sujet_1_1.htm" class="cCatTopic" title="Sujet n°1">x</a></td>
                    <td class="sujetCase4"></td>
                    <td class="sujetCase5"></td>
                    <td class="sujetCase6">Author</td>
                    <td class="sujetCase7">1</td>
                    <td class="sujetCase8">2</td>
                    <td class="sujetCase9"><a class="Tableau">01-01-2026</a></td>
                  </tr>
                </table>
              </div>
            </body></html>
        """.trimIndent()
        val ex = assertThrows(SearchResultParser.ParseException::class.java) {
            parser.parse(syntheticHtml, query = "x", requestedCategory = SearchCategoryScope.All)
        }
        assertTrue(
            "expected ParseException to mention the missing cat context, got <${ex.message}>",
            ex.message!!.contains("cat"),
        )
    }

    @Test
    fun `a malformed topic row with a known cat raises ParseException`() {
        // Once a cat context is known, topic rows are no longer optional :
        // silently dropping a malformed row would turn a broken HFR response
        // into a false "no result" page.
        val syntheticHtml = """
            <html><body>
              <div class="mesdiscussions" id="mesdiscussions">
                <table class="main">
                  <tr class="cBackHeader fondForum1Description"><th>Sujet</th></tr>
                  <tr class="sujet">
                    <td class="sujetCase3">missing topic anchor</td>
                  </tr>
                </table>
              </div>
            </body></html>
        """.trimIndent()
        val ex = assertThrows(SearchResultParser.ParseException::class.java) {
            parser.parse(
                syntheticHtml,
                query = "x",
                requestedCategory = SearchCategoryScope.Category(id = 10, name = "Programmation"),
            )
        }
        assertTrue(
            "expected ParseException to mention the missing topic anchor, got <${ex.message}>",
            ex.message!!.contains("a.cCatTopic"),
        )
    }

    @Test
    fun `pivot fixture exposes a pivot independently of the requested page`() {
        // Sanity check : the requestedPage parameter influences the fallback
        // pagination but does not affect pivot / row parsing.
        val html = readFixture("search_kotlin_pivot_single.html")
        val page = parser.parse(
            html,
            query = "kotlin",
            requestedCategory = SearchCategoryScope.All,
            requestedPage = 7,
        )
        // Fixture pager reports `Page : 1`, so the parser must IGNORE the
        // requested fallback and surface what HFR sent.
        assertEquals(1, page.currentPage)
        assertEquals(1, page.totalPages)
    }

    private fun readFixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "Fixture not found: $name"
        }.bufferedReader().use { it.readText() }
}
