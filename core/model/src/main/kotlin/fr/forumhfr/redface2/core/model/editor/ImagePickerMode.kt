package fr.forumhfr.redface2.core.model.editor

/**
 * Which Android image selector the editors open (#1128). The photo picker keeps the existing
 * behaviour by default; the document picker lets users reach recent photos before the photo
 * picker's gallery has caught up. This is an explicit preference, never an automatic fallback.
 *
 * The enum [name] is serialised verbatim into DataStore — renaming an entry needs a defensive read.
 */
enum class ImagePickerMode {
    PHOTO_PICKER,
    DOCUMENT_PICKER,
    ;

    companion object {
        val DEFAULT = PHOTO_PICKER
    }
}
