package fr.forumhfr.redface2.core.network

import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/** Also blocks HTTP follow-up retries (e.g. 503 Retry-After: 0), beyond connection retry policy. */
internal class FavoriteSmileyRequestBody(private val form: FormBody) : RequestBody() {
    override fun contentType(): MediaType = form.contentType()
    override fun contentLength(): Long = form.contentLength()
    override fun writeTo(sink: BufferedSink) = form.writeTo(sink)
    override fun isOneShot(): Boolean = true
}
