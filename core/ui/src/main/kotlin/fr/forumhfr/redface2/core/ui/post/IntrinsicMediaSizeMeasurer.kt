package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/**
 * #175/#257/#959 — probe a media's NATIVE ORIENTED dimensions via a **header-only** decode
 * ([ProbeMetadataDecoder], attached per request — cadrage Sol Lot 3, Q1 option b).
 *
 * The pre-#959 probe requested a 1024-bounded FIT decode and read `image.width/height` from the
 * decoded bitmap — which CLIPPED the reported dimensions of any source past the bound (measured:
 * 4000×3000 → 1024×768, EXIF 900×1200 → 768×1024), violating §3 "the probe must never clip the
 * reported native dimensions". The header-only decoder reads the bounds (and EXIF orientation)
 * without ever allocating the bitmap — cheaper than the old bounded decode AND exact at any size.
 *
 * The memory cache is disabled BOTH ways on this request: the metadata pseudo-image must never be
 * served to a render request (and a cached render bitmap must not short-circuit the probe with
 * its possibly-resized dimensions — the §3 "first valid pair" authority stays with the probe).
 * The disk policy comes from the rendering host. Public posts keep it active so the downloaded
 * bytes serve the subsequent render decode. Content in private messages bypasses this probe
 * entirely (v1.6-10); private smiley probes retain their disk-disabled behavior (#1096).
 * `execute()` is main-safe (Coil dispatches its own I/O); the caller invokes it from a
 * `LaunchedEffect` and caches the result by URL. Returns `null` on error / non-positive
 * dimensions. The returned size is in SOURCE PIXELS — the §3 equation consumes it as physical px.
 *
 * #973 ([AMENDEMENT-v1.5-2]) — the result is the ATOMIC [IntrinsicMediaMetadata]: the size plus
 * the MIME the header decode identified (through the [ProbeMetadataImage] carrier). A success
 * that did not flow through the probe decoder (or an unidentified container) carries a `null`
 * MIME — never one inferred from the URL. A failed probe returns `null`: no size, no MIME.
 */
internal suspend fun measureIntrinsicMediaSize(
    url: String,
    context: PlatformContext,
    imageLoader: ImageLoader,
    diskCachePolicy: PostMediaDiskCachePolicy = PostMediaDiskCachePolicy.ENABLED,
): IntrinsicMediaMetadata? {
    val result = imageLoader.execute(
        ImageRequest.Builder(context)
            .data(url)
            .decoderFactory(ProbeMetadataDecoder.Factory)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(diskCachePolicy.coilPolicy)
            .build(),
    )
    val image = (result as? SuccessResult)?.image ?: return null
    return if (image.width > 0 && image.height > 0) {
        IntrinsicMediaMetadata(
            size = IntSize(image.width, image.height),
            mimeType = (image as? ProbeMetadataImage)?.mimeType,
        )
    } else {
        null
    }
}

/**
 * #249 follow-up / #960 (§6) — shared probe seam for #175/#224 paragraphs and standalone blocks.
 * #813: a surviving occurrence waits for the winner's ticket and takes over after rollback;
 * returning immediately on a lost reservation would leave it cold after the winner is disposed.
 * v1.6-10 retains that protocol while content sizing authority moves from the memo to the ledger.
 * The key includes the ledger identity for isolated renderer/test scopes. It deliberately spans
 * generations: a replacement waits for the old execution to terminate before probing again.
 */
private data class MeasurementKey(val ledger: MediaAttemptLedger, val url: String)
private val inFlightMeasurements = ConcurrentHashMap<MeasurementKey, CompletableDeferred<Unit>>()

/** v1.6-10 — dedicated content-probe budget; owner cancellation remains a rollback. */
internal const val CONTENT_MEDIA_PROBE_TIMEOUT_MILLIS = 30_000L

// LongParameterList: the trailing `probe` is a test-only seam (cancellation-race pins); the real
// parameters are the url, its pipeline collaborators and the host's persistence policy — grouping
// them would be a one-call holder class with no other purpose.
@Suppress("LongParameterList")
internal suspend fun measureAndCacheIntrinsicMediaSize(
    url: String,
    cache: IntrinsicMediaSizeCache,
    ledger: MediaAttemptLedger,
    context: PlatformContext,
    imageLoader: ImageLoader,
    diskCachePolicy: PostMediaDiskCachePolicy = PostMediaDiskCachePolicy.ENABLED,
    isContentMedia: Boolean = true,
    // Injectable for the cancellation-race tests only — production callers keep the default.
    probe: suspend (String, PlatformContext, ImageLoader) -> IntrinsicMediaMetadata? =
        { probeUrl, probeContext, loader ->
            measureIntrinsicMediaSize(probeUrl, probeContext, loader, diskCachePolicy)
        },
) {
    if (isContentMedia && diskCachePolicy == PostMediaDiskCachePolicy.DISABLED) return
    val key = MeasurementKey(ledger, url)
    while (true) {
        val now = System.currentTimeMillis()
        val known = if (isContentMedia) ledger.geometryOf(url) != null else cache.get(url) != null
        if (known || ledger.isFailedFresh(url, MediaAttemptKind.PROBE, now)) return
        val ticket = CompletableDeferred<Unit>()
        val winner = inFlightMeasurements.putIfAbsent(key, ticket)
        if (winner != null) {
            // Lost the race — wait for the in-flight probe to settle (result OR cancellation),
            // then loop: a landed result short-circuits on the guards, a cancelled probe rolled
            // its reservation back and this caller takes over.
            winner.await()
        } else {
            try {
                probeUnderReservation(url, cache, ledger, context, imageLoader, probe, now, isContentMedia)
            } finally {
                inFlightMeasurements.remove(key, ticket)
                ticket.complete(Unit)
            }
            break
        }
    }
}

// LongParameterList: private tail of the seam above — same collaborators, same rationale.
@Suppress("LongParameterList")
private suspend fun probeUnderReservation(
    url: String,
    cache: IntrinsicMediaSizeCache,
    ledger: MediaAttemptLedger,
    context: PlatformContext,
    imageLoader: ImageLoader,
    probe: suspend (String, PlatformContext, ImageLoader) -> IntrinsicMediaMetadata?,
    nowMillis: Long,
    isContentMedia: Boolean,
) {
    // Only smileys still size from the FIFO memo; content authority never reopens on eviction.
    if (!isContentMedia && ledger.hasSucceeded(url, MediaAttemptKind.PROBE)) {
        ledger.reopenSmileyProbeForLostMemo(url)
    }
    // C1 — consulting may open a new generation when the recorded failure has expired; the
    // reservation is then taken against the CURRENT generation. A denied reservation means the
    // axis settled while this caller raced through the guards — nothing to do.
    val generation = ledger.consultGeneration(url, nowMillis)
    if (!ledger.tryReserve(url, generation, MediaAttemptKind.PROBE)) return
    var settled = false
    try {
        val metadata = if (isContentMedia) {
            probeWithDeadline { probe(url, context, imageLoader) }
        } else {
            probe(url, context, imageLoader)
        }
        // A dead owner must not publish, even when an implementation swallowed cancellation.
        currentCoroutineContext().ensureActive()
        if (metadata != null && metadata.size.width > 0 && metadata.size.height > 0) {
            if (isContentMedia) {
                // #960 P2 first pair; #973 [AMENDEMENT-v1.5-2] atomic metadata. v1.6-10 moves
                // the merge to the ledger: dimensions stay fixed, only null MIME may be enriched.
                ledger.acceptGeometry(url, generation, metadata, MediaAttemptKind.PROBE, cache)
            } else if (ledger.generationOf(url) == generation) {
                cache.putSuccessIfAbsent(url, metadata)
                ledger.settleSuccess(url, generation, MediaAttemptKind.PROBE)
            }
        } else {
            ledger.settleFailure(url, generation, MediaAttemptKind.PROBE, System.currentTimeMillis())
        }
        settled = true
    } finally {
        // A cancelled try is not a try: reopen the axis so the awaiting loser (or the next
        // occurrence) may attempt again.
        if (!settled) ledger.rollbackReservation(url, generation, MediaAttemptKind.PROBE)
    }
}

/**
 * withTimeout returns/throws only after its child execution has terminated, including cleanup.
 * Publish failure afterwards: observing a settled PROBE is sufficient to safely authorize G2.
 * An outer owner cancellation is rethrown, so it takes the rollback path instead of failure.
 */
private suspend fun probeWithDeadline(probe: suspend () -> IntrinsicMediaMetadata?): IntrinsicMediaMetadata? =
    try {
        withTimeout(CONTENT_MEDIA_PROBE_TIMEOUT_MILLIS) {
            val metadata = probe()
            currentCoroutineContext().ensureActive()
            metadata
        }
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        null
    }
