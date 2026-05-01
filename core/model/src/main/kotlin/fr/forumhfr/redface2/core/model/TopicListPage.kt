package fr.forumhfr.redface2.core.model

/**
 * Snapshot d'une page de liste de topics — résultat parsé de
 * `forum1.php?config=hfr.inc&cat=X&subcat=Y&page=Z`.
 *
 * `currentPage` provient de la pagination affichée (numéro entouré d'un `<b>` sans
 * `<a>`). `totalPages` est le maximum des liens `liste_sujet-(\d+).htm` trouvés dans la
 * pagination — HFR ne paginant pas linéairement (1..10 puis 20, 30, ..., N), ce maximum
 * reflète la dernière page atteignable depuis la page courante.
 */
data class TopicListPage(
    val cat: Int,
    /** `null` quand l'URL appelée avait `subcat=0`. */
    val subcat: Int?,
    val currentPage: Int,
    val totalPages: Int,
    val topics: List<TopicSummary>,
)
