package fr.forumhfr.redface2.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PostContent(
    val blocks: List<PostBlock>,
)

@Serializable
sealed interface PostBlock {
    @Serializable
    data class Paragraph(val inlines: List<PostInline>) : PostBlock

    @Serializable
    data class Quote(
        val author: String?,
        val numreponse: Int?,
        val page: Int?,
        val content: PostContent,
    ) : PostBlock

    @Serializable
    data class Spoiler(val label: String?, val content: PostContent) : PostBlock

    @Serializable
    data class Image(val url: String, val description: String?) : PostBlock
}

@Serializable
sealed interface PostInline {
    @Serializable
    data class Text(val value: String) : PostInline

    @Serializable
    data object LineBreak : PostInline

    @Serializable
    data class Strong(val children: List<PostInline>) : PostInline

    @Serializable
    data class Emphasis(val children: List<PostInline>) : PostInline

    @Serializable
    data class Underline(val children: List<PostInline>) : PostInline

    @Serializable
    data class Strike(val children: List<PostInline>) : PostInline

    @Serializable
    data class Color(val colorHex: String, val children: List<PostInline>) : PostInline

    @Serializable
    data class Link(val url: String, val children: List<PostInline>) : PostInline

    @Serializable
    data class InlineImage(val url: String, val description: String?) : PostInline

    @Serializable
    data class Smiley(val kind: SmileyKind, val imageUrl: String?) : PostInline
}

@Serializable
sealed interface SmileyKind {
    @Serializable
    data class Builtin(val code: String) : SmileyKind

    @Serializable
    data class Perso(val name: String) : SmileyKind
}
