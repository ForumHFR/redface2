package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize

/**
 * #973 (§8, [AMENDEMENT-v1.5-2]) — atomic native oriented dimensions plus decoded-header MIME.
 * That amendment originally made the whole cache entry immutable after its first deposit.
 * [AMENDEMENT-v1.6-10] moves content authority into the ledger and supersedes only null→known MIME.
 * URL extensions never supply MIME; painter results carry null. Content authority lives in
 * [MediaAttemptLedger]: the first current valid pair fixes dimensions and provenance, while a
 * reliable current probe may later enrich null MIME without changing geometry (v1.6-10).
 */
internal data class IntrinsicMediaMetadata(
    val size: IntSize,
    val mimeType: String?,
)
