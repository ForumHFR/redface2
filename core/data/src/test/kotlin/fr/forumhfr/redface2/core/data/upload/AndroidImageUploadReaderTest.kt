package fr.forumhfr.redface2.core.data.upload

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.upload.UploadException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidImageUploadReaderTest {

    private lateinit var provider: TestImageContentProvider
    private lateinit var diagnostics: DiagnosticsLog
    private lateinit var reader: AndroidImageUploadReader

    @Before
    fun setUp() {
        provider = Robolectric.buildContentProvider(TestImageContentProvider::class.java)
            .create(AUTHORITY)
            .get()
        diagnostics = DiagnosticsLog()
        reader = AndroidImageUploadReader(
            context = ApplicationProvider.getApplicationContext<Context>(),
            ioDispatcher = UnconfinedTestDispatcher(),
            diagnostics = diagnostics,
        )
    }

    @Test
    fun `read records safe source mime extension declared size and actual bytes`() = runTest {
        val uri = Uri.parse("content://$AUTHORITY/private-media-id")
        val bytes = byteArrayOf(1, 2, 3, 4)
        provider.mimeType = "image/jpeg"
        provider.displayName = "retouched.photo.jpg"
        provider.declaredSize = 12_345L
        provider.bytes = bytes

        val image = reader.read(uri.toString())

        assertEquals("image/jpeg", image.mimeType)
        assertEquals("retouched.photo.jpg", image.displayName)
        assertTrue(bytes.contentEquals(image.bytes))
        // Guards Robolectric's content URI path: openInputStream -> provider openAssetFile -> openFile.
        assertEquals(1, provider.openFileCallCount)
        val entry = diagnostics.singleReaderInfo()
        assertTrue(entry.message.contains("source=content://$AUTHORITY"))
        assertTrue(entry.message.contains("mime_raw=image/jpeg fallback=false mime=image/jpeg"))
        assertTrue(entry.message.contains("extension=jpg"))
        assertTrue(entry.message.contains("size_col=12345"))
        assertTrue(entry.message.contains("bytes=4"))
        assertTrue(entry.message.contains("duration_ms="))
        assertFalse("the media identifier must not be logged", entry.message.contains("private-media-id"))
        assertFalse("the complete display name must not be logged", entry.message.contains("retouched.photo.jpg"))
    }

    @Test
    fun `read records MIME fallback and returns image wildcard when getType is null`() = runTest {
        val uri = Uri.parse("content://$AUTHORITY/missing-type")
        provider.mimeType = null
        provider.displayName = "photo.heic"
        provider.declaredSize = 3L
        provider.bytes = byteArrayOf(1, 2, 3)

        val image = reader.read(uri.toString())

        assertEquals("image/*", image.mimeType)
        val entry = diagnostics.singleReaderInfo()
        assertTrue(entry.message.contains("mime_raw=null fallback=true mime=image/*"))
        assertTrue(entry.message.contains("extension=heic"))
    }

    @Test
    fun `read maps an opening SecurityException to Network and records permission warning`() = runTest {
        val uri = Uri.parse("content://$AUTHORITY/permission-denied")
        provider.mimeType = "image/jpeg"
        provider.displayName = "photo.jpg"
        provider.declaredSize = 42L
        provider.openFailure = SecurityException("picker grant expired")

        val error = runCatching { reader.read(uri.toString()) }.exceptionOrNull()

        assertTrue(error is UploadException.Network)
        assertTrue(error?.cause is SecurityException)
        assertEquals(1, provider.openFileCallCount)
        val warning = diagnostics.entries.value.single {
            it.tag == "UploadReader" && it.level == DiagnosticsLog.Level.WARN
        }
        assertTrue(warning.message.startsWith("permission "))
        assertTrue(warning.message.contains("ex=java.lang.SecurityException: picker grant expired"))
        assertFalse("the media identifier must not be logged", warning.message.contains("permission-denied"))
    }

    @Test
    fun `read records unavailable display name when the provider omits the column`() = runTest {
        val uri = Uri.parse("content://$AUTHORITY/no-display-name")
        provider.mimeType = "image/png"
        provider.exposeDisplayNameColumn = false
        provider.declaredSize = 2L
        provider.bytes = byteArrayOf(1, 2)

        val image = reader.read(uri.toString())

        assertEquals(null, image.displayName)
        val entry = diagnostics.singleReaderInfo()
        assertTrue(entry.message.contains("display_name=unavailable"))
        assertTrue(entry.message.contains("extension=none"))
    }

    private fun DiagnosticsLog.singleReaderInfo(): DiagnosticsLog.Entry = entries.value.single {
        it.tag == "UploadReader" && it.level == DiagnosticsLog.Level.INFO
    }

    class TestImageContentProvider : ContentProvider() {
        var mimeType: String? = null
        var displayName: String? = null
        var declaredSize: Long? = null
        var exposeDisplayNameColumn: Boolean = true
        var openFailure: RuntimeException? = null
        var bytes: ByteArray = byteArrayOf()
        var openFileCallCount: Int = 0

        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String? = mimeType

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = buildList {
                if (exposeDisplayNameColumn) add(OpenableColumns.DISPLAY_NAME)
                add(OpenableColumns.SIZE)
            }
            return MatrixCursor(columns.toTypedArray()).apply {
                val row = newRow()
                if (exposeDisplayNameColumn) row.add(displayName)
                row.add(declaredSize)
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            openFileCallCount += 1
            openFailure?.let { throw it }
            val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                output.write(bytes)
            }
            return readSide
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    private companion object {
        const val AUTHORITY = "fr.forumhfr.redface2.test.upload"
    }
}
