package fr.forumhfr.redface2.core.ui.post

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil3.compose.AsyncImagePainter

/**
 * #960 (§6) — the PAINTER-axis gate of ONE on-screen occurrence of a media url. Reads the
 * [MediaAttemptLedger] through tracked snapshot reads and takes AT MOST one reservation per
 * (url, generation):
 *
 *  - [failedFresh] → the occurrence composes the §6 error state and NEVER a painter node, so no
 *    network attempt can fire (the pre-#960 pipeline re-attempted per occurrence and per key()
 *    bump);
 *  - terminal success → [renderPainter] without any settlement (Coil serves its caches);
 *  - untried → the FIRST occurrence wins the reservation and reports the painter outcome through
 *    [onState]; concurrent occurrences hold the placeholder until the winner settles (the ledger's
 *    snapshot write recomposes them onto the settled branch).
 *
 * The reservation is taken in a [LaunchedEffect] — never during composition (an abandoned
 * composition would leak an in-flight axis) — and rolls back on dispose while unsettled: the
 * AsyncImage's request is cancelled with its composition, so nobody else would ever settle the
 * axis (a cancelled try is not a try).
 */
@Stable
internal class PainterAttempt(
    private val ledger: MediaAttemptLedger,
    private val url: String,
    private val generation: Int,
) {
    private var granted by mutableStateOf(false)
    private var settled = false

    /** Fresh painter failure on record — compose the error state, never a painter node. */
    val failedFresh: Boolean
        get() = ledger.isFailedFresh(url, MediaAttemptKind.PAINTER, System.currentTimeMillis())

    /** True when THIS occurrence composes the painter node (own grant, or a terminal success). */
    val renderPainter: Boolean
        get() = granted || ledger.hasSucceeded(url, MediaAttemptKind.PAINTER)

    fun reserveIfUntried() {
        if (ledger.tryReserve(url, generation, MediaAttemptKind.PAINTER)) granted = true
    }

    /** Settles the granted attempt on the painter's terminal states; loading/empty are ignored. */
    fun onState(state: AsyncImagePainter.State) {
        if (!granted || settled) return
        when (state) {
            is AsyncImagePainter.State.Success -> {
                settled = true
                ledger.settleSuccess(url, generation, MediaAttemptKind.PAINTER)
            }

            is AsyncImagePainter.State.Error -> {
                settled = true
                ledger.settleFailure(url, generation, MediaAttemptKind.PAINTER, System.currentTimeMillis())
            }

            else -> Unit
        }
    }

    fun rollbackIfUnsettled() {
        if (granted && !settled) ledger.rollbackReservation(url, generation, MediaAttemptKind.PAINTER)
    }
}

/**
 * Remembers the [PainterAttempt] of this occurrence of [url]. Keyed on the url's ledger
 * GENERATION (a tracked read): a scoped retry recreates the attempt — and with it the whole
 * painter node, replacing the pre-#960 screen-wide `key(refreshGeneration)` bumps.
 */
@Composable
internal fun rememberPainterAttempt(url: String): PainterAttempt {
    val ledger = LocalMediaAttemptLedger.current
    val generation = ledger.generationOf(url)
    val attempt = remember(ledger, url, generation) { PainterAttempt(ledger, url, generation) }
    LaunchedEffect(attempt) { attempt.reserveIfUntried() }
    DisposableEffect(attempt) { onDispose { attempt.rollbackIfUnsettled() } }
    return attempt
}
