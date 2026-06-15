package fr.forumhfr.redface2.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #494 v2 — contrat de routage de la racine « catégories d'abord » : les catégories adossées à une
 * sous-page dédiée y mènent directement, les autres ouvrent le détail générique ; le mapping
 * catégorie → section(s) du catalogue est figé (notamment « À venir » qui agrège 3 sections futures).
 */
class SettingsCategoriesTest {

    private class Spy {
        var display = 0
        var images = 0
        var accountAbout = 0
        var category: String? = null

        fun route(id: String) = routeSettingsCategory(
            id = id,
            onOpenDisplay = { display++ },
            onOpenImages = { images++ },
            onOpenAccountAbout = { accountAbout++ },
            onOpenCategory = { category = it },
        )
    }

    @Test
    fun `dedicated categories route straight to their sub-page`() {
        with(Spy()) {
            route("display")
            assertEquals(1, display)
            assertNull(category)
        }
        with(Spy()) {
            route("images")
            assertEquals(1, images)
            assertNull(category)
        }
        with(Spy()) {
            route("account")
            assertEquals(1, accountAbout)
            assertNull(category)
        }
    }

    @Test
    fun `other categories open the generic detail with their id`() {
        for (id in listOf("flags", "topic", "mp", "editing", "start", "network", "upcoming")) {
            with(Spy()) {
                route(id)
                assertEquals(id, category)
                assertEquals(0, display + images + accountAbout)
            }
        }
    }

    @Test
    fun `upcoming aggregates the three future sections`() {
        assertEquals(listOf("notifications", "accessibility", "extensions"), sectionIdsForCategory("upcoming"))
    }

    @Test
    fun `a regular category maps to its own section id`() {
        assertEquals(listOf("flags"), sectionIdsForCategory("flags"))
        assertEquals(listOf("network"), sectionIdsForCategory("network"))
    }

    @Test
    fun `every category title resolves to a non-zero resource`() {
        for (id in listOf("network", "start", "flags", "topic", "editing", "mp", "upcoming")) {
            assertTrue("title res for $id", categoryTitleRes(id) != 0)
        }
    }
}
