package fr.forumhfr.redface2.core.domain.flags

import fr.forumhfr.redface2.core.model.FlagType

data class TopicRead(val cat: Int, val topicId: Int)

/** Process-local handoff from authenticated topic loads to Flags, acknowledged by each source. */
class TopicReadQueue {
    private data class Entry(val version: Long, val types: Set<FlagType>)

    private val pending = linkedMapOf<Pair<String, TopicRead>, Entry>()
    private var sequence = 0L

    @Synchronized
    fun record(pseudo: String, read: TopicRead) {
        val key = pseudo.lowercase() to read
        pending.remove(key)
        pending[key] = Entry(++sequence, FlagType.entries.toSet())
        if (pending.size > MAX_PENDING_READS) pending.remove(pending.keys.first())
    }

    @Synchronized
    fun checkpoint(): Long = sequence

    @Synchronized
    fun pending(pseudo: String, through: Long): Map<TopicRead, Set<FlagType>> {
        val account = pseudo.lowercase()
        return pending.entries.mapNotNull { (key, entry) ->
            if (key.first == account && entry.version <= through) key.second to entry.types else null
        }.toMap()
    }

    @Synchronized
    fun acknowledge(pseudo: String, through: Long, covered: Map<TopicRead, Set<FlagType>>) {
        val account = pseudo.lowercase()
        covered.forEach { (read, types) ->
            val key = account to read
            val entry = pending[key] ?: return@forEach
            if (entry.version > through) return@forEach
            val remaining = entry.types - types
            if (remaining.isEmpty()) pending.remove(key) else pending[key] = entry.copy(types = remaining)
        }
    }
}

private const val MAX_PENDING_READS = 64
