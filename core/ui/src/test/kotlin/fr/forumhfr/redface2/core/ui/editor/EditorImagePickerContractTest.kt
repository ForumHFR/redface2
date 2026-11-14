package fr.forumhfr.redface2.core.ui.editor

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.model.editor.ImagePickerContract
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34])
class EditorImagePickerContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uris = listOf(Uri.parse("content://media/private/1"), Uri.parse("content://media/private/2"))

    @Test
    fun `photo picker preserves native intents and distinguishes cancellation from successful results`() {
        assertContract(
            ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES_PER_UPLOAD),
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA,
        )
    }

    @Test
    fun `documents picker preserves native intents and distinguishes cancellation from successful results`() {
        assertContract(
            ActivityResultContracts.OpenMultipleDocuments(),
            arrayOf("image/*"),
            ImagePickerContract.OPEN_MULTIPLE_DOCUMENTS,
        )
    }

    @Test
    fun `get content preserves native intents and distinguishes cancellation from successful results`() {
        assertContract(
            ActivityResultContracts.GetMultipleContents(),
            "image/*",
            ImagePickerContract.GET_MULTIPLE_CONTENTS,
        )
    }

    private fun <I> assertContract(
        delegate: ActivityResultContract<I, List<Uri>>,
        input: I,
        contract: ImagePickerContract,
    ) {
        val wrapped = ImagePickerResultContract(delegate)
        val expected = delegate.createIntent(context, input)
        val actual = wrapped.createIntent(context, input)
        assertEquals(expected.action, actual.action)
        assertEquals(expected.type, actual.type)
        assertEquals(expected.toUri(Intent.URI_INTENT_SCHEME), actual.toUri(Intent.URI_INTENT_SCHEME))
        assertEquals(expected.extras?.keySet(), actual.extras?.keySet())
        assertArrayEquals(
            expected.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
            actual.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
        )
        assertNull(delegate.getSynchronousResult(context, input))
        assertNull(wrapped.getSynchronousResult(context, input))
        val data = Intent().apply {
            this.data = uris.first()
            clipData = ClipData.newRawUri("images", uris.first()).apply {
                addItem(ClipData.Item(uris.last()))
                addItem(ClipData.Item(uris.first()))
            }
        }
        listOf(Activity.RESULT_CANCELED, 17, Activity.RESULT_OK).forEach { code ->
            listOf(null, Intent(), data).forEach { intent ->
                assertResult(delegate, wrapped, contract, code, intent)
            }
        }
        assertEquals(uris, wrapped.parseResult(Activity.RESULT_OK, data).uris)
    }

    private fun <I> assertResult(
        delegate: ActivityResultContract<I, List<Uri>>,
        wrapped: ImagePickerResultContract<I>,
        contract: ImagePickerContract,
        code: Int,
        intent: Intent?,
    ) {
        val result = wrapped.parseResult(code, intent)
        assertEquals(code, result.resultCode)
        assertEquals(delegate.parseResult(code, intent), result.uris)
        val events = mutableListOf<ImagePickerEvent>()
        emitImagePickerResult(contract, result.resultCode, result.uris, events::add)
        val expected = if (code == Activity.RESULT_OK) {
            ImagePickerEvent.Result(contract, delegate.parseResult(code, intent).map(Uri::toString))
        } else {
            ImagePickerEvent.Cancelled(contract, code)
        }
        assertEquals(listOf(expected), events)
    }

    @Test
    fun `wrapper passes the exact intent and synchronous result through the delegate`() {
        val input = "image/*"
        val expectedIntent = Intent(Intent.ACTION_GET_CONTENT).putExtra("sentinel", arrayOf("unchanged"))
        var synchronousUris: List<Uri>? = uris
        val delegate = object : ActivityResultContract<String, List<Uri>>() {
            override fun createIntent(context: Context, input: String): Intent {
                assertSame(this@EditorImagePickerContractTest.context, context)
                assertEquals("image/*", input)
                return expectedIntent
            }

            override fun getSynchronousResult(context: Context, input: String): SynchronousResult<List<Uri>>? {
                assertSame(this@EditorImagePickerContractTest.context, context)
                assertEquals("image/*", input)
                return synchronousUris?.let { SynchronousResult(it) }
            }

            override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
                assertEquals(17, resultCode)
                assertSame(expectedIntent, intent)
                return uris
            }
        }
        val wrapped = ImagePickerResultContract(delegate)
        assertSame(expectedIntent, wrapped.createIntent(context, input))
        assertEquals(
            ImagePickerActivityResult(Activity.RESULT_OK, uris),
            wrapped.getSynchronousResult(context, input)?.value,
        )
        synchronousUris = null
        assertNull(wrapped.getSynchronousResult(context, input))
        assertEquals(ImagePickerActivityResult(17, uris), wrapped.parseResult(17, expectedIntent))
    }
}
