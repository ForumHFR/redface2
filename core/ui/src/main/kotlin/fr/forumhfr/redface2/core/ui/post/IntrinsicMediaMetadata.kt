package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize

/**
 * #973 (contrat images §8, [AMENDEMENT-v1.5-2]) — the ATOMIC intrinsic metadata of a media URL:
 * the native ORIENTED [size] (§3, the geometry authority) plus the [mimeType] the PROBE's
 * header-only bounds decode identified (e.g. `image/gif`), or `null` when no MIME is known.
 *
 * Contractual rules carried by this type:
 *  - the MIME comes from the DECODED HEADER only — the URL extension is NEVER authoritative;
 *  - MIME absent/unknown, or a deposit that never went through the probe (the painter's G2
 *    geometry deposit), carries `null` — « pas de MIME » ;
 *  - the pair is deposited in ONE cache write and never patched afterwards: once the first
 *    valid metadata fixed the entry there is NO late reclassification, in either direction
 *    (a painter success never adds nor strips a MIME after the fact).
 */
internal data class IntrinsicMediaMetadata(
    val size: IntSize,
    val mimeType: String?,
)

/**
 * §8 — the probe MIME that makes a BLOCK content media eligible for the display profile
 * (`eligibleGifBloc`). Static GIFs (GIF8 container) match too: animation is not discriminable
 * at the probe. Compared against [IntrinsicMediaMetadata.mimeType] ONLY — never a URL extension.
 */
internal const val GIF_MIME_TYPE = "image/gif"
