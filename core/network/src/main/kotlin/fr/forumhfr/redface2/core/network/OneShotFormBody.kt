package fr.forumhfr.redface2.core.network

import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/** ED-168: prevent replay without consuming the form inspected by smiley accounting. */
internal class OneShotFormBody(private val form: FormBody) : RequestBody() {
    override fun contentType(): MediaType = form.contentType()
    override fun contentLength(): Long = form.contentLength()
    override fun writeTo(sink: BufferedSink) = form.writeTo(sink)
    override fun isOneShot(): Boolean = true
}
