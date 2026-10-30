package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.SmileyWikiKeywordsResult

/** Repository callbacks, shared by all editor hosts without injecting data-layer types into UI. */
data class SmileyWikiKeywordsSource(
    val cached: (String) -> SmileyWikiKeywordsResult.Loaded? = { null },
    val load: suspend (String) -> SmileyWikiKeywordsResult = { SmileyWikiKeywordsResult.Failed },
)
