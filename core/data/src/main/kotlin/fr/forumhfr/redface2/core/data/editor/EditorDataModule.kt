package fr.forumhfr.redface2.core.data.editor

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EditorDataModule {

    @Binds
    @Singleton
    abstract fun bindEditorDraftStore(impl: RoomEditorDraftStore): EditorDraftStore
}
