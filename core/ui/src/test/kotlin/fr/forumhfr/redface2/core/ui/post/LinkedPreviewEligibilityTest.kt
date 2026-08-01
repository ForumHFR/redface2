package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #876 ([AMENDEMENT-v1.5-4], clause « Éligibilité — garde G3 ») — pure-JVM contract on the linked
 * preview guard: no Compose runtime, no Robolectric, no probe. Every case below is one of the
 * re-gate checks of the amendment, so a regression on the guard order (non-null → absolute
 * HTTP(S) → G1 → host → G2) or on its assumed residuals (`http`/`https`, trailing slash, `www.`,
 * sub-domains, and the two pinned `java.net.URI` quirks: underscore host, terminal dot) surfaces
 * here.
 */
class LinkedPreviewEligibilityTest {

    private val thumbnailPx = IntSize(150, 112)

    @Test
    fun `un linkUrl absent ou blanc n'est pas un aperçu lié`() {
        // Garde 1 = non NUL, strictement (le contrat) ; un linkUrl blanc n'est pas une URL
        // absolue, il échoue donc à la garde 2 — même verdict, garde différente.
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, null, thumbnailPx))
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, "", thumbnailPx))
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, "   ", thumbnailPx))
    }

    @Test
    fun `la garde 2 exige une URL HTTP(S) absolue - pas un préfixe`() {
        // "https://" (sans autorité) ne parse pas, une URL relative ou sans schéma n'est pas
        // absolue : tout échoue à la garde 2, AVANT G1 et la garde d'hôte — l'ordre opérant du
        // contrat, pas seulement le verdict final.
        assertFalse(isEligibleLinkedPreview("https://", REHOST_FULL_URL, thumbnailPx))
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, "https://", thumbnailPx))
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, "/f/abcdef0123456789.jpg", thumbnailPx))
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, "reho.st/f/abcdef0123456789.jpg", thumbnailPx))
    }

    @Test
    fun `la garde 2 exige une autorité non vide - un schéma seul ne suffit pas`() {
        // `URI.isAbsolute` n'atteste qu'un schéma : sans l'exigence d'autorité non vide, ces trois
        // formes franchissaient la garde 2 et n'étaient rejetées qu'à la garde d'hôte — le verdict
        // était le bon mais pas l'ordre normatif du contrat.
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, "http:foo", thumbnailPx))
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, "https:/path", thumbnailPx))
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, "https:///path", thumbnailPx))
        assertFalse(isEligibleLinkedPreview("http:foo", REHOST_FULL_URL, thumbnailPx))
        assertFalse(isEligibleLinkedPreview("https:/path", REHOST_FULL_URL, thumbnailPx))
        assertFalse(isEligibleLinkedPreview("https:///path", REHOST_FULL_URL, thumbnailPx))
    }

    @Test
    fun `le validateur d'autorité de la garde 2 rejette lui-même les URL sans autorité`() {
        // Non ambigu par construction : le booléen d'isEligibleLinkedPreview ne distingue pas la
        // garde fautive (l'hôte null de ces URI ferait AUSSI échouer la garde d'hôte), donc on
        // épingle le validateur de la garde 2 directement — ces assertions ne peuvent réussir que
        // parce que LA GARDE 2 rejette.
        assertNull(absoluteHttpUriOrNull("http:foo"))
        assertNull(absoluteHttpUriOrNull("https:/path"))
        assertNull(absoluteHttpUriOrNull("https:///path"))
        // Contre-épingle : l'hôte à underscore PASSE la garde 2 (autorité non vide, host null) —
        // son rejet appartient à la garde d'hôte. Un durcissement en `host != null` casserait ICI.
        assertNotNull(absoluteHttpUriOrNull("https://my_host.example.com/t/1.jpg"))
        // Contrôle positif : une URL saine parse et porte son autorité.
        assertNotNull(absoluteHttpUriOrNull(REHOST_THUMB_URL))
    }

    @Test
    fun `un auto-lien exact est exclu par G1`() {
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, REHOST_THUMB_URL, thumbnailPx))
        // G1 compare les chaînes après trim : l'espacement seul ne recrée pas un lien distinct.
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, "  $REHOST_THUMB_URL  ", thumbnailPx))
    }

    @Test
    fun `les résiduels assumés de G1 passent la garde`() {
        // Résiduels explicitement assumés par l'amendement : G1 est une inégalité de chaînes SANS
        // normalisation, donc le schéma seul ou le slash final suffisent à passer — borné par G2.
        assertTrue(
            isEligibleLinkedPreview(
                url = "https://example.com/a.jpg",
                linkUrl = "http://example.com/a.jpg",
                nativePx = thumbnailPx,
            ),
        )
        assertTrue(
            isEligibleLinkedPreview(
                url = "https://example.com/a.jpg",
                linkUrl = "https://example.com/a.jpg/",
                nativePx = thumbnailPx,
            ),
        )
    }

    @Test
    fun `une casse d'hôte différente reste le même hôte`() {
        assertTrue(
            isEligibleLinkedPreview(
                url = "https://Example.COM/t/1.jpg",
                linkUrl = "https://example.com/f/1.jpg",
                nativePx = thumbnailPx,
            ),
        )
    }

    @Test
    fun `www et le domaine nu sont des hôtes différents`() {
        // Voulu : AUCUN retrait de `www.` — une vraie miniature peut perdre l'agrandissement.
        assertFalse(
            isEligibleLinkedPreview(
                url = "https://www.example.com/t/1.jpg",
                linkUrl = "https://example.com/f/1.jpg",
                nativePx = thumbnailPx,
            ),
        )
    }

    @Test
    fun `un port différent ne change pas l'hôte`() {
        assertTrue(
            isEligibleLinkedPreview(
                url = "https://example.com:8443/t/1.jpg",
                linkUrl = "https://example.com/f/1.jpg",
                nativePx = thumbnailPx,
            ),
        )
    }

    @Test
    fun `deux sous-domaines distincts sont des hôtes différents`() {
        // `i.imgur.com ≠ imgur.com` : le motif miniature Imgur liée vers sa page N'EST PAS éligible.
        assertFalse(
            isEligibleLinkedPreview(
                url = "https://i.imgur.com/abcdef.jpg",
                linkUrl = "https://imgur.com/abcdef",
                nativePx = thumbnailPx,
            ),
        )
    }

    @Test
    fun `une URL non parsable n'est pas éligible`() {
        // URL réelles non encodées : espace et `|` font échouer le parseur strict, dès la
        // garde 2 (le parse unique) → non éligible.
        assertFalse(
            isEligibleLinkedPreview(
                url = "https://example.com/mon image.jpg",
                linkUrl = "https://example.com/f/1.jpg",
                nativePx = thumbnailPx,
            ),
        )
        assertFalse(
            isEligibleLinkedPreview(
                url = "https://example.com/t/1.jpg",
                linkUrl = "https://example.com/a|b.jpg",
                nativePx = thumbnailPx,
            ),
        )
    }

    @Test
    fun `résiduel épinglé - un hôte à underscore est rejeté`() {
        // Résiduel assumé du parseur strict retenu (java.net.URI) : un underscore invalide le
        // hostname, le champ `host` parsé est null → la garde d'hôte échoue, des deux côtés.
        assertFalse(
            isEligibleLinkedPreview(
                url = "https://my_host.example.com/t/1.jpg",
                linkUrl = "https://my_host.example.com/f/1.jpg",
                nativePx = thumbnailPx,
            ),
        )
        assertFalse(
            isEligibleLinkedPreview(
                url = "https://example.com/t/1.jpg",
                linkUrl = "https://my_host.example.com/f/1.jpg",
                nativePx = thumbnailPx,
            ),
        )
    }

    @Test
    fun `résiduel épinglé - le point terminal d'hôte est conservé`() {
        // Résiduel assumé : aucune normalisation DNS, le point final reste dans le champ `host`
        // parsé — `example.com.` et `example.com` sont donc deux hôtes DIFFÉRENTS…
        assertFalse(
            isEligibleLinkedPreview(
                url = "https://example.com./t/1.jpg",
                linkUrl = "https://example.com/f/1.jpg",
                nativePx = thumbnailPx,
            ),
        )
        // … et le même point conservé des DEUX côtés reste un même hôte.
        assertTrue(
            isEligibleLinkedPreview(
                url = "https://example.com./t/1.jpg",
                linkUrl = "https://example.com./f/1.jpg",
                nativePx = thumbnailPx,
            ),
        )
    }

    @Test
    fun `un hôte absent n'est pas éligible`() {
        // Depuis l'exigence d'autorité non vide, `https:///…` échoue dès la garde 2 (épinglé par
        // le test direct du validateur ci-dessus) ; le verdict final reste inchangé.
        assertFalse(isEligibleLinkedPreview("https:///t/1.jpg", "https:///f/1.jpg", thumbnailPx))
        assertFalse(
            isEligibleLinkedPreview(
                url = "https://example.com/t/1.jpg",
                linkUrl = "https:///f/1.jpg",
                nativePx = thumbnailPx,
            ),
        )
    }

    @Test
    fun `un schéma non HTTP(S) portant un hôte n'est pas éligible`() {
        assertFalse(
            isEligibleLinkedPreview(
                url = "https://example.com/t/1.jpg",
                linkUrl = "ftp://example.com/f/1.jpg",
                nativePx = thumbnailPx,
            ),
        )
        assertFalse(
            isEligibleLinkedPreview(
                url = "ftp://example.com/t/1.jpg",
                linkUrl = "https://example.com/f/1.jpg",
                nativePx = thumbnailPx,
            ),
        )
    }

    @Test
    fun `un grand axe natif de 400 px passe la garde G2`() {
        assertTrue(isEligibleLinkedPreview(REHOST_THUMB_URL, REHOST_FULL_URL, IntSize(400, 300)))
        assertTrue(isEligibleLinkedPreview(REHOST_THUMB_URL, REHOST_FULL_URL, IntSize(300, 400)))
    }

    @Test
    fun `un grand axe natif de 401 px échoue à la garde G2`() {
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, REHOST_FULL_URL, IntSize(401, 300)))
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, REHOST_FULL_URL, IntSize(300, 401)))
    }

    @Test
    fun `des dimensions natives inconnues échouent fail-closed`() {
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, REHOST_FULL_URL, null))
        // Une paire dégénérée n'est pas une mesure : même traitement fail-closed.
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, REHOST_FULL_URL, IntSize(0, 0)))
    }

    @Test
    fun `un seul axe nul échoue fail-closed`() {
        // Un axe valide ne rachète pas l'autre : zéro n'est pas une mesure.
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, REHOST_FULL_URL, IntSize(0, 112)))
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, REHOST_FULL_URL, IntSize(150, 0)))
    }

    @Test
    fun `un axe négatif échoue fail-closed`() {
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, REHOST_FULL_URL, IntSize(-150, 112)))
        assertFalse(isEligibleLinkedPreview(REHOST_THUMB_URL, REHOST_FULL_URL, IntSize(150, -112)))
    }

    @Test
    fun `une miniature rehost liée vers son full du même hôte est éligible`() {
        // Cas réel du chantier : diberie `/t/ID` (150×112) enveloppée par `/f/ID`.
        assertTrue(isEligibleLinkedPreview(REHOST_THUMB_URL, REHOST_FULL_URL, IntSize(150, 112)))
    }

    @Test
    fun `mApercu plafonne l'agrandissement à 3`() {
        assertEquals(0.75f, linkedPreviewUpscaleCeiling(0.75f), 0f)
        assertEquals(2f, linkedPreviewUpscaleCeiling(2f), 0f)
        assertEquals(3f, linkedPreviewUpscaleCeiling(3f), 0f)
        assertEquals(3f, linkedPreviewUpscaleCeiling(4f), 0f)
    }

    private companion object {
        const val REHOST_THUMB_URL = "https://reho.st/t/abcdef0123456789.jpg"
        const val REHOST_FULL_URL = "https://reho.st/f/abcdef0123456789.jpg"
    }
}
