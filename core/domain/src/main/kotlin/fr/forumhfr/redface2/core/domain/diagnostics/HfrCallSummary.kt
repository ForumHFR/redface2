package fr.forumhfr.redface2.core.domain.diagnostics

import fr.forumhfr.redface2.core.model.FlagType
import java.util.concurrent.atomic.AtomicBoolean

/** Only public endpoint metadata crosses the network/diagnostics boundary. */
data class HfrEndpoint(
    val family: Family,
    val category: Int? = null,
    val page: Int? = null,
) {
    enum class Family(val label: String) {
        CATEGORIES("rest:categories"),
        PARTICIPATED("rest:bucket:participated"),
        READ("rest:bucket:read"),
        FAVORITES("rest:bucket:favorites"),
        LAST("rest:last"),
        SUBCATEGORIES("rest:subcategories"),
        REST_OTHER("rest:other"),
        FORUM1("html:forum1"),
        FORUM2("html:forum2"),
        HTML_OTHER("html:other"),
    }

    fun label(): String = "${family.label} cat=${category ?: "-"} page=${page ?: "-"}"
}

enum class HfrClientRole { AUTH, ANON, MUTATION, UPLOAD }
enum class HfrCallOutcome { OK, FAILURE, CANCELLED }
enum class HfrIpFamily(val label: String) { V4("v4"), V6("v6"), UNKNOWN("-") }
enum class FlagRefreshPhase { CATALOGUE, BUCKETS, STICKIES, SORT, MUTEX, ROOM }

// Each duration is a separate transport landmark, not a bag of arbitrary log fields.
@Suppress("LongParameterList")
data class HfrCallSummary(
    val id: Long,
    val endpoint: HfrEndpoint,
    val role: HfrClientRole,
    val refreshId: Long?,
    val phase: FlagRefreshPhase?,
    val waitMs: Long?,
    val dnsMs: Long,
    val connectMs: Long,
    val tlsMs: Long,
    val headersSentToReceivedMs: Long?,
    val bodyMs: Long,
    val totalMs: Long,
    val status: Int?,
    val protocol: String?,
    val reusedConnection: Boolean,
    val inFlightAtStart: Int,
    val outcome: HfrCallOutcome,
    val exceptionClass: String? = null,
    val flagType: FlagType? = null,
    val connectAttempts: Int = 0,
    val connectFailures: Int = 0,
    val connectAbandoned: Int = 0,
    // Like connectMs, failedConnectMs includes TLS; TLS is also reported separately.
    val failedConnectMs: Long = 0,
    val maxFailedConnectMs: Long = 0,
    val failedTlsMs: Long = 0,
    val maxFailedTlsMs: Long = 0,
    val ipFamily: HfrIpFamily = HfrIpFamily.UNKNOWN,
    // Copies retain this call-local claim, including catalogue ownership and late attachment.
    private val detailPublished: AtomicBoolean = AtomicBoolean(),
) {
    fun claimDetail(): Boolean = detailPublished.compareAndSet(false, true)

    fun isDetailPublished(): Boolean = detailPublished.get()

    fun line(): String =
        "refresh=${refreshId ?: "-"} ${endpoint.label()} role=${role.name.lowercase()} " +
            "type=${flagType?.name ?: "-"} phase=${phase?.name?.lowercase() ?: "-"} " +
            "waitMs=${waitMs ?: "-"} dnsMs=$dnsMs " +
            "connectMs=$connectMs tlsMs=$tlsMs connectAttempts=$connectAttempts connectFailures=$connectFailures " +
            "connectAbandoned=$connectAbandoned failedConnectMs=$failedConnectMs " +
            "maxFailedConnectMs=$maxFailedConnectMs failedTlsMs=$failedTlsMs maxFailedTlsMs=$maxFailedTlsMs " +
            "headersSentToReceivedMs=${headersSentToReceivedMs ?: "-"} " +
            "bodyMs=$bodyMs totalMs=$totalMs status=${status ?: "-"} protocol=${protocol ?: "-"} " +
            "reused=$reusedConnection inFlight=$inFlightAtStart outcome=${outcome.name.lowercase()} " +
            "exception=${exceptionClass ?: "-"} ip=${ipFamily.label}"
}

/** One quota across all types, attempts and joined fetches of a refresh. */
object HfrCallDetailPolicy {
    const val SLOW_CALL_MS = 1_000L
    const val MAX_REFRESH_DETAILS = 12

    fun includes(summary: HfrCallSummary): Boolean =
        summary.totalMs >= SLOW_CALL_MS || summary.outcome != HfrCallOutcome.OK
}

/** Never use Throwable.message, toString, stack traces or request data at this boundary. */
fun Throwable.diagnosticClassName(): String = javaClass.simpleName
    .filter { it.isLetterOrDigit() || it == '_' || it == '$' }
    .take(80)

fun elapsedDiagnosticMs(startNanos: Long, endNanos: Long = System.nanoTime()): Long =
    ((endNanos - startNanos).coerceAtLeast(0L)) / 1_000_000L
