package fr.forumhfr.redface2.core.model

/**
 * A top-level HFR forum category. Lives at the root of the forum hierarchy
 * (e.g. "Hardware", "Discussions", "Programmation"). 19 are publicly exposed by
 * the REST endpoint `forums/hardwarefr/categories/`. The full list of subcategories
 * is fetched on demand via a separate endpoint to avoid an N+1 on the home screen.
 */
data class Category(
    val id: Int,
    val name: String,
    /**
     * When true, the legacy HFR forum1.php UI forces the user to pick a subcategory
     * before listing topics. Mirrors the REST `force_subcat` boolean.
     */
    val forceSubcat: Boolean,
    /**
     * Number of subcategories under this category. Mapped from REST
     * `number_of_subcategories` on the category list payload — we do not consume
     * `links.subcategories.count` because the public list omits the `links` block
     * for the categories themselves (see `RestCategory`'s field-by-field comment).
     */
    val subcategoryCount: Int,
)

/**
 * A second-level grouping inside a [Category]. Each subcategory has its own topic list
 * accessible via REST `categories/{cat}/subcategories/{sub}/topics/last/`.
 */
data class SubCategory(
    val id: Int,
    val name: String,
    val parentCategoryId: Int,
)
