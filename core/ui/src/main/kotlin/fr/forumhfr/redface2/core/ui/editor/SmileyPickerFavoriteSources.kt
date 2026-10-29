package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollections
import fr.forumhfr.redface2.core.model.EditorSmiley

/** Host-supplied favorite sources; HFR and device collections can be provided independently. */
data class SmileyPickerFavoriteSources(
    val favoriteSmileys: () -> List<EditorSmiley> = { emptyList() },
    val hfrFavoriteMutations: SmileyFavoriteMutations? = null,
    val localCollections: SmileyLocalCollections? = null,
)
