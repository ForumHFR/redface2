package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.model.write.QuoteSelection

/**
 * Plain request payload assisted-injected into [PostEditorViewModel]. Mirrors the
 * `PostEditorRoute` NavKey from `:app/navigation` without leaking Navigation 3
 * types into the feature module.
 *
 * Phase 2C-A (#145) extends the payload with [page] and [subcat] : both are
 * required by HFR's public `message.php` reply form contract (cf.
 * `docs/specs/protocol-hfr.md` § POST `bddpost.php`). The caller (TopicScreen)
 * is responsible for supplying real values; passing `null` here implies "Phase
 * 2C wiring not yet ready for this entry point" and the editor stays in a
 * read-only / local-preview-only mode for that session. Private edits instead carry a
 * [PostEditorTarget.PrivateMessage] and leave public category/topic/subcategory coordinates null.
 */
data class PostEditorRequest(
    val mode: PostEditorMode,
    val cat: Int?,
    val topicId: Int?,
    val numreponse: Int?,
    val page: Int?,
    val subcat: Int?,
    /**
     * #604 lot 3 (mockup P3) — the quote CARDS this editor opens with, in citation order :
     * « Citer N » selections or a quick-reply escalation's armed cards. Consumed once into
     * [PostEditorViewModel]'s `quotes` state ; the `[quotemsg]` blocks are materialised at
     * SUBMIT (never prefetched into the field — citations are cards, the field is the user's
     * text). Handed over in memory by `:app` (never serialised into the route) : the cards are
     * deliberately transient, a process death keeps the text (#405 row) but drops the cards.
     */
    val initialQuotes: List<QuoteSelection> = emptyList(),
    /**
     * #790 (#604 lot 2) — `true` ONLY when this editor is the ESCALATION of a quick-reply sheet. The
     * ViewModel then auto-applies the shared #405 draft row instead of surfacing the restore
     * banner (appending to anything already typed — the escalation continues the same
     * composition act). The sheet requires a decision on its restore offer before escalation.
     *
     * #843 — this flag is escalation-ONLY again. The #829/#833 COLD full-editor opens (FAB under the
     * FULL_EDITOR preset, « Citer », long-press, « Citer N » 3+) had wrongly set it too, silently
     * re-applying an old draft with no « Ignorer »; they now pass `false` so the restore banner is
     * surfaced. Only `RedfaceNavigation.onEscalateToFullEditor` sets it `true`.
     */
    val resumeSharedDraft: Boolean = false,
    val target: PostEditorTarget = PostEditorTarget.Public,
) {
    init {
        when (target) {
            PostEditorTarget.Public -> require(cat != null)
            is PostEditorTarget.PrivateMessage -> {
                require(mode == PostEditorMode.Edit && cat == null && topicId == null && subcat == null)
                require(initialQuotes.isEmpty() && !resumeSharedDraft)
            }
        }
    }

    override fun toString(): String = "PostEditorRequest(mode=$mode, target=$target)"
}
