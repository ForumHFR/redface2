package fr.forumhfr.redface2.core.ui.editor

/** Keeps the selector decision platform-free so all three paths can be checked on the JVM (#1128). */
sealed interface ImagePickRequest {
    data object PhotoPicker : ImagePickRequest

    data class GetContent(val mimeType: String) : ImagePickRequest

    data class Documents(val mimeTypes: List<String>) : ImagePickRequest
}
