package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.upload.UploadedImage
import fr.forumhfr.redface2.core.domain.upload.UploadedImageRecord
import fr.forumhfr.redface2.core.model.AuthState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * #459 — shared fakes of the MP composers' upload trio, mirrored from TopicFormViewModelTest.
 * Top-level (not nested) so both PrivateMessageComposeViewModelTest and
 * PrivateMessageReplyViewModelTest reuse them without duplication.
 */

/** Fixed [AuthState] so the VM resolves (or not) an upload `userId`. */
internal class FakeAuthRepository(
    private val authState: AuthState = AuthState.Authenticated("alice"),
) : AuthRepository {
    override fun observeAuthState(): Flow<AuthState> = MutableStateFlow(authState)
    override suspend fun login(pseudo: String, password: String): Result<AuthState.Authenticated> =
        Result.success(AuthState.Authenticated(pseudo))

    override suspend fun logout() = Unit
}

/** Canned [ImageUploadReader] (1-byte PNG), records the picked uris in call order. */
internal class FakeImageUploadReader : ImageUploadReader {
    val readUris: MutableList<String> = mutableListOf()

    override suspend fun read(uri: String): ImageUpload {
        readUris += uri
        return ImageUpload(bytes = byteArrayOf(0), mimeType = "image/png", displayName = null)
    }
}

/** Fake [UploadRepository] ; only [uploadWithCurrentProvider] matters to the composers. */
internal class FakeUploadRepository : UploadRepository {
    var uploadException: Throwable? = null
    var uploadCalls: Int = 0
        private set

    override suspend fun uploadWithCurrentProvider(image: ImageUpload, userId: String): UploadedImage {
        uploadCalls += 1
        uploadException?.let { throw it }
        return UploadedImage(
            provider = UploadProviderId.DIBERIE,
            imageUrl = "https://rehost.diberie.com/Picture/Get/f/1",
            thumbnailUrl = null,
            resizedUrl = null,
            deleteHandle = null,
            expiresAt = null,
        )
    }

    override fun observeUploads(userId: String): Flow<List<UploadedImageRecord>> =
        MutableStateFlow(emptyList())

    override suspend fun delete(record: UploadedImageRecord, userId: String): Boolean = false
}
