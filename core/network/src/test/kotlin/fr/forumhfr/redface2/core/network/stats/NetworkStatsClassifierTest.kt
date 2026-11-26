package fr.forumhfr.redface2.core.network.stats

import fr.forumhfr.redface2.core.domain.stats.NetworkPeakGroup
import fr.forumhfr.redface2.core.domain.stats.NetworkRole
import fr.forumhfr.redface2.core.domain.stats.NetworkTarget
import fr.forumhfr.redface2.core.network.diagnostics.NetworkStatsClassifier
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkStatsClassifierTest {
    @Test
    fun `wire URLs project to every closed target`() {
        val forum = "https://forum.hardware.fr"
        val rest = "$forum/webservices/rest_api.php?uri=forums/hardwarefr"
        val cases = listOf(
            Triple("$rest/categories/", NetworkRole.ANON, NetworkTarget.REST_CATALOGUE),
            Triple("$rest/categories/23/topics/participated/", NetworkRole.AUTH,
                NetworkTarget.REST_CATEGORY_PARTICIPATED),
            Triple("$rest/categories/23/topics/read/", NetworkRole.AUTH, NetworkTarget.REST_CATEGORY_READ),
            Triple("$rest/categories/23/subcategories/9/topics/favorites/", NetworkRole.AUTH,
                NetworkTarget.REST_CATEGORY_FAVORITES),
            Triple("$rest/categories/23/topics/last/", NetworkRole.AUTH, NetworkTarget.REST_CATEGORY_LAST),
            Triple("$rest/categories/23/subcategories/", NetworkRole.AUTH, NetworkTarget.REST_SUBCATEGORIES),
            Triple("$rest/topics/participated/", NetworkRole.AUTH, NetworkTarget.REST_GLOBAL_PARTICIPATED),
            Triple("$rest/topics/read/", NetworkRole.AUTH, NetworkTarget.REST_GLOBAL_READ),
            Triple("$rest/topics/favorites/", NetworkRole.AUTH, NetworkTarget.REST_GLOBAL_FAVORITES),
            Triple("$rest/topics/unknown/", NetworkRole.AUTH, NetworkTarget.REST_OTHER),
            Triple("$forum/forum1f.php?cat=23", NetworkRole.AUTH, NetworkTarget.HTML_FORUM1),
            Triple("$forum/forum2.php?cat=prive&post=123", NetworkRole.AUTH, NetworkTarget.HTML_FORUM2),
            Triple("$forum/forum1.php?cat=prive", NetworkRole.AUTH, NetworkTarget.HTML_MP_LIST),
            Triple("$forum/bddpost.php", NetworkRole.MUTATION, NetworkTarget.HTML_WRITE),
            Triple("$forum/message.php", NetworkRole.AUTH, NetworkTarget.HTML_WRITE),
            Triple("$forum/bdd.php", NetworkRole.MUTATION, NetworkTarget.HTML_WRITE),
            Triple("$forum/other.php?name=alice", NetworkRole.AUTH, NetworkTarget.HTML_OTHER),
            Triple("$forum/user/supprperso_validation.php?codehex=5ec7e7", NetworkRole.SMILEY,
                NetworkTarget.FAVORITE_SMILEYS),
            Triple("$forum/mesimages/private.gif", NetworkRole.AUTH, NetworkTarget.FORUM_IMAGES),
            Triple("https://forum-images.hardware.fr/private/path", NetworkRole.IMAGE, NetworkTarget.FORUM_IMAGES),
            Triple("https://image.invalid/no-extension", NetworkRole.IMAGE, NetworkTarget.THIRD_PARTY_IMAGES),
            Triple("https://upload.invalid/delete/123", NetworkRole.UPLOAD, NetworkTarget.THIRD_PARTY_UPLOAD),
            Triple("$rest/categories/", NetworkRole.PROBE, NetworkTarget.PROBE),
            Triple("https://forum.hardware.fr.invalid/forum2.php", NetworkRole.AUTH, NetworkTarget.OTHER_HOST),
        )
        for ((url, role, expected) in cases) {
            assertEquals(expected, NetworkStatsClassifier.target(url.toHttpUrl(), role))
        }
        assertEquals(NetworkTarget.entries.toSet(), cases.map { it.third }.toSet())
    }

    @Test
    fun `peaks group forum and forum images by destination`() {
        assertEquals(NetworkPeakGroup.FORUM,
            NetworkStatsClassifier.peakGroup("https://forum.hardware.fr/webservices/rest_api.php".toHttpUrl()))
        assertEquals(NetworkPeakGroup.FORUM,
            NetworkStatsClassifier.peakGroup("https://forum.hardware.fr/images/smiley.gif".toHttpUrl()))
        assertEquals(NetworkPeakGroup.FORUM_IMAGES,
            NetworkStatsClassifier.peakGroup("https://forum-images.hardware.fr/path".toHttpUrl()))
        assertNull(NetworkStatsClassifier.peakGroup("https://image.invalid/path".toHttpUrl()))
    }
}
