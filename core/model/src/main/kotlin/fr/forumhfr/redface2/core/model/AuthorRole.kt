package fr.forumhfr.redface2.core.model

/**
 * Rôle HFR d'un auteur sur le forum (#1112, #221 — PR A/socle).
 *
 * Le rôle **n'est pas** dans le HTML du post. Deux sources publiques, toutes deux anonymes, le
 * portent (cf. `docs/specs/protocol-hfr.md`) :
 * - **primaire** — l'**annuaire staff global** (« Contacter un responsable »,
 *   `message-smi-mp-aj.php?responsable=1`) : un seul GET donne la liste des responsables indexée
 *   par **pseudo** ; c'est la source du badge (1 GET + lookups locaux) ;
 * - **secondaire** — la page profil (`/hfr/profil-{userId}.htm`, champ « Statut ») indexée par
 *   `Post.profileId`, réservée à une demande explicite mono-utilisateur (écran profil, PR C).
 *
 * Un même libellé HFR peut se mapper depuis les deux sources ; le mapping `libellé → AuthorRole`
 * est **partagé** (voir `authorRoleFromLabel` dans `:core:parser`) pour rester cohérent.
 *
 * Rôles exposés par HFR :
 * - `Membre` → [MEMBER] ;
 * - `Modérateur` → [MODERATOR] ;
 * - `Administrateur` → [ADMIN] ;
 * - `Super Administrateur` → [SUPER_ADMIN] ;
 * - `Développeur` → [DEVELOPER] ;
 * - `Architecte / Développeur principal` → [ARCHITECT].
 *
 * Tout libellé absent, vide ou non reconnu se mappe à `null` (rôle indéterminé) côté parser :
 * l'entrée staff est ignorée, et le profil rend `null`. Donnée **décorative, publique et
 * best-effort** : elle enrichit l'affichage (badge) sans jamais gouverner une décision de
 * sécurité ; l'UI dégrade silencieusement quand le rôle est inconnu.
 */
enum class AuthorRole {
    /** Membre standard — libellé HFR « Membre ». */
    MEMBER,

    /** Modérateur — libellé HFR « Modérateur ». */
    MODERATOR,

    /** Administrateur — libellé HFR « Administrateur ». */
    ADMIN,

    /** Super administrateur — libellé HFR « Super Administrateur ». */
    SUPER_ADMIN,

    /** Développeur — libellé HFR « Développeur ». */
    DEVELOPER,

    /** Architecte — libellé HFR « Architecte / Développeur principal ». */
    ARCHITECT,
}
