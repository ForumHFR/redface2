package fr.forumhfr.redface2.core.data.write

/**
 * Strips code points HFR cannot store from the `content_form` body of a post before it is
 * POSTed (#114).
 *
 * ## Why
 *
 * HFR silently truncates a posted message at the FIRST character it cannot store and STILL
 * answers HTTP 200 « success » — so the user loses everything after that character with no
 * error. Verified live, the trigger in practice is any code point outside the Basic
 * Multilingual Plane (`>= U+10000`, encoded as a UTF-16 surrogate pair) — chiefly **emojis**
 * pasted into the editor. The app itself posts valid UTF-8 (`FormBody.Builder(Charsets.UTF_8)`),
 * so the bad byte never comes from our encoding; it comes from the content the user typed.
 *
 * ## What it does
 *
 * Removes every astral (non-BMP) code point AND every lone/unpaired UTF-16 surrogate, keeping
 * everything in the BMP intact: BBCode tags (`[b]…[/b]`), accented Latin (é, à, ç, œ…),
 * combining marks, CR/LF, tabs and all ordinary punctuation are preserved verbatim. Losing the
 * emoji is far better than losing the whole post.
 *
 * Deliberately scoped to non-BMP / surrogate removal only — no control-byte stripping — because
 * that is the single truncation vector observed live (do not broaden without fresh evidence).
 *
 * Pure and side-effect-free so it can be unit-tested in isolation (see
 * `ContentFormSanitizerTest`), mirroring the [redactHashCheckForDiagnostics] precedent. Applied
 * at every user-authored `content_form` write site (reply / quote / edit / new-topic /
 * edit-first-post / MP reply / MP compose). The delete-post path is excluded on purpose: it
 * re-posts `ReplyForm.initialContent`, i.e. content HFR already accepted, never freshly typed.
 */
internal fun sanitizeContentForm(content: String): String {
    // Fast path: a single `Char` (UTF-16 code unit) scan. Every astral pair AND every lone
    // surrogate has at least one code unit >= 0xD800, so the absence of any such unit proves the
    // string is pure BMP scalar values — nothing to strip — and the common ASCII/Latin/BBCode
    // post returns without allocating. High-BMP chars (U+E000..U+FFFF) are < 0xD800 so they take
    // this path too and are preserved.
    if (content.all { it.code < SURROGATE_RANGE_START }) return content
    return buildString(content.length) {
        var index = 0
        while (index < content.length) {
            val codePoint = content.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            // Keep only valid BMP scalar values. `codePointAt` returns the raw surrogate value
            // (0xD800..0xDFFF, charCount 1) for an UNPAIRED surrogate — those are < 0x10000 yet
            // must still be dropped, so test the surrogate range explicitly. A well-formed
            // astral pair decodes to a single codePoint >= 0x10000 with charCount 2.
            val isAstral = codePoint >= ASTRAL_PLANE_START
            val isLoneSurrogate = codePoint in SURROGATE_RANGE_START..SURROGATE_RANGE_END
            if (!isAstral && !isLoneSurrogate) {
                append(content, index, index + charCount)
            }
            index += charCount
        }
    }
}

/**
 * DETECTION twin of [sanitizeContentForm] (#114 logic, #C4) — reports whether [content] holds any
 * code point HFR would silently truncate at : an astral (non-BMP, `>= U+10000`) scalar value OR a
 * lone / unpaired UTF-16 surrogate. Returns `false` for any pure-BMP string (BBCode, accented Latin,
 * high-BMP chars `U+E000..U+FFFF`, CR/LF, tabs).
 *
 * This is the NON-DESTRUCTIVE companion : where [sanitizeContentForm] STRIPS the offending code
 * points from a freshly-typed user post (losing an emoji beats losing the whole post), this only
 * DETECTS them. The MPStorage write path (ADR-014) must NOT strip — the body is a SHARED third-party
 * document — so it uses this to FAIL CLOSED (refuse the POST) instead, leaving the document untouched.
 *
 * Pure and side-effect-free (unit-tested in `ContentFormSanitizerTest`). Shares the exact code-unit
 * scan / boundary semantics of [sanitizeContentForm] so the two never disagree on what HFR truncates.
 */
internal fun containsUnstorableContent(content: String): Boolean {
    // Fast path: every astral pair AND every lone surrogate has at least one code unit >= 0xD800, so
    // the absence of any such unit proves the string is pure BMP — nothing HFR truncates on. The
    // common ASCII/Latin/BBCode body returns false without a code-point walk. Otherwise scan the code
    // units: any unit in the surrogate range is either an astral pair's half or a lone surrogate, and
    // both are unstorable (same boundary semantics as sanitizeContentForm, which drops exactly these).
    return content.any { it.code in SURROGATE_RANGE_START..SURROGATE_RANGE_END }
}

/** First code point of the astral planes (UTF-16 surrogate-pair territory). */
private const val ASTRAL_PLANE_START = 0x10000

/** Inclusive bounds of the UTF-16 surrogate range — never valid as standalone scalar values. */
private const val SURROGATE_RANGE_START = 0xD800
private const val SURROGATE_RANGE_END = 0xDFFF
