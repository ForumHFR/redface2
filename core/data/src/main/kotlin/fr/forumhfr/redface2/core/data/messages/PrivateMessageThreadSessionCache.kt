package fr.forumhfr.redface2.core.data.messages

import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-memory-only LRU for private conversation pages.
 *
 * The request stamp closes the logout/account-switch race: every access must still belong to the
 * generation captured before the network request. [clearAndAdvanceGeneration] is synchronous so
 * [fr.forumhfr.redface2.core.data.cache.CacheInvalidator] can invalidate in-flight work before its
 * first suspending persistence purge.
 */
@Singleton
internal class PrivateMessageThreadSessionCache @Inject constructor() {
    private val pages = object : LinkedHashMap<Key, PrivateMessageThread>(
        MAX_PAGES + 1,
        LOAD_FACTOR,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Key, PrivateMessageThread>?,
        ): Boolean = size > MAX_PAGES
    }
    private var generation: Long = 0

    @Synchronized
    fun capture(account: String): Stamp = Stamp(
        account = canonicalizePseudo(account),
        generation = generation,
    )

    @Synchronized
    fun read(stamp: Stamp, threadId: Int, page: Int): PrivateMessageThread? =
        if (isStampCurrent(stamp)) pages[Key(stamp.account, threadId, page)] else null

    @Synchronized
    fun write(stamp: Stamp, threadId: Int, page: Int, thread: PrivateMessageThread) {
        if (isStampCurrent(stamp)) pages[Key(stamp.account, threadId, page)] = thread
    }

    @Synchronized
    fun isCurrent(stamp: Stamp): Boolean = isStampCurrent(stamp)

    @Synchronized
    fun clearAndAdvanceGeneration() {
        pages.clear()
        generation += 1
    }

    private fun isStampCurrent(stamp: Stamp): Boolean =
        stamp.generation == generation

    data class Stamp internal constructor(
        val account: String,
        val generation: Long,
    )

    private data class Key(
        val account: String,
        val threadId: Int,
        val page: Int,
    )

    private companion object {
        const val MAX_PAGES = 5
        const val LOAD_FACTOR = 0.75f
    }
}
