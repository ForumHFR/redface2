package fr.forumhfr.redface2.core.model

/**
 * Persisted names: changing an entry requires a database migration.
 * GLOBAL is an in-memory observation; Room writes LEGACY_UNKNOWN for v23 build rollback.
 */
enum class FlagSource { BUCKET, STICKY, LEGACY_UNKNOWN, GLOBAL }

/**
 * Closed failure vocabulary; server bodies and exception messages never enter the cache.
 * Room persists DEADLINE as UNKNOWN for v23 rollback compatibility; unknown names read as UNKNOWN.
 */
enum class FlagFailureKind { NETWORK, SERVER, SESSION, SCHEMA, PAGE_CAP, UNKNOWN, DEADLINE }
