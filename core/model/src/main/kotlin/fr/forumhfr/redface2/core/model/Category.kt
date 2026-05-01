package fr.forumhfr.redface2.core.model

/**
 * Une catégorie HFR telle qu'exposée par la page racine `forum.php?config=hfr.inc`.
 *
 * **Note importante** : la racine HFR n'expose **pas** d'identifiant numérique pour les
 * catégories — uniquement le nom et le slug mod_rewrite. L'ID numérique (`cat`) est
 * récupéré ultérieurement, via le `<input type="hidden" name="cat">` ou le
 * `<select name="cat">` présent sur n'importe quelle page de liste de topics
 * (`forum1.php?config=hfr.inc&cat=...`). Garder ce modèle aligné sur ce que la fixture
 * racine prouve évite la dette de spec.
 */
data class Category(
    val name: String,
    /** Slug mod_rewrite, ex: `Discussions`, `Hardware`, `AchatsVentes`. */
    val slug: String,
    val subcategories: List<SubCategory>,
)
