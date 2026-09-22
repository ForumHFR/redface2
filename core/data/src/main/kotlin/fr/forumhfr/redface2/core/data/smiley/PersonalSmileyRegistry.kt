package fr.forumhfr.redface2.core.data.smiley

import javax.inject.Inject
import javax.inject.Singleton

/**
 * #873 — process-RAM registry for perso-smiley names observed in successful wiki responses.
 *
 * Entries are bounded with FIFO eviction. Updating an existing exact key changes its URL without
 * moving it in insertion order. Every operation uses the same lock because wiki responses arrive
 * on an IO dispatcher while editor previews are parsed from UI-driven flows. A request stamp keeps
 * an in-flight response from refilling the registry after logout or an account switch.
 */
@Singleton
internal class PersonalSmileyRegistry private constructor(
    private val maxEntries: Int,
) {
    @Inject
    constructor() : this(DEFAULT_MAX_ENTRIES)

    private val entries = LinkedHashMap<String, String>()
    private val lock = Any()
    private var generation: Long = 0

    fun capture(): Stamp = synchronized(lock) { Stamp(generation) }

    fun resolve(name: String): String? = synchronized(lock) { entries[name] }

    fun register(stamp: Stamp, name: String, imageUrl: String) {
        synchronized(lock) {
            if (stamp.generation == generation) {
                entries[name] = imageUrl
                while (entries.size > maxEntries) {
                    val oldest = entries.entries.iterator()
                    oldest.next()
                    oldest.remove()
                }
            }
        }
    }

    fun clearAndAdvanceGeneration() {
        synchronized(lock) {
            entries.clear()
            generation += 1
        }
    }

    internal val size: Int
        get() = synchronized(lock) { entries.size }

    data class Stamp internal constructor(
        val generation: Long,
    )

    internal companion object {
        const val DEFAULT_MAX_ENTRIES = 1024

        fun createForTest(maxEntries: Int): PersonalSmileyRegistry {
            require(maxEntries > 0) { "maxEntries must be positive" }
            return PersonalSmileyRegistry(maxEntries)
        }
    }
}
