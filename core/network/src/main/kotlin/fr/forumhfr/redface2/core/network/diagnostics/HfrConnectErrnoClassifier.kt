package fr.forumhfr.redface2.core.network.diagnostics

import android.system.ErrnoException
import android.system.OsConstants
import fr.forumhfr.redface2.core.domain.diagnostics.HfrConnectErrno
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/** A short cause chain only: never messages, suppressed errors, stack traces or string matching. */
internal fun classifyConnectErrno(error: Throwable): HfrConnectErrno {
    var cause: Throwable? = error
    var category = HfrConnectErrno.OTHER
    repeat(MAX_CAUSES) {
        val current = cause
        if (category == HfrConnectErrno.OTHER) category = when (current) {
            is ErrnoException -> classifyErrno(current.errno)
            is SocketTimeoutException -> HfrConnectErrno.TIMEOUT
            is NoRouteToHostException -> HfrConnectErrno.UNREACHABLE
            else -> HfrConnectErrno.OTHER
        }
        cause = current?.cause
    }
    return category
}

private fun classifyErrno(errno: Int): HfrConnectErrno = when (errno) {
    OsConstants.ECONNREFUSED -> HfrConnectErrno.REFUSED
    OsConstants.ENETUNREACH, OsConstants.EHOSTUNREACH -> HfrConnectErrno.UNREACHABLE
    OsConstants.ENETDOWN, OsConstants.ENETRESET, OsConstants.ECONNRESET -> HfrConnectErrno.NETWORK_DOWN
    OsConstants.ETIMEDOUT -> HfrConnectErrno.TIMEOUT
    else -> HfrConnectErrno.OTHER
}

private const val MAX_CAUSES = 8
