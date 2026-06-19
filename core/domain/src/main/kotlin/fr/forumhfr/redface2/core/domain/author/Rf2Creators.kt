package fr.forumhfr.redface2.core.domain.author

import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo

/**
 * #221 — static, app-embedded list of the Redface 2 creator pseudo(s). Their pseudo is rendered in a
 * shiny gold sheen everywhere it appears (assumed easter egg, cf. issue #221 « doré étincelant »).
 *
 * Stored canonicalised ([canonicalizePseudo]) so the match is robust to the exact HFR rendering of the
 * pseudo (casing, zero-width / format characters, NBSP, NFC form) — the same normalisation the blacklist
 * relies on. Note [canonicalizePseudo] keeps accents (NFC, not stripped), irrelevant here since the only
 * creator pseudo carries none. This is the simplest of the three highlight categories in #221 (a
 * hard-coded set); the admin / section-moderator categories need an HFR role source and land later.
 */
private val RF2_CREATOR_CANONICALS: Set<String> =
    setOf("XaTriX").mapTo(mutableSetOf(), ::canonicalizePseudo)

/** `true` when [pseudo] designates a Redface 2 creator (case / format-char / NBSP insensitive). */
fun isRf2Creator(pseudo: String): Boolean = canonicalizePseudo(pseudo) in RF2_CREATOR_CANONICALS
