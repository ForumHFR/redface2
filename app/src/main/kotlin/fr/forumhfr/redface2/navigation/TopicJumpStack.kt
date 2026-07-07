package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.feature.topic.TopicScrollAnchor

/**
 * #782 — one quote-jump departure: the page the reader jumped FROM and their exact read position at
 * tap time. The anchor is captured by the topic screen when the quote header is tapped (never at
 * disposal — an intra-page jump would have already scrolled away by then, cf. `onGoToPost`).
 */
internal data class TopicJumpEntry(val page: Int, val anchor: TopicScrollAnchor)

/**
 * #782 — the quote-jump return stack of ONE topic. Same single-owner stance as [MultiQuoteBasket]:
 * at most one stack lives at a time, keyed by `(cat, post)` so another topic's jumps can never arm
 * the in-topic back interception ([matches]). Deliberately TRANSIENT session state hoisted in
 * `RedfaceApp` (a plain `remember`), never serialized into a route: process death drops it and the
 * back button just exits the topic as before — replaying a stale return chain after a restore would
 * be worse (same rationale as `pendingBottomLanding`, Codex review on PR #420).
 */
internal data class TopicJumpStack(
    val cat: Int,
    val post: Int,
    val entries: List<TopicJumpEntry>,
) {
    fun matches(cat: Int, post: Int): Boolean = this.cat == cat && this.post == post
}

/**
 * #782 — depth cap of the return stack. Deep enough for any realistic quote-chase (each entry is
 * one « aller au message cité » tap without an intervening manual navigation), small enough that a
 * pathological chain cannot make the system back feel stuck in the topic: past the cap the OLDEST
 * departure is dropped, so back always unwinds at most this many jumps before leaving the topic.
 */
internal const val TOPIC_JUMP_STACK_MAX = 8

/**
 * #782 — push a departure onto the topic's return stack. A jump in a DIFFERENT topic than the
 * current stack's resets it (one stack at a time, cf. [TopicJumpStack]); overflow past
 * [TOPIC_JUMP_STACK_MAX] drops the oldest entry. Pure so `TopicJumpStackTest` pins the contract.
 */
internal fun TopicJumpStack?.pushedJump(cat: Int, post: Int, entry: TopicJumpEntry): TopicJumpStack {
    val kept = this?.takeIf { it.matches(cat, post) }?.entries.orEmpty()
    return TopicJumpStack(cat = cat, post = post, entries = (kept + entry).takeLast(TOPIC_JUMP_STACK_MAX))
}

/**
 * #782 — drop the most recent departure (the one the back interception is returning to). An emptied
 * stack collapses to `null` so the `BackHandler` disables itself and the NEXT back leaves the topic
 * through the normal pop, exactly like before the feature.
 */
internal fun TopicJumpStack.popped(): TopicJumpStack? =
    entries.dropLast(1).takeIf { it.isNotEmpty() }?.let { copy(entries = it) }

/**
 * #782 — the one return landing currently owed its anchor: armed by the back interception right
 * before it replaces the `TopicRoute`, matched by the next landing against its exact
 * `(cat, post, page)` [key], consumed (cleared) once that landing has resolved its scroll
 * restoration. Transient nav state like [TopicJumpStack] — never a route field.
 */
internal data class TopicJumpReturn(val key: TopicScrollKey, val anchor: TopicScrollAnchor)
