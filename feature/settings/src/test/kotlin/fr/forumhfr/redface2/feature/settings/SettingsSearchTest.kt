package fr.forumhfr.redface2.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #494 — pure unit tests for [filterSettingsSections]. JUnit4 only, no Robolectric / Android
 * resources: the filter is synchronous and Android-free by design.
 */
class SettingsSearchTest {

    private fun sections() = listOf(
        SettingsSearchableSection(
            id = "display",
            title = "Affichage",
            items = listOf(
                SettingsSearchableItem(
                    id = "theme",
                    title = "Thème",
                    description = "Clair, Système ou Sombre",
                    keywords = listOf("amoled", "couleur"),
                ),
                SettingsSearchableItem(
                    id = "density",
                    title = "Densité et police de lecture",
                ),
            ),
        ),
        SettingsSearchableSection(
            id = "editing",
            title = "Édition et publication",
            items = listOf(
                SettingsSearchableItem(
                    id = "confirm",
                    title = "Confirmation avant publication",
                ),
            ),
        ),
        SettingsSearchableSection(
            id = "notifications",
            title = "Notifications",
            items = listOf(
                // Planned (disabled) — must stay searchable.
                SettingsSearchableItem(
                    id = "future_notifications",
                    title = "Relève configurable",
                    enabled = false,
                ),
            ),
        ),
        // A navigation row whose target sub-page hosts controls only reachable by their displayed
        // label (e.g. « Hôte » in Proxy). The row carries those labels as keywords so search routes
        // to the sub-page instead of the empty state (#494 Codex P2).
        SettingsSearchableSection(
            id = "network",
            title = "Réseau et cache",
            items = listOf(
                SettingsSearchableItem(
                    id = "proxy",
                    title = "Proxy",
                    description = "Configurer un proxy HTTP",
                    keywords = listOf("proxy", "Hôte", "Port"),
                ),
            ),
        ),
        SettingsSearchableSection(
            id = "mp",
            title = "Messages privés",
            items = listOf(
                // Gated off — must be excluded entirely.
                SettingsSearchableItem(
                    id = "dt_inspector",
                    title = "Inspecteur MP storage",
                    visible = false,
                ),
                SettingsSearchableItem(
                    id = "mp_badge",
                    title = "Badge de MP non lus",
                ),
            ),
        ),
    )

    @Test
    fun `blank query returns all sections with visible items`() {
        val result = filterSettingsSections(sections(), "")
        // The MP section keeps only its visible item; every other section is present.
        assertEquals(listOf("display", "editing", "notifications", "network", "mp"), result.map { it.id })
        val mp = result.first { it.id == "mp" }
        assertEquals(listOf("mp_badge"), mp.items.map { it.id })
    }

    @Test
    fun `matches on title`() {
        val result = filterSettingsSections(sections(), "Thème")
        assertEquals(listOf("display"), result.map { it.id })
        assertEquals(listOf("theme"), result.first().items.map { it.id })
    }

    @Test
    fun `matches on description`() {
        val result = filterSettingsSections(sections(), "Sombre")
        assertEquals(listOf("theme"), result.flatMap { it.items }.map { it.id })
    }

    @Test
    fun `matches on keywords`() {
        val result = filterSettingsSections(sections(), "couleur")
        assertEquals(listOf("theme"), result.flatMap { it.items }.map { it.id })
    }

    @Test
    fun `is accent insensitive`() {
        // "systeme" (no accent) must match the "Clair, Système ou Sombre" description.
        val result = filterSettingsSections(sections(), "systeme")
        assertEquals(listOf("theme"), result.flatMap { it.items }.map { it.id })
    }

    @Test
    fun `is case insensitive`() {
        val result = filterSettingsSections(sections(), "THÈME")
        assertEquals(listOf("theme"), result.flatMap { it.items }.map { it.id })
    }

    @Test
    fun `omits sections emptied by the filter`() {
        val result = filterSettingsSections(sections(), "Thème")
        assertNull(result.firstOrNull { it.id == "editing" })
        assertNull(result.firstOrNull { it.id == "notifications" })
        assertNull(result.firstOrNull { it.id == "mp" })
    }

    @Test
    fun `disabled future item stays searchable`() {
        val result = filterSettingsSections(sections(), "Relève")
        val items = result.flatMap { it.items }
        assertEquals(listOf("future_notifications"), items.map { it.id })
        assertTrue(items.all { !it.enabled })
    }

    @Test
    fun `matches a sub-page label carried as a nav-row keyword`() {
        // « Hôte » is only shown inside the Proxy sub-page; the nav row carries it as a keyword so
        // searching it (accent-folded) routes to the network section instead of the empty state.
        val result = filterSettingsSections(sections(), "hote")
        assertEquals(listOf("network"), result.map { it.id })
        assertEquals(listOf("proxy"), result.first().items.map { it.id })
    }

    @Test
    fun `invisible item is excluded even when it matches`() {
        val result = filterSettingsSections(sections(), "Inspecteur")
        assertTrue(result.isEmpty())
    }
}
