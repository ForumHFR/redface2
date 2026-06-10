package fr.forumhfr.redface2.core.domain.error

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #324 — full truth table of [classifyHfrError]. The classification drives which shared
 * string every reading screen renders, so each row pins one contract:
 * 5xx → ServerDown, transport failures → Network, everything else (including the
 * session-expiry special case and 4xx) → Other.
 */
class HfrErrorClassifierTest {

    @Test
    fun `HfrServerException 500 classifies as ServerDown`() {
        assertEquals(
            HfrErrorKind.ServerDown,
            classifyHfrError(HfrServerException(code = 500, url = "https://forum.hardware.fr/forum2.php")),
        )
    }

    @Test
    fun `HfrServerException 503 classifies as ServerDown`() {
        assertEquals(
            HfrErrorKind.ServerDown,
            classifyHfrError(HfrServerException(code = 503, url = "https://forum.hardware.fr/forum2.php")),
        )
    }

    @Test
    fun `HfrServerException 404 classifies as Other not ServerDown`() {
        // A 4xx means the request was wrong (dead topic id, bad cat) — HFR itself is fine,
        // so the screen must keep its generic message rather than claim an outage.
        assertEquals(
            HfrErrorKind.Other,
            classifyHfrError(HfrServerException(code = 404, url = "https://forum.hardware.fr/forum2.php")),
        )
    }

    @Test
    fun `HfrServerException with a custom detail message keeps classifying on the code`() {
        // The REST client (HfrApiClient) passes its richer body-excerpt message — the
        // classification must come from the typed code, never from message parsing.
        assertEquals(
            HfrErrorKind.ServerDown,
            classifyHfrError(
                HfrServerException(
                    code = 502,
                    url = "https://forum.hardware.fr/webservices/rest_api.php",
                    detailMessage = "HFR REST returned 502 for … — body[0..9]: bad gw",
                ),
            ),
        )
    }

    @Test
    fun `SessionExpiredException classifies as Other even though it is an IOException`() {
        // Non-régression du CTA session expirée (FlagsRoute) : la branche session des écrans
        // passe AVANT le classifieur, et une session expirée n'est jamais une coupure réseau.
        assertEquals(
            HfrErrorKind.Other,
            classifyHfrError(SessionExpiredException("https://forum.hardware.fr/login.php")),
        )
    }

    @Test
    fun `UnknownHostException classifies as Network`() {
        assertEquals(
            HfrErrorKind.Network,
            classifyHfrError(UnknownHostException("forum.hardware.fr")),
        )
    }

    @Test
    fun `SocketTimeoutException classifies as Network`() {
        assertEquals(
            HfrErrorKind.Network,
            classifyHfrError(SocketTimeoutException("timeout")),
        )
    }

    @Test
    fun `plain IOException classifies as Network`() {
        assertEquals(
            HfrErrorKind.Network,
            classifyHfrError(IOException("unexpected end of stream")),
        )
    }

    @Test
    fun `IllegalStateException classifies as Other`() {
        assertEquals(
            HfrErrorKind.Other,
            classifyHfrError(IllegalStateException("parser invariant broken")),
        )
    }
}
