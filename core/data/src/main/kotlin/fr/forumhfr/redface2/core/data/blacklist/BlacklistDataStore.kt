package fr.forumhfr.redface2.core.data.blacklist

import javax.inject.Qualifier

/**
 * Qualifies the [androidx.datastore.core.DataStore] dedicated to the local blacklist, so it is not
 * confused with the user-preferences store ([fr.forumhfr.redface2.core.data.preferences.UserPreferencesDataStore]).
 * Kept in its own file so the business list and the simple preferences never share a store.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BlacklistDataStore
