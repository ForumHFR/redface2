package fr.forumhfr.redface2.feature.topic

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.media.ImageSaveException
import fr.forumhfr.redface2.core.domain.media.PostImageSaver
import fr.forumhfr.redface2.core.domain.media.SavedPostImage
import fr.forumhfr.redface2.feature.topic.PostImageActionsViewModel.SaveImageEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * #831 — pins the effect mapping of the « Enregistrer l'image » ViewModel: one one-shot effect
 * per save request, typed failure → typed Toast message (fetch / storage / too-large), and the
 * saver called with the exact image URL. The saver is faked (interface in `:core:domain`), same
 * seam-testing stance as the upload reader.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PostImageActionsViewModelTest {

    private class FakePostImageSaver(
        private val behaviour: (String) -> SavedPostImage,
    ) : PostImageSaver {
        val requests = mutableListOf<String>()
        override suspend fun save(url: String): SavedPostImage {
            requests += url
            return behaviour(url)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a successful save emits SAVED and forwards the URL to the saver`() = runTest {
        val saver = FakePostImageSaver { SavedPostImage(displayName = "vacances.png") }
        val viewModel = PostImageActionsViewModel(saver)

        viewModel.effects.test {
            viewModel.saveImage("https://images.example/vacances.png")
            assertEquals(SaveImageEffect.SAVED, awaitItem())
        }
        assertEquals(listOf("https://images.example/vacances.png"), saver.requests)
    }

    @Test
    fun `a fetch failure emits FAILED_FETCH`() = runTest {
        val viewModel = PostImageActionsViewModel(FakePostImageSaver { throw ImageSaveException.Fetch() })

        viewModel.effects.test {
            viewModel.saveImage("https://images.example/gone.png")
            assertEquals(SaveImageEffect.FAILED_FETCH, awaitItem())
        }
    }

    @Test
    fun `a storage failure emits FAILED_STORAGE`() = runTest {
        val viewModel = PostImageActionsViewModel(FakePostImageSaver { throw ImageSaveException.Storage() })

        viewModel.effects.test {
            viewModel.saveImage("https://images.example/full-disk.png")
            assertEquals(SaveImageEffect.FAILED_STORAGE, awaitItem())
        }
    }

    @Test
    fun `an oversized image emits FAILED_TOO_LARGE`() = runTest {
        val viewModel = PostImageActionsViewModel(
            FakePostImageSaver { throw ImageSaveException.TooLarge(maxBytes = 1L) },
        )

        viewModel.effects.test {
            viewModel.saveImage("https://images.example/huge.png")
            assertEquals(SaveImageEffect.FAILED_TOO_LARGE, awaitItem())
        }
    }
}
