package fr.forumhfr.redface2.core.model

/**
 * Une ligne de la liste de topics d'une (sous-)catégorie, telle que renvoyée par
 * `forum1.php?config=hfr.inc&cat=X&subcat=Y&page=Z`.
 *
 * Le mapping des colonnes `td.sujetCase{1..9}` est **identique** à celui de la page des
 * drapeaux (cf. `Flag` et `FlagsListParser`) — c'est le même rendu HFR sous-jacent.
 *
 * `firstUnreadPostId` n'est volontairement PAS exposé ici : la liste de topics ne porte
 * pas l'ancre `#t<numreponse>` que la page des drapeaux ajoute aux liens. Garder le
 * modèle honnête sur ce que la fixture prouve.
 */
data class TopicSummary(
    val cat: Int,
    /** `null` quand l'URL appelée avait `subcat=0` (toutes les sous-catégories). */
    val subcat: Int?,
    val post: Int,
    val title: String,
    /** Auteur du premier post — colonne `td.sujetCase6`. */
    val firstPostAuthor: String,
    /** Nombre de réponses — colonne `td.sujetCase7` ("Rép."). */
    val replyCount: Int,
    /** Nombre de vues — colonne `td.sujetCase8` ("Lues"). */
    val views: Int,
    /** Numéro de la dernière page — colonne `td.sujetCase4` ("Dern. page"). */
    val totalPages: Int,
    val lastReplyAuthor: String,
    /** Timestamp brut HFR (`DD-MM-YYYY HH:mm`) — parsing reporté côté UI. */
    val lastReplyAt: String,
    val isSticky: Boolean,
    val isLocked: Boolean,
    /**
     * Ne devient significatif que pour une requête authentifiée. Pour une requête
     * anonyme, HFR sert toujours l'icône `closedb_new.gif` (« nouveau sujet »), donc
     * `hasUnread` est mécaniquement `true` partout — le champ doit alors être ignoré côté
     * UI (cf. règle dans `:core:data` quand on saura différencier les deux cas).
     */
    val hasUnread: Boolean,
)
