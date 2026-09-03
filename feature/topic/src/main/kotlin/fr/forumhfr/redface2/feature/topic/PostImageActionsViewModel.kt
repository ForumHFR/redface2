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
 * [fr.forumhfr.redface2.core.ui.post.PostImageMenuSheet]'s host-owned actions.
 * Deliberately its own small `@HiltViewModel` (precedent: [QuickReplyViewModel]) instead of a new
 * member on the `@AssistedInject` TopicViewModel: the image menu needs one injected save seam
 * ([PostImageSaver], `:core:domain`) and one effect channel, nothing of the topic state.
 *
 * Save runs in [viewModelScope], NOT in the sheet's composition: the sheet closes on tap
 * (feedback-through-Toast convention of `:feature:topic`) and the write must survive dismissal.
 * Share is host-side Android UI, so it is emitted as a one-shot effect on the same channel.
 */
@HiltViewModel
class PostImageActionsViewModel @Inject constructor(
    private val postImageSaver: PostImageSaver,
) : ViewModel() {

    /** One-shot image-menu effects rendered by the host. */
    sealed interface Effect

    /** One-shot feedback of a save request — mapped to a Toast string by the host. */
    enum class SaveImageEffect : Effect {
        SAVED,
        FAILED_FETCH,
        FAILED_STORAGE,
        FAILED_TOO_LARGE,
    }

    data class ShareImageEffect(val url: String) : Effect

    private val _effects: Channel<Effect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<Effect> = _effects.receiveAsFlow()

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

    /** Requests the host to share [url] through Android's chooser. */
    fun shareImage(url: String) {
        viewModelScope.launch {
            _effects.send(ShareImageEffect(url))
        }
    }
}
