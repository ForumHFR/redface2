package fr.forumhfr.redface2.core.network.qualifiers

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthenticatedClient

/**
 * Authenticated HFR client reserved for mutations. Automatic connection retries are disabled so a
 * request cannot be replayed after HFR may already have applied it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MutationClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AnonymousClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HfrBaseUrl

/**
 * Third-party image-host client (#459). Cookie-less so the HFR session is never leaked to imgur /
 * diberie, with longer write/call timeouts than the HFR clients (a 20 MB binary upload, not a short
 * urlencoded `FormBody`). Automatic connection retries are disabled to avoid replaying an upload.
 * Never route an upload through [AuthenticatedClient].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UploadClient
