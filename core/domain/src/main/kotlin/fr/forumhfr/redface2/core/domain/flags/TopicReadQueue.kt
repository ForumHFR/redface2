package fr.forumhfr.redface2.core.domain.flags

data class TopicRead(val cat: Int, val topicId: Int)

/** Process-local handoff from authenticated topic loads to the next Flags landing. */
class TopicReadQueue {
    private val pending = linkedMapOf<Pair<String, TopicRead>, Long>()
    private var sequence = 0L

    @Synchronized
    fun record(pseudo: String, read: TopicRead) {
        val key = pseudo.lowercase() to read
        pending.remove(key)
        pending[key] = ++sequence
        if (pending.size > MAX_PENDING_READS) pending.remove(pending.keys.first())
    }

    @Synchronized
    fun checkpoint(): Long = sequence

    @Synchronized
    fun hasPending(pseudo: String, through: Long): Boolean {
        val account = pseudo.lowercase()
        return pending.any { (key, version) -> key.first == account && version <= through }
    }

    @Synchronized
    fun drain(pseudo: String, through: Long): Set<TopicRead> {
        val account = pseudo.lowercase()
        val reads = linkedSetOf<TopicRead>()
        pending.entries.removeAll { (key, version) ->
            (version <= through).also { consumed ->
                if (consumed && key.first == account) reads.add(key.second)
            }
        }
        return reads
    }

    @Synchronized
    fun clear() = pending.clear()
}

private const val MAX_PENDING_READS = 64
