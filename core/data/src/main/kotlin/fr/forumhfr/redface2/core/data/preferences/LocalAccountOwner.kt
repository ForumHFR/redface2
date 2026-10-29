package fr.forumhfr.redface2.core.data.preferences

/** Local per-account storage convention shared with super favorites. Deliberately does not trim. */
internal fun normalizeLocalAccountOwner(owner: String): String = owner.lowercase()
