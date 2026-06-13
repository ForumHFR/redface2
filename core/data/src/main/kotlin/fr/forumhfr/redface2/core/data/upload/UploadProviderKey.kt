package fr.forumhfr.redface2.core.data.upload

import dagger.MapKey
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId

/**
 * Dagger [MapKey] for the `Map<UploadProviderId, UploadProvider>` multibinding (#459): keys each
 * `@IntoMap` provider binding by its [UploadProviderId] so [DefaultUploadRepository] can select the
 * implementation matching the current preference.
 */
@MapKey
@Retention(AnnotationRetention.RUNTIME)
internal annotation class UploadProviderKey(val value: UploadProviderId)
