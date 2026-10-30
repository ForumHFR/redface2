package fr.forumhfr.redface2.core.model

/** Public Wiki metadata: an unknown smiley and a smiley without keywords both load an empty list. */
sealed interface SmileyWikiKeywordsResult {
    data class Loaded(val keywords: List<String>) : SmileyWikiKeywordsResult
    data object Failed : SmileyWikiKeywordsResult
    data object NotApplicable : SmileyWikiKeywordsResult
}
