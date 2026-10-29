package fr.forumhfr.redface2.core.data.smiley

import javax.inject.Qualifier

/** Qualifies the single DataStore instance for the dedicated local smiley favorites file. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LocalFavoriteSmileyDataStore
