package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.unit.sp
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostRendererInlineTest {

    private val emptyLinkStyles = TextLinkStyles()

    @Test
    fun `LineBreak inside Strong preserves the newline character in the AnnotatedString`() {
        val inlines = listOf(
            PostInline.Strong(
                children = listOf(
                    PostInline.Text("hello"),
                    PostInline.LineBreak,
                    PostInline.Text("world"),
                ),
            ),
        )

        val annotated = buildInlineText(inlines, emptyLinkStyles, imageAlt = "img")

        assertEquals("hello\nworld", annotated.text)
    }

    @Test
    fun `appendInline IDs match collectInlineMedia keys for smileys with image URLs`() {
        // Builtin smiley without imageUrl emits the textual token in the AnnotatedString and
        // contributes NO entry to the inline-media map. Two smileys with imageUrl emit
        // appendInlineContent IDs (post-smiley-0, post-smiley-1) and ARE present in the map.
        // The two parallel walks must agree on which inline gets a placeholder ID.
        val inlines = listOf(
            PostInline.Smiley(kind = SmileyKind.Builtin(":jap:"), imageUrl = null),
            PostInline.Smiley(
                kind = SmileyKind.Builtin(":o"),
                imageUrl = "https://forum.hardware.fr/images/perso/o.gif",
            ),
            PostInline.Smiley(
                kind = SmileyKind.Perso("ouch"),
                imageUrl = "https://forum.hardware.fr/images/perso/ouch.gif",
            ),
        )

        val annotated = buildInlineText(inlines, emptyLinkStyles, imageAlt = "img")
        val media = collectInlineMedia(inlines)

        // The textual token of the first smiley (no imageUrl) lands in the plain-text content,
        // alongside two placeholders for the IDs the second and third smileys emit.
        assertTrue(annotated.text.contains(":jap:"))
        assertEquals(setOf("post-smiley-0", "post-smiley-1"), media.keys)
        // appendInlineContent IDs and map keys MUST match — that's the invariant the comment on
        // MediaCounter spells out.
        assertEquals(media.keys, annotated.inlineContentIds())
    }

    @Test
    fun `MediaCounter recursion is symmetric across every PostInline container`() {
        // The MediaCounter KDoc warns that appendInline and walkInlinesForMedia must advance
        // the counter under the EXACT same conditions, including when descending into the six
        // PostInline containers (Strong, Emphasis, Underline, Strike, Color, Link). If any
        // recursion branch in walkInlinesForMedia (PostRenderer.kt, `is PostInline.<C> ->
        // walkInlinesForMedia(inline.children, ...)`) is deleted, the AnnotatedString would
        // still emit a post-smiley-N placeholder but the inline-content map would not register
        // it — leading to silent divergence at Compose runtime. This test exercises every
        // container type in turn so a deletion of any branch fails loudly with a JVM unit test.
        fun smileyChild(): List<PostInline> = listOf(
            PostInline.Smiley(
                kind = SmileyKind.Builtin(":o"),
                imageUrl = "https://forum.hardware.fr/images/perso/o.gif",
            ),
        )
        val containers: List<Pair<String, PostInline>> = listOf(
            "Strong" to PostInline.Strong(smileyChild()),
            "Emphasis" to PostInline.Emphasis(smileyChild()),
            "Underline" to PostInline.Underline(smileyChild()),
            "Strike" to PostInline.Strike(smileyChild()),
            "Color" to PostInline.Color(colorHex = "#FF0000", children = smileyChild()),
            "Link" to PostInline.Link(url = "https://example.com", children = smileyChild()),
        )

        containers.forEach { (name, container) ->
            val inlines = listOf(container)
            val annotated = buildInlineText(inlines, emptyLinkStyles, imageAlt = "img")
            val media = collectInlineMedia(inlines)

            assertEquals(
                "$name: collectInlineMedia must register the nested smiley as map key",
                setOf("post-smiley-0"),
                media.keys,
            )
            assertEquals(
                "$name: AnnotatedString placeholder IDs must match the inline-content map keys",
                media.keys,
                annotated.inlineContentIds(),
            )
        }
    }

    @Test
    fun `collectInlineMedia applies the provided smiley box resolver to the placeholder (175 seam)`() {
        // #175 — the production caller passes a cache-backed resolver returning the measured size;
        // here we pin that whatever box the resolver yields lands on the InlineTextContent placeholder
        // (the seam through which intrinsic sizing flows). Default-resolver/bucket behaviour stays
        // covered by the bucket tests below.
        val inlines = listOf(
            PostInline.Smiley(
                kind = SmileyKind.Perso("measured"),
                imageUrl = "https://forum-images.hardware.fr/images/perso/measured.gif",
            ),
        )
        val media = collectInlineMedia(inlines) { InlineMediaBox(33.sp, 21.sp) }
        val placeholder = media["post-smiley-0"]?.placeholder
        assertNotNull("resolved smiley should yield an InlineTextContent", placeholder)
        assertEquals(33.sp, placeholder!!.width)
        assertEquals(21.sp, placeholder.height)
    }

    @Test
    fun `inline image emits a post-image placeholder and a matching map entry`() {
        val inlines = listOf(
            PostInline.InlineImage(
                url = "https://forum.hardware.fr/images/foo.png",
                description = "foo",
            ),
        )

        val annotated = buildInlineText(inlines, emptyLinkStyles, imageAlt = "img")
        val media = collectInlineMedia(inlines)

        assertEquals(setOf("post-image-0"), media.keys)
        assertEquals(setOf("post-image-0"), annotated.inlineContentIds())
    }

    @Test
    fun `smiley without imageUrl renders its token as text and produces no map entry`() {
        val inlines = listOf(
            PostInline.Smiley(kind = SmileyKind.Builtin(":jap:"), imageUrl = null),
            PostInline.Smiley(kind = SmileyKind.Perso("custom"), imageUrl = null),
        )

        val annotated = buildInlineText(inlines, emptyLinkStyles, imageAlt = "img")
        val media = collectInlineMedia(inlines)

        assertEquals(":jap:[:custom]", annotated.text)
        assertTrue("no inline content expected", media.isEmpty())
        assertFalse(annotated.inlineContentIds().any { it.startsWith("post-") })
    }

    @Test
    fun `builtin smiley with imageUrl uses the small bucket placeholder baseline-aligned`() {
        // Builtin smileys are typically 16x16 to 18x18 in HFR's icon set. The small bucket fits
        // them inline with body-medium text without a noticeable height bump on the line.
        val inlines = listOf(
            PostInline.Smiley(
                kind = SmileyKind.Builtin(":jap:"),
                imageUrl = "https://forum-images.hardware.fr/icones/smilies/jap.gif",
            ),
        )

        val media = collectInlineMedia(inlines)
        val placeholder = media["post-smiley-0"]?.placeholder

        assertNotNull("builtin smiley with imageUrl should yield an InlineTextContent", placeholder)
        assertEquals(
            "builtin bucket width should match the policy",
            PostMediaDisplayPolicy.builtinSmiley.placeholderWidth,
            placeholder!!.width,
        )
        assertEquals(
            "builtin bucket height should match the policy",
            PostMediaDisplayPolicy.builtinSmiley.placeholderHeight,
            placeholder.height,
        )
        assertEquals(
            // #175 — AboveBaseline: the sprite bottom sits on the text baseline (web/RF1 parity, #203).
            // Zero overlap for a tall perso comes from the unspecified lineHeight on media paragraphs
            // (the line grows upward to contain it), NOT from the alignment — see smileyInlineContent.
            "smileys must be baseline-aligned (AboveBaseline) for web parity",
            PlaceholderVerticalAlign.AboveBaseline,
            placeholder.placeholderVerticalAlign,
        )
    }

    @Test
    fun `perso smiley with imageUrl uses the 70x50sp perso bucket not the builtin one`() {
        // Exhaustive wikismilies stats show HFR perso mostly target a 50px-high line, with 70×50
        // as the dominant size. The bucket follows that real corpus shape while keeping the
        // placeholder height below the previous 64sp line rhythm that broke post #74625731.
        val inlines = listOf(
            PostInline.Smiley(
                kind = SmileyKind.Perso("cosmoschtroumpf"),
                imageUrl = "https://forum-images.hardware.fr/images/perso/cosmoschtroumpf.gif",
            ),
        )

        val media = collectInlineMedia(inlines)
        val placeholder = media["post-smiley-0"]?.placeholder

        assertNotNull("perso smiley with imageUrl should yield an InlineTextContent", placeholder)
        assertEquals(
            "perso bucket should match the policy (70×50sp), not the builtin 18sp bucket",
            PostMediaDisplayPolicy.persoSmiley.placeholderWidth,
            placeholder!!.width,
        )
        assertEquals(
            PostMediaDisplayPolicy.persoSmiley.placeholderHeight,
            placeholder.height,
        )
        // Defensive: explicitly assert it's NOT the builtin bucket — guards against a future
        // refactor that accidentally collapses both code paths to one bucket.
        assertNotEquals(
            "perso must not collapse to the builtin bucket",
            PostMediaDisplayPolicy.builtinSmiley.placeholderWidth,
            placeholder.width,
        )
        assertEquals(
            // #175 — same rule as builtin: AboveBaseline (web parity); zero overlap via line growth.
            "perso smileys must be baseline-aligned (AboveBaseline) for web parity",
            PlaceholderVerticalAlign.AboveBaseline,
            placeholder.placeholderVerticalAlign,
        )
    }

    @Test
    fun `non-trivial AST keeps inlineContentIds and media keys in lockstep`() {
        // Issue #139 (suite #83): the existing tests cover the symmetry one container at a time
        // (`MediaCounter recursion is symmetric across every PostInline container`) plus a
        // 3-smiley flat case. They do not exercise a deep mix of containers + smileys (with and
        // without `imageUrl`) + inline images all in one AST, which is how a real HFR post looks.
        // A drift between `appendInline` and `walkInlinesForMedia` (e.g. a future container that
        // recurses in one walk and not the other) would slip past the per-container tests but
        // not this composite one — the set equality is the contract MediaCounter's KDoc spells
        // out: "do not break that symmetry".
        val inlines = listOf(
            PostInline.Strong(
                children = listOf(
                    PostInline.Text("intro "),
                    PostInline.Underline(
                        children = listOf(
                            PostInline.Smiley(
                                kind = SmileyKind.Builtin(":jap:"),
                                imageUrl = "https://forum-images.hardware.fr/icones/smilies/jap.gif",
                            ),
                        ),
                    ),
                    // Inline image directly under `Strong` covers the BBCode `[b][img]…[/img][/b]`
                    // shape — without this case, deletion of the `is PostInline.Strong` branch in
                    // `walkInlinesForMedia` (or its `appendInline` counterpart) would still leave
                    // every per-container test green.
                    PostInline.InlineImage(
                        url = "https://forum.hardware.fr/images/inside-strong.png",
                        description = "inside-strong",
                    ),
                    // Smiley without imageUrl: must NOT advance the smiley counter and must NOT
                    // appear in the media map. Asymmetry trap if either walk forgets the gate.
                    // Reuse a real HFR builtin (`:o`) — AGENTS.md prohibits inventing smiley codes
                    // even in tests; the assertion only cares about the `imageUrl == null` branch.
                    PostInline.Smiley(kind = SmileyKind.Builtin(":o"), imageUrl = null),
                ),
            ),
            PostInline.Color(
                colorHex = "#FF0000",
                children = listOf(
                    PostInline.InlineImage(
                        url = "https://forum.hardware.fr/images/foo.png",
                        description = "foo",
                    ),
                    PostInline.Smiley(
                        kind = SmileyKind.Perso("cosmoschtroumpf"),
                        imageUrl = "https://forum-images.hardware.fr/images/perso/cosmoschtroumpf.gif",
                    ),
                ),
            ),
            PostInline.LineBreak,
            PostInline.Link(
                url = "https://example.com",
                children = listOf(
                    PostInline.Emphasis(
                        children = listOf(
                            PostInline.Strike(
                                children = listOf(
                                    PostInline.Smiley(
                                        kind = SmileyKind.Perso("rofl"),
                                        imageUrl = "https://forum-images.hardware.fr/images/perso/rofl.gif",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            PostInline.InlineImage(
                url = "https://forum.hardware.fr/images/bar.png",
                description = "bar",
            ),
        )

        val annotated = buildInlineText(inlines, emptyLinkStyles, imageAlt = "img")
        val media = collectInlineMedia(inlines)

        // Set equality: every appendInlineContent placeholder must have an InlineTextContent and
        // vice versa. A drift here is the orphan-placeholder bug the MediaCounter KDoc warns
        // against ("do not break that symmetry"). `media.keys` is the contract value (what
        // `collectInlineMedia` produces), `annotated.inlineContentIds()` is the observed value
        // (what `buildInlineText` emitted) — keep that ordering for failure-message clarity, in
        // line with the sibling assertions earlier in this file.
        assertEquals(media.keys, annotated.inlineContentIds())
        // Belt-and-braces: the count must also reflect what we constructed (3 smileys with
        // imageUrl + 3 inline images = 6 placeholders, the no-imageUrl smiley contributes 0).
        assertEquals("3 smileys + 3 inline images expected", 6, media.size)
    }

    @Test
    fun `inline content IDs are zero-indexed and contiguous per media kind`() {
        // The MediaCounter is created fresh per Text per its KDoc, so every paragraph restarts
        // at 0. Pin both sides of the symmetry — the AnnotatedString placeholder IDs AND the
        // map keys — so a future change that, say, switched to a shared mutable counter or a
        // UUID-based ID would fail loudly here. Pinning only `media.keys` would miss the case
        // where `appendInline` drifts to a different scheme while `walkInlinesForMedia` keeps
        // the counter, which is precisely the asymmetry MediaCounter's KDoc warns against.
        val inlines = listOf(
            PostInline.Smiley(
                kind = SmileyKind.Builtin(":jap:"),
                imageUrl = "https://forum-images.hardware.fr/icones/smilies/jap.gif",
            ),
            PostInline.InlineImage(url = "https://forum.hardware.fr/images/a.png", description = "a"),
            PostInline.Smiley(
                kind = SmileyKind.Perso("rofl"),
                imageUrl = "https://forum-images.hardware.fr/images/perso/rofl.gif",
            ),
            PostInline.InlineImage(url = "https://forum.hardware.fr/images/b.png", description = "b"),
            PostInline.Smiley(
                kind = SmileyKind.Builtin(":o"),
                imageUrl = "https://forum-images.hardware.fr/icones/smilies/o.gif",
            ),
        )

        val annotated = buildInlineText(inlines, emptyLinkStyles, imageAlt = "img")
        val media = collectInlineMedia(inlines)

        val expectedSmileyIds = setOf("post-smiley-0", "post-smiley-1", "post-smiley-2")
        val expectedImageIds = setOf("post-image-0", "post-image-1")

        // Smiley counter on the map side: 3 distinct entries, zero-indexed, no gap.
        assertEquals(
            expectedSmileyIds,
            media.keys.filter { it.startsWith("post-smiley-") }.toSet(),
        )
        // Image counter on the map side: 2 distinct entries, zero-indexed, no gap. The two
        // counters are independent — image-0 does not skip ahead because of smiley-0/1/2.
        assertEquals(
            expectedImageIds,
            media.keys.filter { it.startsWith("post-image-") }.toSet(),
        )
        // And on the AnnotatedString side: same expected sets, otherwise `appendInline` and
        // `walkInlinesForMedia` are out of sync on the ID scheme even when the count matches.
        val annotatedIds = annotated.inlineContentIds()
        assertEquals(expectedSmileyIds, annotatedIds.filter { it.startsWith("post-smiley-") }.toSet())
        assertEquals(expectedImageIds, annotatedIds.filter { it.startsWith("post-image-") }.toSet())
    }

    @Test
    fun `inline image uses the bounded 240x180 placeholder centred`() {
        // Pre-#109 the inline image placeholder was 240×180 but the inner Modifier was
        // fillMaxWidth() — meaningless inside InlineTextContent (the placeholder dictates the
        // parent constraint), and the image stretched in unpredictable ways. The placeholder
        // now pins the dimensions; the inner Modifier.fillMaxSize() makes the AsyncImage track
        // them under any fontScale, while the inline image policy keeps small arbitrary images
        // from being blown up to the full 240×180.
        val inlines = listOf(
            PostInline.InlineImage(
                url = "https://forum.hardware.fr/images/foo.png",
                description = "foo",
            ),
        )

        val media = collectInlineMedia(inlines)
        val placeholder = media["post-image-0"]?.placeholder

        assertNotNull("inline image should yield an InlineTextContent", placeholder)
        assertEquals(
            PostMediaDisplayPolicy.inlineImage.placeholderWidth,
            placeholder!!.width,
        )
        assertEquals(
            PostMediaDisplayPolicy.inlineImage.placeholderHeight,
            placeholder.height,
        )
        assertEquals(
            PlaceholderVerticalAlign.Center,
            placeholder.placeholderVerticalAlign,
        )
    }
}

/**
 * Pulls every appendInlineContent ID out of an AnnotatedString. Compose marks the position of
 * each placeholder with the special object-replacement char (U+FFFC) and stores the ID as a
 * string annotation under the well-known tag `androidx.compose.foundation.text.inlineContent`.
 */
private fun androidx.compose.ui.text.AnnotatedString.inlineContentIds(): Set<String> =
    getStringAnnotations(
        tag = "androidx.compose.foundation.text.inlineContent",
        start = 0,
        end = length,
    ).map { it.item }.toSet()
