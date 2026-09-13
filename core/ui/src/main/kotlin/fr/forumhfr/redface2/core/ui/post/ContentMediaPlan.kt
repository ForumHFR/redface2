package fr.forumhfr.redface2.core.ui.post

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import kotlin.math.ceil

/** Physical display caps of one occurrence, independent of its cold/exact box and MIME. */
internal data class ContentMediaConstraints(
    val maxWidthPx: Int,
    val maxHeightPx: Float,
    val contentCeiling: Float = 1f,
)

/** v1.6-10 G2: independent inclusive buckets, bounded before arithmetic to avoid overflow. */
internal fun g2DecodeSizePx(constraints: ContentMediaConstraints): IntSize {
    fun bucket(cap: Double): Int =
        (ceil(cap.coerceIn(1.0, DECODE_MAX_PX.toDouble()) / DECODE_BUCKET_PX) * DECODE_BUCKET_PX).toInt()
    return IntSize(bucket(constraints.maxWidthPx.toDouble()), bucket(constraints.maxHeightPx.toDouble()))
}

/** Null withholds the painter; a known pair always takes priority over probe state/policy. */
internal fun contentMediaDecodeTarget(
    geometry: IntSize?,
    probeSettled: Boolean,
    diskCachePolicy: PostMediaDiskCachePolicy,
    constraints: ContentMediaConstraints,
): IntSize? = when {
    geometry != null -> {
        val displayed = imageDisplaySizePx(
            geometry, constraints.maxWidthPx, constraints.maxHeightPx, constraints.contentCeiling,
        )
        decodeSizePx(displayed.width, geometry)
    }
    diskCachePolicy == PostMediaDiskCachePolicy.DISABLED || probeSettled -> g2DecodeSizePx(constraints)
    else -> null
}

/** One request target per mount × generation × constraints. G2 never re-decodes on late geometry. */
@Stable
internal class ContentMediaPlan(initialTarget: IntSize?) {
    var decodeSize: IntSize? by mutableStateOf(initialTarget)
        private set

    fun resolve(target: IntSize?) {
        if (decodeSize == null && target != null) decodeSize = target
    }
}

/** Shared by block and inline content only; smileys and cc retain their existing pipelines. */
@Composable
internal fun rememberContentMediaPlan(url: String, constraints: ContentMediaConstraints): ContentMediaPlan {
    val ledger = LocalMediaAttemptLedger.current
    val cache = LocalIntrinsicMediaSizeCache.current
    val context = LocalPlatformContext.current
    val diskCachePolicy = LocalPostMediaDiskCachePolicy.current
    val generation = ledger.generationOf(url)
    val candidate = contentMediaDecodeTarget(
        ledger.geometryOf(url)?.size, ledger.isSettled(url, MediaAttemptKind.PROBE), diskCachePolicy, constraints,
    )
    val plan = remember(url, ledger, generation, constraints, diskCachePolicy) { ContentMediaPlan(candidate) }
    LaunchedEffect(plan, candidate) { plan.resolve(candidate) }
    // Probe lifetime does not depend on geometry/constraints: a current in-flight reliable probe
    // may finish and enrich MIME even if another host's painter has already supplied dimensions.
    LaunchedEffect(url, ledger, cache, context, generation, diskCachePolicy) {
        measureAndCacheIntrinsicMediaSize(
            url, cache, ledger, context, SingletonImageLoader.get(context), diskCachePolicy,
        )
    }
    return plan
}
