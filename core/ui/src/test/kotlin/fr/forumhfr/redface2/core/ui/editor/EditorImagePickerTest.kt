package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.editor.ImagePickerContract
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1128 — selector routing and the shared selection ceiling need no Android runtime. */
class EditorImagePickerTest {

    @Test
    fun `PHOTO_PICKER keeps the photo picker request`() {
        assertEquals(ImagePickRequest.PhotoPicker, imagePickRequestFor(ImagePickerMode.PHOTO_PICKER))
    }

    @Test
    fun `PHOTO_PICKER_GET_CONTENT requests image content via GET_CONTENT`() {
        assertEquals(
            ImagePickRequest.GetContent("image/*"),
            imagePickRequestFor(ImagePickerMode.PHOTO_PICKER_GET_CONTENT),
        )
    }

    @Test
    fun `DOCUMENT_PICKER requests only image documents`() {
        assertEquals(
            ImagePickRequest.Documents(listOf("image/*")),
            imagePickRequestFor(ImagePickerMode.DOCUMENT_PICKER),
        )
    }

    @Test
    fun `each picker mode reports its actual Activity Result contract`() {
        assertEquals(
            ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA,
            imagePickerContractFor(ImagePickerMode.PHOTO_PICKER),
        )
        assertEquals(
            ImagePickerContract.GET_MULTIPLE_CONTENTS,
            imagePickerContractFor(ImagePickerMode.PHOTO_PICKER_GET_CONTENT),
        )
        assertEquals(
            ImagePickerContract.OPEN_MULTIPLE_DOCUMENTS,
            imagePickerContractFor(ImagePickerMode.DOCUMENT_PICKER),
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

    @Test
    fun `photo picker results keep all callback uris for upload`() {
        val images = (1..11).toList()

        assertEquals(images, pickedImagesForUpload(ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA, images))
    }

    @Test
    fun `document and get content results keep the original application cap`() {
        val images = (1..11).toList()

        assertEquals(
            (1..10).toList(),
            pickedImagesForUpload(ImagePickerContract.OPEN_MULTIPLE_DOCUMENTS, images),
        )
        assertEquals(
            (1..10).toList(),
            pickedImagesForUpload(ImagePickerContract.GET_MULTIPLE_CONTENTS, images),
        )
    }

    @Test
    fun `empty picker result is emitted before upload filtering`() {
        val events = mutableListOf<ImagePickerEvent>()

        emitImagePickerResult(
            contract = ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA,
            uris = emptyList<String>(),
            onEvent = events::add,
        )

        assertEquals(
            listOf(
                ImagePickerEvent.Result(
                    contract = ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA,
                    uris = emptyList(),
                ),
            ),
            events,
        )
    }

    @Test
    fun `non-empty picker result emits every uri in order before capping`() {
        val events = mutableListOf<ImagePickerEvent>()
        val uris = (1..11).map { "content://provider/image/$it" }

        emitImagePickerResult(
            contract = ImagePickerContract.OPEN_MULTIPLE_DOCUMENTS,
            uris = uris,
            onEvent = events::add,
        )

        assertEquals(
            ImagePickerEvent.Result(
                contract = ImagePickerContract.OPEN_MULTIPLE_DOCUMENTS,
                uris = uris,
            ),
            events.single(),
        )
    }
}
