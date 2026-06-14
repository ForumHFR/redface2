package fr.forumhfr.redface2.core.domain.mpstorage

/**
 * Applies the DT reading positions held in the MPStorage document (#6, ADR-014) to the local
 * per-account MP reading-position store (ADR-013 stage 1).
 *
 * DTCloud's `mpFlags.list[]` entries are the reading-resume positions of the user's group
 * conversations (`cat=prive`, "DT") — `{post = threadId, page, …}`. Seeding them locally lets a
 * fresh install / new device resume those conversations at the page the user last reached on the
 * desktop, without any server-side per-message bookmark (HFR has none, cf. ADR-013/#361).
 *
 * Local-priority (ADR-013): the store is only SEEDED — an entry never rewinds a conversation the
 * user already advanced past locally. Read-only on the MPStorage side: nothing is written back to
 * the storage MP (the write path stays deferred & opt-in, ADR-014 §4).
 */
interface MpStorageReadPositionSeeder {

    suspend fun seed(): MpStorageSeedOutcome
}

/** Result of a [MpStorageReadPositionSeeder.seed] run. */
sealed interface MpStorageSeedOutcome {

    /** No authenticated session — nothing to seed against. */
    data object NotAuthenticated : MpStorageSeedOutcome

    /** The account has no storage MP (the nominal case for a non-DTCloud user). */
    data object NoStorage : MpStorageSeedOutcome

    /** The storage MP exists but its document is not a readable v0.1 envelope. */
    data object Unreadable : MpStorageSeedOutcome

    /** [applied] positions were seeded out of [total] DT entries found in the document. */
    data class Seeded(val total: Int, val applied: Int) : MpStorageSeedOutcome
}
