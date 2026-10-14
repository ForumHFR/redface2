package fr.forumhfr.redface2.core.network.diagnostics

import fr.forumhfr.redface2.core.domain.diagnostics.FlagFetchTrace
import fr.forumhfr.redface2.core.domain.diagnostics.HfrRequestQueued
import fr.forumhfr.redface2.core.model.FlagType
import kotlin.coroutines.EmptyCoroutineContext
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HfrCallTagTest {
    @Test
    fun `request tag transports fetch correlation and consumes queue time only on the first page`() {
        val fetch = FlagFetchTrace(7, FlagType.RED)
        val context = fetch + HfrRequestQueued(42)
        val original = Request.Builder().url("https://forum.hardware.fr/forum2.php?cat=23&post=35395")
            .header("Cookie", "SecretCookie").build()
        val first = original.withHfrDiagnostics(context)
        val tag = requireNotNull(first.tag(HfrCallTag::class.java))
        assertSame(fetch, tag.fetch)
        assertEquals(42L, tag.queuedNanos)
        assertNull(tag.executeNanos)
        tag.executing(100)
        assertEquals(100L, tag.executeNanos)
        val second = original.withHfrDiagnostics(context).tag(HfrCallTag::class.java)!!
        assertFalse("A later page must not include the previous page's execution time", second.queuedNanos == 42L)
        assertEquals(original.url, first.url)
        assertEquals(original.headers, first.headers)
        assertEquals(original.method, first.method)
        assertNull(original.withHfrDiagnostics(EmptyCoroutineContext).tag(HfrCallTag::class.java)?.fetch)
    }
}
