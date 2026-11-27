package fr.forumhfr.redface2.core.domain.flags

import fr.forumhfr.redface2.core.model.FlagType
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Identifies refreshes sharing a sticky sweep, including an in-flight type joined by a Super pull. */
class FlagPullBatch(val types: Set<FlagType> = emptySet()) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FlagPullBatch>
}
