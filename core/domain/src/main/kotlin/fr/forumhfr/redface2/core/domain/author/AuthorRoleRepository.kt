package fr.forumhfr.redface2.core.domain.author

import fr.forumhfr.redface2.core.model.AuthorRole

/**
 * Rôle HFR des auteurs (#1112, #221 — PR A/socle) — interface domaine, **hybride** à deux sources
 * (cf. `docs/specs/protocol-hfr.md`), toutes deux anonymes et best-effort :
 *
 * - **[getStaff]** — source **primaire** du badge : l'annuaire staff GLOBAL, un seul GET, indexé
 *   par **pseudo** (l'annuaire n'expose aucun `profileId`). C'est ce qui alimente les badges d'une
 *   liste de posts (1 GET + lookups locaux par pseudo, pas de N+1).
 * - **[getRole]** — source **secondaire** : la page profil d'**un** auteur, indexée par
 *   `Post.profileId`. Réservée à une demande explicite mono-utilisateur (écran profil, PR C) —
 *   **jamais** un fallback « requêter tous les profileId » si l'annuaire échoue.
 *
 * Donnée **décorative / publique / best-effort** : jamais un signal de sécurité. L'UI dégrade
 * silencieusement (pas de badge) quand le rôle est indéterminé.
 */
interface AuthorRoleRepository {

    /**
     * Annuaire staff global : `pseudo canonicalisé -> rôle` (clés via `canonicalizePseudo`).
     *
     * Un seul GET (mis en cache 24 h), puis lookups locaux. Contrat de robustesse :
     * - échec réseau → renvoyer le **cache périmé** s'il existe (sans avancer son timestamp),
     *   sinon `emptyMap()` — **jamais** de fallback sur N profils ;
     * - un annuaire absent des retours (pseudo non staff) signifie simplement « pas de badge » ;
     * - `CancellationException` et erreurs inattendues remontent.
     */
    suspend fun getStaff(): Map<String, AuthorRole>

    /**
     * Rôle d'**un** auteur via sa page profil, indexé par [profileId]. `null` = rôle indéterminé
     * (statut non reconnu **ou** échec réseau individuel — indiscernables par conception).
     * `CancellationException` et erreurs inattendues remontent.
     */
    suspend fun getRole(profileId: Int): AuthorRole?
}
