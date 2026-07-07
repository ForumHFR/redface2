package fr.forumhfr.redface2.core.data.media

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.media.PostImageSaver
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface MediaModule {

    // #831 — the image contextual menu's « Enregistrer l'image » saves through this seam ; the
    // Android impl lives here (it needs MediaStore + the Coil disk cache) so the feature ViewModel
    // stays platform-free — same pattern as UploadProviderBindingsModule.bindImageUploadReader.
    @Binds
    @Singleton
    fun bindPostImageSaver(impl: AndroidPostImageSaver): PostImageSaver
}
