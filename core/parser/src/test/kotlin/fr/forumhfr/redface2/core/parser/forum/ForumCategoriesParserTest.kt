package fr.forumhfr.redface2.core.parser.forum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForumCategoriesParserTest {

    private val parser = ForumCategoriesParser()

    @Test
    fun `parse returns the 19 categories present in the captured root page`() {
        val index = parser.parse(readFixture("forum_root.html"))

        // 19 categories — derived from the captured root page (April 2026). The brief
        // initially announced 20, but the fixture itself only has 19 `<tr class="cat">`
        // rows (no `Blabla - Divers` at capture time). The model is anchored on what the
        // fixture proves.
        assertEquals(19, index.categories.size)
    }

    @Test
    fun `Hardware category exposes its 14 subcategories with the expected slug`() {
        val index = parser.parse(readFixture("forum_root.html"))
        val hardware = index.categories.first { it.name == "Hardware" }

        assertEquals("Hardware", hardware.slug)
        assertEquals(14, hardware.subcategories.size)
        assertTrue(
            "All Hardware subcategories must reference Hardware as parent",
            hardware.subcategories.all { it.parentCategorySlug == "Hardware" },
        )
    }

    @Test
    fun `Discussions category exposes its 15 subcategories including Viepratique`() {
        val index = parser.parse(readFixture("forum_root.html"))
        val discussions = index.categories.first { it.name == "Discussions" }

        assertEquals("Discussions", discussions.slug)
        assertEquals(15, discussions.subcategories.size)

        val viepratique = discussions.subcategories.firstOrNull { it.slug == "Viepratique" }
        assertNotNull("Viepratique subcategory must be present in Discussions", viepratique)
        // HFR's display name for Viepratique includes a space ("Vie pratique"); the slug
        // does not. The model preserves both since display name and slug serve different
        // purposes (UI vs URL building).
        assertEquals("Vie pratique", viepratique!!.name)
        assertEquals("Discussions", viepratique.parentCategorySlug)
    }

    @Test
    fun `AchatsVentes is parsed with its dual-segment cCatTopic href as the AchatsVentes slug`() {
        // Special case: the `Achats & Ventes` category's cCatTopic anchor points at
        // `/hfr/AchatsVentes/Hardware/liste_sujet-1.htm`. The category slug is the FIRST
        // path segment (`AchatsVentes`); the second (`Hardware`) is a real subcategory
        // and must show up in the subcategories list.
        val index = parser.parse(readFixture("forum_root.html"))
        val achatsVentes = index.categories.firstOrNull { it.slug == "AchatsVentes" }
        assertNotNull("AchatsVentes category must be parsed even with a dual-segment cCatTopic href", achatsVentes)
        assertEquals("Achats & Ventes", achatsVentes!!.name)
        assertTrue(
            "AchatsVentes must list Hardware as a subcategory",
            achatsVentes.subcategories.any { it.slug == "Hardware" },
        )
    }

    @Test
    fun `parser drops moderator MP-to-modo links from subcategories`() {
        val index = parser.parse(readFixture("forum_root.html"))
        // The captured page hosts ~70 `<a class="Tableau">` links pointing at
        // `/message.php?...&dest=<modo>` (the moderator column). None of these must
        // surface as subcategories — they are MP composer links, not forum sections.
        val anyHrefLikeSubcategorySlug = index.categories
            .flatMap { it.subcategories }
            .map { it.slug }
        assertFalse(
            "Moderator handles must not appear as subcategory slugs",
            "TotalRecall" in anyHrefLikeSubcategorySlug ||
                "asmomo" in anyHrefLikeSubcategorySlug ||
                "Fouge" in anyHrefLikeSubcategorySlug,
        )
    }

    @Test
    fun `every parsed category has a non-blank name and slug`() {
        val index = parser.parse(readFixture("forum_root.html"))
        index.categories.forEach { category ->
            assertTrue("blank name in $category", category.name.isNotBlank())
            assertTrue("blank slug in $category", category.slug.isNotBlank())
            category.subcategories.forEach { sub ->
                assertTrue("blank subcategory name in $sub", sub.name.isNotBlank())
                assertTrue("blank subcategory slug in $sub", sub.slug.isNotBlank())
                assertEquals(category.slug, sub.parentCategorySlug)
            }
        }
    }

    private fun readFixture(name: String): String {
        val resource = javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?: error("Missing fixture: fixtures/$name")
        return resource.bufferedReader().use { it.readText() }
    }
}
