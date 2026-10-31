package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.HfrEndpoint
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HfrEndpointClassifierTest {
    @Test
    fun `only endpoint family public category and page survive classification`() {
        val cases = listOf(
            "webservices/rest_api.php?uri=forums%2Fhardwarefr%2Fcategories%2F" to HfrEndpoint.Family.CATEGORIES,
            "webservices/rest_api.php?uri=forums%2Fhardwarefr%2Fcategories%2F23%2Ftopics%2Fparticipated%2F" to
                HfrEndpoint.Family.PARTICIPATED,
            "webservices/rest_api.php?uri=forums/hardwarefr/categories/23/topics/read/" to HfrEndpoint.Family.READ,
            "webservices/rest_api.php?uri=forums/hardwarefr/categories/23/subcategories/7/topics/favorites/" to
                HfrEndpoint.Family.FAVORITES,
            "webservices/rest_api.php?uri=forums/hardwarefr/categories/23/topics/last/" to HfrEndpoint.Family.LAST,
            "webservices/rest_api.php?uri=forums/hardwarefr/categories/23/subcategories/" to
                HfrEndpoint.Family.SUBCATEGORIES,
            "webservices/rest_api.php?uri=forums/hardwarefr/categories/23/topics/35395/" to
                HfrEndpoint.Family.REST_OTHER,
            "forum1.php?cat=23&pseudo=SecretPseudo" to HfrEndpoint.Family.FORUM1,
            "forum2.php?cat=23&post=35395&numreponse=987654&hash_check=SecretHash" to HfrEndpoint.Family.FORUM2,
            "user/delflag.php?cat=23&post=35395&hash_check=SecretHash" to HfrEndpoint.Family.HTML_OTHER,
        )
        cases.forEach { (path, family) ->
            val endpoint = classifyHfrEndpoint("https://forum.hardware.fr/$path&page=2&cookie=SecretCookie".toHttpUrl())
            assertEquals(path, family, endpoint?.family)
            val output = endpoint.toString() + endpoint?.label()
            listOf("35395", "987654", "Secret", "?", "=forums", "hash_check", "cookie", "hardware.fr").forEach {
                assertFalse("Leaked $it in $output", output.contains(it))
            }
        }
        assertEquals(HfrEndpoint(HfrEndpoint.Family.FORUM2, 23, 2),
            classifyHfrEndpoint("https://forum.hardware.fr/forum2.php?cat=23&post=35395&page=2".toHttpUrl()))
    }

    @Test
    fun `private categories malformed values and images cannot supply identity metadata`() {
        val privatePage = classifyHfrEndpoint(
            "https://forum.hardware.fr/forum2.php?cat=prive&post=35395&page=oops&hash_check=SecretHash".toHttpUrl(),
        )
        assertEquals(HfrEndpoint(HfrEndpoint.Family.FORUM2), privatePage)
        assertNull(classifyHfrEndpoint("https://forum.hardware.fr/images/perso/SecretPseudo.gif".toHttpUrl()))
        assertNull(classifyHfrEndpoint("https://rehost.diberie.com/picture/raw/35395".toHttpUrl()))
        assertNull(classifyHfrEndpoint("https://proxy.example/forum2.php?cat=23&post=35395".toHttpUrl()))
        assertEquals(
            HfrEndpoint.Family.REST_OTHER,
            classifyHfrEndpoint(
                "https://forum.hardware.fr/webservices/rest_api.php?uri=SecretPseudo".toHttpUrl(),
            )?.family,
        )
    }
}
