package fr.forumhfr.redface2.core.network

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/** Conservative whole-Call evidence, including OkHttp's internal route retries and fast fallback. */
internal class FlagReadConnectionPhase {
    private var tcpStarted = false
    private var tcpFailed = false
    private var beyondTcp = false

    @Synchronized fun connecting() { tcpStarted = true }
    @Synchronized fun connectFailed() { tcpFailed = true }
    @Synchronized fun connected() { beyondTcp = true }

    @Synchronized fun canRetry(error: IOException): Boolean =
        tcpStarted && tcpFailed && !beyondTcp && when (error) {
            is ConnectException, is NoRouteToHostException, is SocketTimeoutException -> true
            // Unknown SocketException / errno, DNS, cancellation and TLS are deliberately excluded.
            else -> false
        }
}
