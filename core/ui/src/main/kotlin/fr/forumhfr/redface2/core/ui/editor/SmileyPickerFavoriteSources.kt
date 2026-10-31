package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollections
import fr.forumhfr.redface2.core.model.EditorSmiley

/** HFR mutations require [localCollections] to observe their account owner; form lists can be read alone. */
data class SmileyPickerFavoriteSources(
    val favoriteSmileys: () -> List<EditorSmiley> = { emptyList() },
    val hfrFavoriteMutations: SmileyFavoriteMutations? = null,
    val localCollections: SmileyLocalCollections? = null,
)
