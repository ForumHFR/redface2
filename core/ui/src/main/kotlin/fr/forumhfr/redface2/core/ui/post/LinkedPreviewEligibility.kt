package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import java.net.URI

/**
 * #876 ([AMENDEMENT-v1.5-4], clause « Éligibilité — garde G3 ») — whether a BLOCK content media is
 * a LINKED PREVIEW, i.e. a thumbnail wrapped in a `[url=…]` pointing at a DISTINCT resource of the
 * SAME host, small enough that its physical size is the problem the amendment fixes. Eligible
 * previews take the [linkedPreviewUpscaleCeiling] factor as their §3 scale ceiling; everything else
 * keeps the strict no-upscale `1f`.
 *
 * The guards are evaluated in the contractual order, each one short-circuiting the next:
 *  1. [linkUrl] non-null — no wrapping link, no preview. A pure null check: a blank string is not
 *     an absolute URL, so the blank case belongs to guard 2, not here;
 *  2. [url] AND [linkUrl] are ABSOLUTE HTTP(S) URLs WITH A NON-EMPTY AUTHORITY — a REAL parse
 *     ([absoluteHttpUriOrNull]), never a prefix test: `"https://"` (unparsable), `http:foo`,
 *     `https:/path` and `https:///path` (no authority), a relative path or a non-HTTP(S) scheme
 *     all fail HERE. The URIs are parsed ONCE and reused by the host guard;
 *  3. **G1** — `linkUrl ≠ url` by EXACT string inequality of the RAW trimmed strings, with NO
 *     normalisation (never the parsed URIs). An auto-link differing only by the `http`/`https`
 *     scheme or by a trailing slash therefore PASSES G1: an assumed residual of the contract,
 *     bounded by G2 — it must not be "fixed" here;
 *  4. same host — see [isSameLinkedPreviewHost], on the URIs parsed at guard 2;
 *  5. **G2** — the largest axis of the EXIF-oriented native dimensions (§3 is the geometry
 *     authority; the caller passes the already-oriented pair) is at most
 *     [LINKED_PREVIEW_MAX_NATIVE_AXIS_PX]. Unknown dimensions FAIL the guard: fail-closed, so a
 *     cold media is never upscaled on speculation.
 *
 * No probe, no extension sniffing and no validation of the LINK target: eligibility comes from the
 * `img` media wrapped by the link, never from the URL alone. The classification order of §3 is what
 * keeps smileys (§3 bis) and cc-images (#256) off this path — no extra host rule is allowed here
 * (invariant I1), which is why the `[img]`-of-a-perso-smiley false positive is an accepted residual.
 */
internal fun isEligibleLinkedPreview(url: String, linkUrl: String?, nativePx: IntSize?): Boolean {
    if (linkUrl == null) return false
    val image = url.trim()
    val link = linkUrl.trim()
    val imageUri = absoluteHttpUriOrNull(image)
    val linkUri = absoluteHttpUriOrNull(link)
    return imageUri != null &&
        linkUri != null &&
        image != link &&
        isSameLinkedPreviewHost(imageUri, linkUri) &&
        fitsLinkedPreviewSizeGuard(nativePx)
}

/**
 * #876 — the contract's `mApercu = min(densité, 3,0)`: an eligible linked preview may spread one
 * source pixel over at most [density] screen pixels, capped at [LINKED_PREVIEW_MAX_UPSCALE].
 *
 * Deliberately NOT floored to `1f`: the `1,0` floor is guaranteed at the call site by
 * `mEffectif = max(mApercu, mGif)` (the GIF factor is `1f` when the media is not an eligible GIF),
 * and the multipliers NEVER multiply — they relax the same no-upscale ceiling and the largest wins.
 * The hard caps (`fImage × largeurConteneur`, `capBloc`) re-clamp the result downstream.
 */
internal fun linkedPreviewUpscaleCeiling(density: Float): Float =
    minOf(density, LINKED_PREVIEW_MAX_UPSCALE)

/**
 * Guard 2's validator: the trimmed string must PARSE as an ABSOLUTE URI of `http`/`https` scheme
 * carrying a NON-EMPTY `rawAuthority`. `URI.isAbsolute` only attests a scheme, so the authority
 * requirement is what rejects `http:foo` (opaque), `https:/path` (no authority) and
 * `https:///path` (empty authority, parsed as a `null` `rawAuthority`) HERE, per the contractual
 * guard order — not downstream at the host guard. Deliberately NOT `host != null`: the underscore
 * residual below must keep failing at the HOST guard, not here.
 *
 * `java.net.URI` is the retained parser, in strict mode — pure JVM (`android.net.Uri` needs
 * Robolectric, and `:core:ui` policies stay JVM-testable) and strict enough to reject un-encoded
 * URLs — notably a real URL carrying a space or a `|`, which therefore fails guard 2, before the
 * host guard the contract's « Même hôte » clause lists it under.
 *
 * Two residuals of that parser choice, pinned by [LinkedPreviewEligibilityTest]: a host carrying
 * an underscore parses with a non-empty authority but a `null` `host` field — it PASSES this
 * guard and is rejected downstream by the host guard — and a terminal dot is KEPT as-is
 * (`example.com. ≠ example.com`).
 */
internal fun absoluteHttpUriOrNull(url: String): URI? = runCatching { URI(url) }
    .getOrNull()
    ?.takeIf { it.isAbsolute && isHttpScheme(it.scheme) && !it.rawAuthority.isNullOrEmpty() }

private fun isHttpScheme(scheme: String?): Boolean =
    scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)

/**
 * « Même hôte » per the amendment: case-INSENSITIVE comparison of the PARSED `host` fields — the
 * URIs already parsed by guard 2, never a re-parse — with NO `www.` stripping, ports ignored, and
 * scheme/path/query/fragment without effect. No DNS resolution and no registrable-domain
 * comparison, so two different sub-domains are two different hosts (`i.imgur.com ≠ imgur.com` —
 * the Imgur thumbnail-to-page pattern is NOT eligible, assumed).
 *
 * A missing host — the underscore residual of [absoluteHttpUriOrNull] (authority present, `host`
 * unparsable) — is not eligible: a genuine thumbnail can silently lose the upscale, also assumed.
 * The authority-less forms (`https:///path`) no longer reach this guard: guard 2 rejects them.
 */
private fun isSameLinkedPreviewHost(imageUri: URI, linkUri: URI): Boolean {
    val host = imageUri.host
    return host != null && host.equals(linkUri.host, ignoreCase = true)
}

/**
 * **G2**, fail-closed: unknown dimensions (`null`, the cold cache) never pass, and neither does a
 * degenerate pair — a non-positive axis is not a measurement, and the §3 equation requires
 * positive native dimensions.
 */
private fun fitsLinkedPreviewSizeGuard(nativePx: IntSize?): Boolean =
    nativePx != null &&
        nativePx.width > 0 &&
        nativePx.height > 0 &&
        maxOf(nativePx.width, nativePx.height) <= LINKED_PREVIEW_MAX_NATIVE_AXIS_PX

/**
 * §8 — G2's threshold (px on the largest native axis, inclusive). Empirically placed in the observed
 * `]330, 640[` gap: every observed thumbnail is at most 330 px (`70×150` measured on a real S10e,
 * `150×112` for a diberie `/t/`, `250×250` in the bench, 330 px for a Wikimedia `/thumb/`), while
 * every observed non-thumbnail is at least 640 px (Imgur « l », diberie `/r/`, Zupimages `/up/`).
 * The `500–800 px` preview class linked to its full-size version therefore loses the upscale — an
 * assumed false negative: at density 3 those already occupy at least 213 dp.
 */
internal const val LINKED_PREVIEW_MAX_NATIVE_AXIS_PX = 400

/**
 * §8 — hard cap of `mApercu`. Beyond ×3 the upscale of a small source stops buying legibility and
 * only spreads blur, so a density above 3 does not push the ceiling further.
 */
internal const val LINKED_PREVIEW_MAX_UPSCALE = 3f
