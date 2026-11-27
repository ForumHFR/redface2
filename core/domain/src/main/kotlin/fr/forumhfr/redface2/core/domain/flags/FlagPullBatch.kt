package fr.forumhfr.redface2.core.domain.flags

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Identifies refreshes sharing a sticky sweep, including an in-flight type joined by a Super pull. */
class FlagPullBatch : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FlagPullBatch>
}
