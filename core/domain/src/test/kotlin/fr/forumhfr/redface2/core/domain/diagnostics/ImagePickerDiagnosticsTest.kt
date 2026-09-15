package fr.forumhfr.redface2.core.domain.diagnostics

import fr.forumhfr.redface2.core.model.editor.ImagePickerContract
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ImagePickerDiagnosticsTest {

    @Test
    fun `launch records the selected mode and actual activity result contract`() {
        val diagnostics = DiagnosticsLog()

        diagnostics.recordImagePickerEvent(
            ImagePickerEvent.Launched(
                mode = ImagePickerMode.PHOTO_PICKER_GET_CONTENT,
                contract = ImagePickerContract.GET_MULTIPLE_CONTENTS,
            ),
        )

        val entry = diagnostics.entries.value.single()
        assertEquals(DiagnosticsLog.Level.INFO, entry.level)
        assertEquals("ImagePicker", entry.tag)
        assertEquals(
            "launch mode=PHOTO_PICKER_GET_CONTENT contract=GetMultipleContents",
            entry.message,
        )
    }

    @Test
    fun `result records count and distinct sources without uri paths`() {
        val diagnostics = DiagnosticsLog()

        diagnostics.recordImagePickerEvent(
            ImagePickerEvent.Result(
                contract = ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA,
                uris = listOf(
                    "content://media/picker/0/com.example/secret-photo.jpg",
                    "content://media/picker/0/com.example/other-secret.jpg",
                    "content://com.android.providers.media.documents/document/image%3A12345",
                ),
            ),
        )

        val entry = diagnostics.entries.value.single()
        assertEquals(DiagnosticsLog.Level.INFO, entry.level)
        assertEquals("ImagePicker", entry.tag)
        assertEquals(
            "result contract=PickMultipleVisualMedia count=3 " +
                "sources=[content://media, content://com.android.providers.media.documents]",
            entry.message,
        )
        assertFalse(entry.message.contains("secret-photo"))
        assertFalse(entry.message.contains("document/"))
        assertFalse(entry.message.contains("12345"))
    }

    @Test
    fun `empty result and ViewModel entry are still recorded`() {
        val diagnostics = DiagnosticsLog()

        diagnostics.recordImagePickerEvent(
            ImagePickerEvent.Result(
                contract = ImagePickerContract.OPEN_MULTIPLE_DOCUMENTS,
                uris = emptyList(),
            ),
        )
        diagnostics.recordImagesPicked(count = 0)

        assertEquals(
            listOf(
                "result contract=OpenMultipleDocuments count=0 sources=[]",
                "onImagesPicked count=0",
            ),
            diagnostics.entries.value.map { it.message },
        )
    }

    @Test
    fun `result limits the source list to five distinct authorities`() {
        val diagnostics = DiagnosticsLog()
        val authorities = listOf(
            "media",
            "com.android.providers.media.documents",
            "com.android.providers.downloads.documents",
            "com.android.externalstorage.documents",
            "com.google.android.apps.photos.contentprovider",
            "com.google.android.apps.docs.storage",
        )

        diagnostics.recordImagePickerEvent(
            ImagePickerEvent.Result(
                contract = ImagePickerContract.OPEN_MULTIPLE_DOCUMENTS,
                uris = authorities.map { "content://$it/private/path" },
            ),
        )

        assertEquals(
            "result contract=OpenMultipleDocuments count=6 sources=[" +
                "content://media, content://com.android.providers.media.documents, " +
                "content://com.android.providers.downloads.documents, " +
                "content://com.android.externalstorage.documents, " +
                "content://com.google.android.apps.photos.contentprovider, …]",
            diagnostics.entries.value.single().message,
        )
        assertFalse(diagnostics.entries.value.single().message.contains("apps.docs.storage"))
    }
}
