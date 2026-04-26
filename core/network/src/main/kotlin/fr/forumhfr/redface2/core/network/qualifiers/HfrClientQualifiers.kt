package fr.forumhfr.redface2.core.network.qualifiers

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthenticatedClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AnonymousClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HfrBaseUrl
