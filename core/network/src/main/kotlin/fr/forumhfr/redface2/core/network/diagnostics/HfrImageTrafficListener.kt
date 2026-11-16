package fr.forumhfr.redface2.core.network.diagnostics

import java.io.IOException
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Request

/** Counts HFR image activity for probe interference only; no image telemetry or URL is exported. */
internal class HfrImageTrafficListener(private val diagnostics: HfrNetworkDiagnostics) : EventListener() {
    private var active = false
    private var ended = false

    override fun callStart(call: Call) { observe(call.request()) }

    // Also covers a redirect from an external image host to HFR.
    override fun requestHeadersEnd(call: Call, request: Request) { observe(request) }

    override fun callEnd(call: Call) { finish() }
    override fun callFailed(call: Call, ioe: IOException) { finish() }

    @Synchronized
    private fun observe(request: Request) {
        if (!ended && !active && request.url.host == "forum.hardware.fr") {
            active = true
            diagnostics.imageStarted()
        }
    }

    @Synchronized
    private fun finish() {
        if (ended) return
        ended = true
        if (active) diagnostics.imageFinished()
    }
}
