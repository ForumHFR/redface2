package fr.forumhfr.redface2.core.model.write

/**
 * Identifies the catégorie / sous-catégorie d'arrivée when the user opens the
 * create-topic composer from `ForumCategoryScreen` (Phase 2E #149).
 *
 * `entrySubcat` is nullable on purpose : the user can land on the composer
 * either with a sub-category chip selected (`entrySubcat = 550`) or on the
 * « Toutes les sous-catégories » view (`entrySubcat = null`). The final
 * sub-category sent on submit is **not** carried by this context : it is
 * the dropdown choice in the editor (`selectedSubcat: Int`), so a fail-fast
 * `init { require(selectedSubcat > 0) }` would be wrong here.
 *
 * `from_subcat` (the HFR hidden POST field) reflects the d'arrivée chip, not
 * the final pick. The repository forwards it from `hiddenFields["from_subcat"]`
 * when present, falling back to `entrySubcat` only when HFR did not emit it.
 */
data class NewTopicContext(
    val cat: Int,
    val entrySubcat: Int?,
) {
    init {
        require(cat > 0) { "cat must be > 0, was $cat" }
        require(entrySubcat == null || entrySubcat > 0) {
            "entrySubcat must be null or > 0, was $entrySubcat"
        }
    }
}
