package fr.forumhfr.redface2.core.domain.preferences

/**
 * « Mes drapeaux » filter local to the category (Forum) screen (#455), replicating the web
 * `owntopic` toolbar. [ALL] is the normal paginated listing; the three other modes show ONLY the
 * user's flagged topics of that (sub)category for the matching REST bucket — not a decoration of
 * the listing.
 *
 * Lives in `:core:domain` (rather than in `:feature:forum`) since #1132 so the DataStore layer can
 * persist it without a parallel enum or a mapping shim: the last non-anonymous choice is remembered
 * (cf. [UserPreferencesRepository.observeForumCategoryFlagFilter]) and re-applied as the seed when
 * the user enters the next category, instead of always starting from [ALL].
 */
enum class CategoryFlagFilter { ALL, PARTICIPATED, READ, FAVORITES }
