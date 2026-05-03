package fr.forumhfr.redface2.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AST of a HFR post body. **The JSON serialization of this hierarchy is persisted on
 * disk** in `posts.content` (Room String column, pinned by the converter in
 * `:core:database`). Every row written by every Phase 1+ build holds JSON in this
 * exact shape ; renaming a Kotlin property, moving a sealed case to a different
 * package, or letting the polymorphic discriminator drift would silently make those
 * rows undecodable on the next read.
 *
 * To make that contract explicit and resilient to a Kotlin-only refactor :
 * - every `@SerialName` below is **frozen** to the historical value — moving
 *   `PostBlock.Paragraph` to another file or package no longer changes the JSON
 *   discriminator, because the discriminator is now the explicit string here, not
 *   the FQN that kotlinx.serialization picks by default.
 * - every property name on a `data class` is also pinned via `@SerialName` so a
 *   rename in code keeps writing the same JSON key.
 *
 * If you need to evolve the on-disk shape (rename a sealed case, add a NOT-NULL
 * field), bump the Room database version and write a migration that rewrites every
 * `posts.content` row, the same way `MIGRATION_2_3` rebuilt `flag_topics`.
 *
 * The frozen contract is pinned by `PostContentSerializerTest` in `:core:database`.
 */
@Serializable
data class PostContent(
    @SerialName("blocks") val blocks: List<PostBlock>,
)

@Serializable
sealed interface PostBlock {
    @Serializable
    @SerialName("fr.forumhfr.redface2.core.model.PostBlock.Paragraph")
    data class Paragraph(
        @SerialName("inlines") val inlines: List<PostInline>,
    ) : PostBlock

    @Serializable
    @SerialName("fr.forumhfr.redface2.core.model.PostBlock.Quote")
    data class Quote(
        @SerialName("author") val author: String?,
        @SerialName("numreponse") val numreponse: Int?,
        @SerialName("page") val page: Int?,
        @SerialName("content") val content: PostContent,
    ) : PostBlock

    @Serializable
    @SerialName("fr.forumhfr.redface2.core.model.PostBlock.Spoiler")
    data class Spoiler(
        @SerialName("label") val label: String?,
        @SerialName("content") val content: PostContent,
    ) : PostBlock

    @Serializable
    @SerialName("fr.forumhfr.redface2.core.model.PostBlock.Image")
    data class Image(
        @SerialName("url") val url: String,
        @SerialName("description") val description: String?,
    ) : PostBlock
}

@Serializable
sealed interface PostInline {
    @Serializable
    @SerialName("fr.forumhfr.redface2.core.model.PostInline.Text")
    data class Text(@SerialName("value") val value: String) : PostInline

    @Serializable
    @SerialName("fr.forumhfr.redface2.core.model.PostInline.LineBreak")
    data object LineBreak : PostInline

    @Serializable
    @SerialName("fr.forumhfr.redface2.core.model.PostInline.Strong")
    data class Strong(@SerialName("children") val children: List<PostInline>) : PostInline

    @Serializable
    @SerialName("fr.forumhfr.redface2.core.model.PostInline.Emphasis")
    data class Emphasis(@SerialName("children") val children: List<PostInline>) : PostInline

    @Serializable
    @SerialName("fr.forumhfr.redface2.core.model.PostInline.Underline")
    data class Underline(@SerialName("children") val children: List<PostInline>) : PostInline

    @Serializable
    @SerialName("fr.forumhfr.redface2.core.model.PostInline.Strike")
    data class Strike(@SerialName("children") val children: List<PostInline>) : PostInline

    @Serializable
    @SerialName("fr.forumhfr.redface2.core.model.PostInline.Color")
    data class Color(
        @SerialName("colorHex") val colorHex: String,
        @SerialName("children") val children: List<PostInline>,
    ) : PostInline

    @Serializable
    @SerialName("fr.forumhfr.redface2.core.model.PostInline.Link")
    data class Link(
        @SerialName("url") val url: String,
        @SerialName("children") val children: List<PostInline>,
    ) : PostInline

    @Serializable
    @SerialName("fr.forumhfr.redface2.core.model.PostInline.InlineImage")
    data class InlineImage(
        @SerialName("url") val url: String,
        @SerialName("description") val description: String?,
    ) : PostInline

    @Serializable
    @SerialName("fr.forumhfr.redface2.core.model.PostInline.Smiley")
    data class Smiley(
        @SerialName("kind") val kind: SmileyKind,
        @SerialName("imageUrl") val imageUrl: String?,
    ) : PostInline
}

@Serializable
sealed interface SmileyKind {
    @Serializable
    @SerialName("fr.forumhfr.redface2.core.model.SmileyKind.Builtin")
    data class Builtin(@SerialName("code") val code: String) : SmileyKind

    @Serializable
    @SerialName("fr.forumhfr.redface2.core.model.SmileyKind.Perso")
    data class Perso(@SerialName("name") val name: String) : SmileyKind
}
