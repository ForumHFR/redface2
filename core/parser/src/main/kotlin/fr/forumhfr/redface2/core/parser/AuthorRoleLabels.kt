package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.AuthorRole

/**
 * Mapping **partagé** `libellé HFR → [AuthorRole]` (#1112, #221), utilisé par les DEUX sources du
 * rôle pour éviter toute divergence : l'annuaire staff (`StaffParser`, libellés entre parenthèses
 * des ancres `a.s1Topic`) et la page profil (`ProfileParser.parseAuthorRole`, champ « Statut »).
 *
 * Mapping **exact** (aucune heuristique), sur libellé déjà trimmé :
 * - `Membre` → [AuthorRole.MEMBER] ;
 * - `Modérateur` → [AuthorRole.MODERATOR] ;
 * - `Administrateur`, `Super Administrateur` → [AuthorRole.ADMIN] ;
 * - `Développeur`, `Architecte / Développeur principal` → [AuthorRole.DEVELOPER] ;
 * - tout autre libellé (ou vide) → `null` (rôle indéterminé — l'appelant ignore/dégrade).
 *
 * `internal` : détail de parsing propre à `:core:parser`, jamais exposé hors module.
 */
internal fun authorRoleFromLabel(label: String): AuthorRole? = when (label.trim()) {
    LABEL_MEMBER -> AuthorRole.MEMBER
    LABEL_MODERATOR -> AuthorRole.MODERATOR
    LABEL_ADMIN, LABEL_SUPER_ADMIN -> AuthorRole.ADMIN
    LABEL_DEVELOPER, LABEL_LEAD_DEVELOPER -> AuthorRole.DEVELOPER
    else -> null
}

private const val LABEL_MEMBER = "Membre"
private const val LABEL_MODERATOR = "Modérateur"
private const val LABEL_ADMIN = "Administrateur"
private const val LABEL_SUPER_ADMIN = "Super Administrateur"
private const val LABEL_DEVELOPER = "Développeur"
private const val LABEL_LEAD_DEVELOPER = "Architecte / Développeur principal"
