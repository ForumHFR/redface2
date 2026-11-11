@file:Suppress("MatchingDeclarationName") // Availability is part of the persisted choice contract.

package fr.forumhfr.redface2.core.model.editor

/** Closed ED-36 catalogue. IDs are persisted: never derive them from labels or enum names. */
enum class EditorShortcut(val id: String) {
    NONE("none"),
    BOLD("bold"), ITALIC("italic"), UNDERLINE("underline"), STRIKE("strike"),
    QUOTE("quote"), CODE("code"), FIXED("fixed"), SPOILER("spoiler"), LINK("link"),
    RED("color_red"), BLUE("color_blue"), GREEN("color_green"), ORANGE("color_orange"), GREY("color_grey"),
    UPLOAD_IMAGE("upload_image"), IMAGE_URL("image_url"), IMAGE_SELECTION("image_selection"),
    /** Retained when S2 replaces the Wiki tab with a single search field. */
    SEARCH_SMILEYS("search_smileys"),
    PERSONAL_SMILEY("personal_smiley"),
    ;

    /** Explicit surface policy; existing image capabilities alone do not implement ED-36. */
    fun isAvailableOn(surface: EditorShortcutSurface): Boolean = when (this) {
        NONE -> false
        UPLOAD_IMAGE -> surface != EditorShortcutSurface.QUICK_REPLY
        IMAGE_URL -> surface == EditorShortcutSurface.POST || surface == EditorShortcutSurface.TOPIC
        IMAGE_SELECTION -> surface == EditorShortcutSurface.PRIVATE_MESSAGE_REPLY ||
            surface == EditorShortcutSurface.PRIVATE_MESSAGE_COMPOSE
        else -> true
    }

    companion object {
        fun fromId(id: String?): EditorShortcut = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/** Includes the deferred R4 sheet so its catalogue cannot accidentally inherit upload support. */
enum class EditorShortcutSurface { POST, TOPIC, PRIVATE_MESSAGE_REPLY, PRIVATE_MESSAGE_COMPOSE, QUICK_REPLY }
