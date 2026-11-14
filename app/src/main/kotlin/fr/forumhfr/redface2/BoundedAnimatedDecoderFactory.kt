package fr.forumhfr.redface2

import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.gif.AnimatedImageDecoder
import coil3.request.Options
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * #1411: animated decoding copies the full encoded source into the Java heap, using about twice
 * the GIF size per decode. Nine to fourteen concurrent decodes were measured on return from the viewer.
 * Share one semaphore across this factory's decoders to bound that transient allocation overlap.
 */
internal class BoundedAnimatedDecoderFactory(
    private val delegate: Decoder.Factory = AnimatedImageDecoder.Factory(),
    permits: Int = DEFAULT_DECODE_PERMITS,
) : Decoder.Factory {

    private val semaphore = Semaphore(permits)

    override fun create(
        result: SourceFetchResult,
        options: Options,
        imageLoader: ImageLoader,
    ): Decoder? {
        val decoder = delegate.create(result, options, imageLoader) ?: return null
        return Decoder {
            semaphore.withPermit { decoder.decode() }
        }
    }

    private companion object {
        const val DEFAULT_DECODE_PERMITS = 1
    }
}
