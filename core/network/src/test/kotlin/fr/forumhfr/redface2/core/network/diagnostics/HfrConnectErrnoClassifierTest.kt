package fr.forumhfr.redface2.core.network.diagnostics

import android.system.ErrnoException
import android.system.OsConstants
import fr.forumhfr.redface2.core.domain.diagnostics.AppForegroundDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.HfrConnectErrno
import fr.forumhfr.redface2.core.domain.stats.NetworkRole
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsBuffer
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsProcess
import fr.forumhfr.redface2.core.domain.stats.NoRequestReason
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
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
    fun `nested Android connect timeout is recorded through callFailed`() = runTest {
        val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        val buffer = NetworkStatsBuffer(NetworkStatsProcess(72, isMain = true))
        val foreground = AppForegroundDiagnostics(DiagnosticsLog())
        val listener = NetworkStatsListenerFactory(buffer, foreground, clock).create(NetworkRole.ANON)
        val request = Request.Builder().url("https://forum.hardware.fr/forum2.php").build()
        val call = OkHttpClient().newCall(request)
        val address = InetSocketAddress.createUnresolved("forum.hardware.fr", 443)
        val inner = ConnectException().apply { initCause(ErrnoException("connect", OsConstants.ETIMEDOUT)) }
        val failure = IOException(inner)

        listener.callStart(call)
        listener.connectFailed(call, address, Proxy.NO_PROXY, null, failure)
        listener.callFailed(call, failure)

        assertEquals(NoRequestReason.TCP_TIMEOUT, buffer.drain().getValue(0).noRequests.keys.single().reason)
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
