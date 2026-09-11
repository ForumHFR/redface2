package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1128 — selector routing and the DocumentsUI ceiling need no Android runtime. */
class EditorImagePickerTest {

    @Test
    fun `PHOTO_PICKER keeps the photo picker request`() {
        assertEquals(ImagePickRequest.PhotoPicker, imagePickRequestFor(ImagePickerMode.PHOTO_PICKER))
    }

    @Test
    fun `DOCUMENT_PICKER requests only image documents`() {
        assertEquals(
            ImagePickRequest.Documents(listOf("image/*")),
            imagePickRequestFor(ImagePickerMode.DOCUMENT_PICKER),
        )
    }

    @Test
    fun `capPickedImages keeps the first ten of eleven images in order`() {
        assertEquals((1..10).toList(), capPickedImages((1..11).toList()))
    }

    @Test
    fun `capPickedImages preserves a three image selection`() {
        val images = listOf("first", "second", "third")
        assertEquals(images, capPickedImages(images))
    }

    @Test
    fun `capPickedImages preserves an empty selection`() {
        assertEquals(emptyList<String>(), capPickedImages(emptyList<String>()))
    }
}
