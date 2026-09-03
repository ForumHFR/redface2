package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.SubCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

internal fun ForumRepository.subcategoryNamesForFlags(
    flags: List<Flag>,
    refreshIfMissing: (Int) -> Unit = {},
): Flow<Map<SubcategoryKey, String>> {
    val cats = flags.asSequence()
        .filter { it.subcat != null }
        .map { it.cat }
        .distinct()
        .sorted()
        .toList()
    if (cats.isEmpty()) return flowOf(emptyMap())
    return combine(cats.map { cat -> subcategoryNamesForCat(cat, refreshIfMissing) }) { perCat ->
        perCat.fold(mutableMapOf<SubcategoryKey, String>()) { merged, names ->
            merged.apply { putAll(names) }
        }
    }
}

private fun ForumRepository.subcategoryNamesForCat(
    cat: Int,
    refreshIfMissing: (Int) -> Unit,
): Flow<Map<SubcategoryKey, String>> =
    observeCachedSubcategories(cat)
        .onEach { result ->
            if (result == null) refreshIfMissing(cat)
        }
        .map { result -> result.toSubcategoryNameMap(cat) }
        .distinctUntilChanged()

private fun ForumResult<List<SubCategory>>?.toSubcategoryNameMap(cat: Int): Map<SubcategoryKey, String> =
    (this as? ForumResult.Success)
        ?.value
        .orEmpty()
        .associate { subcategory -> SubcategoryKey(cat = cat, subcat = subcategory.id) to subcategory.name }

internal data class SubcategoryKey(val cat: Int, val subcat: Int)

internal fun Flag.subcategoryKey(): SubcategoryKey? =
    subcat?.let { SubcategoryKey(cat = cat, subcat = it) }

internal fun toFlagRows(
    flags: List<Flag>,
    markerStyle: MarkerStyle,
    subcategoryNames: Map<SubcategoryKey, String>,
): List<FlagRowUiModel> =
    flags.map { flag ->
        flag.toFlagRowUiModel(
            markerStyle = markerStyle,
            subcatName = flag.subcategoryKey()?.let(subcategoryNames::get),
        )
    }
