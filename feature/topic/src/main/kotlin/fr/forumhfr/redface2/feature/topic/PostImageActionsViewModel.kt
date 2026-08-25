package fr.forumhfr.redface2.feature.topic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.media.ImageSaveException
import fr.forumhfr.redface2.core.domain.media.PostImageSaver
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * #831 — the THIN ViewModel behind
 * [fr.forumhfr.redface2.core.ui.post.PostImageMenuSheet]'s « Enregistrer l'image » action.
 * Deliberately its own small `@HiltViewModel` (precedent: [QuickReplyViewModel]) instead of a new
 * member on the `@AssistedInject` TopicViewModel: the save needs exactly one injected seam
 * ([PostImageSaver], `:core:domain`) and one feedback channel, nothing of the topic state.
 *
 * The save runs in [viewModelScope], NOT in the sheet's composition: the sheet closes on tap
 * (feedback-through-Toast convention of `:feature:topic`) and the write must survive its
 * dismissal. Effects are one-shot (Channel, same idiom as [QuickReplyViewModel]) and rendered as
 * Toasts by the hosting screen.
 */
@HiltViewModel
class PostImageActionsViewModel @Inject constructor(
    private val postImageSaver: PostImageSaver,
) : ViewModel() {

    /** One-shot feedback of a save request — mapped to a Toast string by the host. */
    enum class SaveImageEffect {
        SAVED,
        FAILED_FETCH,
        FAILED_STORAGE,
        FAILED_TOO_LARGE,
    }

    private val _effects: Channel<SaveImageEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<SaveImageEffect> = _effects.receiveAsFlow()

    /** Saves the image behind [url] into the shared Pictures collection (fire-and-report). */
    fun saveImage(url: String) {
        viewModelScope.launch {
            val effect = try {
                postImageSaver.save(url)
                SaveImageEffect.SAVED
            } catch (e: ImageSaveException) {
                when (e) {
                    is ImageSaveException.Fetch -> SaveImageEffect.FAILED_FETCH
                    is ImageSaveException.Storage -> SaveImageEffect.FAILED_STORAGE
                    is ImageSaveException.TooLarge -> SaveImageEffect.FAILED_TOO_LARGE
                }
            }
            _effects.send(effect)
        }
    }
}
