package fr.forumhfr.redface2.core.model

/**
 * Une sous-catégorie HFR. Comme [Category], le modèle racine n'expose que le slug et le
 * nom — pas d'ID numérique. `parentCategorySlug` permet de remonter à la catégorie
 * d'appartenance lors du parsing (utile aussi pour les routes Compose Navigation 3).
 *
 * On ne stocke pas le `topicCount` même si la cellule `td.catCase2` de la racine HFR le
 * porte : ce compteur est attaché à la **catégorie parente**, pas à la sous-catégorie. Le
 * surfacer ici serait au mieux trompeur, au pire faux.
 */
data class SubCategory(
    val name: String,
    /** Slug mod_rewrite, ex: `Viepratique`, `Hardware` (pour AchatsVentes). */
    val slug: String,
    /** Slug de la [Category] parente, ex: `Discussions`, `AchatsVentes`. */
    val parentCategorySlug: String,
)
