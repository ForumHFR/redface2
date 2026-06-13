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

/**
 * Third-party image-host client (#459). Cookie-less so the HFR session is never leaked to imgur /
 * diberie, with longer write/call timeouts than the HFR clients (a 20 MB binary upload, not a short
 * urlencoded `FormBody`). Never route an upload through [AuthenticatedClient].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UploadClient
