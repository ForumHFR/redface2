package fr.forumhfr.redface2.core.ui.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.model.editor.ImagePickerContract
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #1573 — exercise the composable launchers through the registry's raw activity-result path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class EditorImagePickerLauncherTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = RecordingRegistry(context)
    private val events = mutableListOf<ImagePickerEvent>()
    private val mode = mutableStateOf(ImagePickerMode.PHOTO_PICKER)
    private lateinit var launchPicker: () -> Unit
    private val uri = Uri.parse("content://media/images/1573")

    @Test
    fun `photo picker cancellation reaches the registered callback without a result`() {
        assertCancellation(ImagePickerMode.PHOTO_PICKER, ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA)
    }

    @Test
    fun `get content cancellation reaches the registered callback without a result`() {
        assertCancellation(ImagePickerMode.PHOTO_PICKER_GET_CONTENT, ImagePickerContract.GET_MULTIPLE_CONTENTS)
    }

    @Test
    fun `documents cancellation reaches the registered callback without a result`() {
        assertCancellation(ImagePickerMode.DOCUMENT_PICKER, ImagePickerContract.OPEN_MULTIPLE_DOCUMENTS)
    }

    @Test
    fun `successful empty get content response remains a result`() {
        assertGetContentSuccess(null, emptyList())
    }

    @Test
    fun `successful get content response delivers the selected uri`() {
        assertGetContentSuccess(Intent().setData(uri), listOf(uri.toString()))
    }

    @Test
    fun `pending photo picker cancellation keeps its contract after switching to documents`() {
        assertPendingPhotoPickerResult(
            Activity.RESULT_CANCELED,
            null,
            ImagePickerEvent.Cancelled(ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA, Activity.RESULT_CANCELED),
        )
    }

    @Test
    fun `pending photo picker success keeps its uri and contract after switching to documents`() {
        assertPendingPhotoPickerResult(
            Activity.RESULT_OK,
            Intent().setData(uri),
            ImagePickerEvent.Result(ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA, listOf(uri.toString())),
        )
    }

    private fun assertCancellation(initialMode: ImagePickerMode, contract: ImagePickerContract) {
        setPickerContent(initialMode)
        val launch = launchAndAssert(initialMode, contract)
        compose.runOnIdle {
            assertTrue(registry.dispatchResult(launch.requestCode, Activity.RESULT_CANCELED, null))
            assertEquals(
                listOf(
                    ImagePickerEvent.Launched(initialMode, contract),
                    ImagePickerEvent.Cancelled(contract, Activity.RESULT_CANCELED),
                ),
                events,
            )
        }
    }

    private fun assertGetContentSuccess(data: Intent?, expectedUris: List<String>) {
        val initialMode = ImagePickerMode.PHOTO_PICKER_GET_CONTENT
        val contract = ImagePickerContract.GET_MULTIPLE_CONTENTS
        setPickerContent(initialMode)
        val launch = launchAndAssert(initialMode, contract)
        compose.runOnIdle {
            assertTrue(registry.dispatchResult(launch.requestCode, Activity.RESULT_OK, data))
            assertEquals(
                listOf(
                    ImagePickerEvent.Launched(initialMode, contract),
                    ImagePickerEvent.Result(contract, expectedUris),
                ),
                events,
            )
        }
    }

    private fun assertPendingPhotoPickerResult(resultCode: Int, data: Intent?, expected: ImagePickerEvent) {
        val initialMode = ImagePickerMode.PHOTO_PICKER
        val contract = ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA
        setPickerContent(initialMode)
        val pending = launchAndAssert(initialMode, contract)
        compose.runOnIdle { mode.value = ImagePickerMode.DOCUMENT_PICKER }
        // A separate idle boundary lets recomposition replace the returned launch lambda first.
        compose.runOnIdle {
            launchPicker()
            assertEquals(2, registry.launches.size)
            assertEquals(expectedAction(ImagePickerMode.DOCUMENT_PICKER), registry.launches.last().intent.action)
            val launched = listOf(
                ImagePickerEvent.Launched(initialMode, contract),
                ImagePickerEvent.Launched(ImagePickerMode.DOCUMENT_PICKER, ImagePickerContract.OPEN_MULTIPLE_DOCUMENTS),
            )
            assertEquals(launched, events)
            // Reply to the original request, while the new mode also has an outstanding request.
            assertTrue(registry.dispatchResult(pending.requestCode, resultCode, data))
            assertEquals(launched + expected, events)
        }
    }

    private fun setPickerContent(initialMode: ImagePickerMode) {
        mode.value = initialMode
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                launchPicker = rememberEditorImagePicker(mode.value, events::add)
            }
        }
    }

    private fun launchAndAssert(initialMode: ImagePickerMode, contract: ImagePickerContract): Launch =
        compose.runOnIdle {
            launchPicker()
            assertEquals(listOf(ImagePickerEvent.Launched(initialMode, contract)), events)
            registry.launches.single().also {
                assertEquals(expectedAction(initialMode), it.intent.action)
            }
        }

    // Derive only the action from AndroidX, so photo-picker fallback details remain platform-owned.
    private fun expectedAction(selectedMode: ImagePickerMode): String? = when (selectedMode) {
        ImagePickerMode.PHOTO_PICKER -> ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES_PER_UPLOAD)
            .createIntent(context, PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)).action
        ImagePickerMode.PHOTO_PICKER_GET_CONTENT -> ActivityResultContracts.GetMultipleContents()
            .createIntent(context, "image/*").action
        ImagePickerMode.DOCUMENT_PICKER -> ActivityResultContracts.OpenMultipleDocuments()
            .createIntent(context, arrayOf("image/*")).action
    }

    private data class Launch(val requestCode: Int, val intent: Intent)

    private class RecordingRegistry(
        private val context: Context,
    ) : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        val launches = mutableListOf<Launch>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            // Success output alone cannot distinguish a bare contract from the result-code wrapper.
            assertTrue("The composable must launch the result-code wrapper", contract is ImagePickerResultContract<*>)
            launches += Launch(requestCode, contract.createIntent(context, input))
        }
    }
}
