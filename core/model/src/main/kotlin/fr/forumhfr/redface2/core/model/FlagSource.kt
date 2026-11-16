package fr.forumhfr.redface2.core.model

/** Persisted names: changing an entry requires a database migration. */
enum class FlagSource { BUCKET, STICKY, LEGACY_UNKNOWN }

/**
 * Closed failure vocabulary; server bodies and exception messages never enter the cache.
 * Room persists DEADLINE as UNKNOWN for v23 rollback compatibility; unknown names read as UNKNOWN.
 */
enum class FlagFailureKind { NETWORK, SERVER, SESSION, SCHEMA, PAGE_CAP, UNKNOWN, DEADLINE }
