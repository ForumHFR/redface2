package fr.forumhfr.redface2.core.model

data class PostContent(
    val blocks: List<PostBlock>,
)

sealed interface PostBlock {
    data class Paragraph(val inlines: List<PostInline>) : PostBlock

    data class Quote(
        val author: String?,
        val numreponse: Int?,
        val page: Int?,
        val content: PostContent,
    ) : PostBlock

    data class Spoiler(val label: String?, val content: PostContent) : PostBlock

    data class CodeBlock(val text: String) : PostBlock

    data class Image(val url: String, val description: String?) : PostBlock
}

sealed interface PostInline {
    data class Text(val value: String) : PostInline

    data object LineBreak : PostInline

    data class Strong(val children: List<PostInline>) : PostInline

    data class Emphasis(val children: List<PostInline>) : PostInline

    data class Underline(val children: List<PostInline>) : PostInline

    data class Strike(val children: List<PostInline>) : PostInline

    data class Color(val colorHex: String, val children: List<PostInline>) : PostInline

    data class Link(val url: String, val children: List<PostInline>) : PostInline

    data class InlineImage(val url: String, val description: String?) : PostInline

    data class Smiley(val kind: SmileyKind, val imageUrl: String?) : PostInline
}

sealed interface SmileyKind {
    data class Builtin(val code: String) : SmileyKind

    data class Perso(val name: String) : SmileyKind
}
