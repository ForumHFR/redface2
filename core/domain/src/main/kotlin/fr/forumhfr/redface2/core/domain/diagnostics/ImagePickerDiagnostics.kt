package fr.forumhfr.redface2.core.domain.diagnostics

import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent

/** Records the platform-free image-picker boundary without retaining complete URI values. */
fun DiagnosticsLog.recordImagePickerEvent(event: ImagePickerEvent) {
    val message = when (event) {
        is ImagePickerEvent.Launched ->
            "launch mode=${event.mode.name} contract=${event.contract.diagnosticName}"
        is ImagePickerEvent.Result -> {
            val sources = event.uris
                .map(DiagnosticRedactor::redactUriSource)
                .distinct()
                .joinToString(prefix = "[", postfix = "]")
            "result contract=${event.contract.diagnosticName} count=${event.uris.size} sources=$sources"
        }
    }
    record(DiagnosticsLog.Level.INFO, IMAGE_PICKER_LOG_TAG, message)
}

/** Records entry into an editor ViewModel's upload path, before blank-URI filtering and guards. */
fun DiagnosticsLog.recordImagesPicked(count: Int) {
    record(DiagnosticsLog.Level.INFO, IMAGE_PICKER_LOG_TAG, "onImagesPicked count=$count")
}

private const val IMAGE_PICKER_LOG_TAG = "ImagePicker"
