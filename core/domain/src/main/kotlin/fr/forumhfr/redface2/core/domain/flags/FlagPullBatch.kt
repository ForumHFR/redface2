package fr.forumhfr.redface2.core.domain.flags

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Identifies the serialized per-type refreshes issued by one pull gesture on the Super tab. */
class FlagPullBatch : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FlagPullBatch>
}
