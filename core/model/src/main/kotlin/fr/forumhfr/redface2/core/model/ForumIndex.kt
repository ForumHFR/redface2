package fr.forumhfr.redface2.core.model

/**
 * Snapshot de la racine HFR (`forum.php?config=hfr.inc`) : la liste ordonnée des
 * catégories, chacune portant ses sous-catégories. Pas d'ID numérique exposé à ce niveau
 * — voir [Category] pour la justification.
 */
data class ForumIndex(
    val categories: List<Category>,
)
