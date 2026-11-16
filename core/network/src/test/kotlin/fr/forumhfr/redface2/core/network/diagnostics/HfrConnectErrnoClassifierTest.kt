package fr.forumhfr.redface2.core.network.diagnostics

import android.system.ErrnoException
import android.system.OsConstants
import fr.forumhfr.redface2.core.domain.diagnostics.HfrConnectErrno
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HfrConnectErrnoClassifierTest {
    @Test
    fun `nested Android errno produces only a closed category`() {
        val cases = mapOf(
            OsConstants.ECONNREFUSED to HfrConnectErrno.REFUSED,
            OsConstants.ENETUNREACH to HfrConnectErrno.UNREACHABLE,
            OsConstants.EHOSTUNREACH to HfrConnectErrno.UNREACHABLE,
            OsConstants.ENETDOWN to HfrConnectErrno.NETWORK_DOWN,
            OsConstants.ENETRESET to HfrConnectErrno.NETWORK_DOWN,
            OsConstants.ECONNRESET to HfrConnectErrno.NETWORK_DOWN,
            OsConstants.ETIMEDOUT to HfrConnectErrno.TIMEOUT,
            OsConstants.EINVAL to HfrConnectErrno.OTHER,
        )
        cases.forEach { (errno, expected) ->
            val inner = ErrnoException("SecretHost 192.0.2.1", errno)
            val error = IOException("SecretCookie", ConnectException("SecretProxy").apply { initCause(inner) })
            assertEquals(expected, classifyConnectErrno(error))
        }
    }

    @Test
    fun `missing errno is other even if the message names an errno or a secret`() {
        assertEquals(HfrConnectErrno.OTHER, classifyConnectErrno(ConnectException("ECONNREFUSED SecretCookie")))
        assertEquals(HfrConnectErrno.OTHER, classifyConnectErrno(IOException("ETIMEDOUT SecretHost")))
        assertEquals(HfrConnectErrno.TIMEOUT, classifyConnectErrno(IOException(SocketTimeoutException())))
        assertEquals(HfrConnectErrno.UNREACHABLE, classifyConnectErrno(IOException(NoRouteToHostException())))
        val unreadableMessage = object : IOException() {
            override val message: String get() = error("The classifier must never read this secret")
        }
        assertEquals(HfrConnectErrno.OTHER, classifyConnectErrno(unreadableMessage))
    }

    @Test
    fun `cause traversal stops at eight including cycles`() {
        var inside: Throwable = ErrnoException("SecretFunction", OsConstants.ECONNREFUSED)
        repeat(7) { inside = IOException(inside) }
        assertEquals(HfrConnectErrno.REFUSED, classifyConnectErrno(inside))
        assertEquals(HfrConnectErrno.OTHER, classifyConnectErrno(IOException(inside)))
        val cyclic = object : IOException() {
            override val cause: Throwable get() = this
        }
        assertEquals(HfrConnectErrno.OTHER, classifyConnectErrno(cyclic))
    }
}
