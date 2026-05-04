package fr.forumhfr.redface2.core.database.serialization

import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the on-disk JSON representation of [PostContent]. The AST is persisted in
 * `posts.content` via Room TypeConverter — every `posts` row written by every build
 * since Phase 1A holds JSON in this exact shape. Renaming a Kotlin property,
 * moving a sealed case to a different package, or letting the polymorphic
 * discriminator drift would silently make those rows undecodable on the next read,
 * **without crashing the migration** (the column survives untouched). The only
 * failure mode is a `SerializationException` thrown at DAO read time.
 *
 * The bug pattern is exactly the one that hit `flag_topics` at the v2 → v3 boundary,
 * but routed through a JSON column instead of a SQL schema. There is no equivalent
 * of `MigrationTestHelper` for JSON columns, hence this test : a frozen fixture
 * that **must** keep decoding to the same logical AST. If you rename a property,
 * either bump the AST and write a migration that rewrites every `posts.content`,
 * or stop and put the rename behind a `@SerialName` so the JSON output is unchanged.
 */
class PostContentSerializerTest {

    @Test
    fun `every block and inline kind round-trips through encode-decode`() {
        val sample = samplePostContent()

        val encoded = PostContentSerializer.encode(sample)
        val decoded = PostContentSerializer.decode(encoded)
        assertEquals(sample, decoded)
    }

    /**
     * Frozen fixture : a payload identical to what a real `posts.content` row holds
     * today. If this test fails, your change broke the on-disk JSON format. Either
     * revert the rename or add a Room migration that rewrites every row in `posts`.
     *
     * The discriminator is `"type"` (set in [PostContentSerializer]). The values
     * below are the **fully-qualified Kotlin names** of the sealed cases, which is
     * what kotlinx.serialization emits when no `@SerialName` is declared on a
     * sealed subclass. Any code-side rename / package move must keep the JSON
     * value here — either via `@SerialName(this exact string)` on the sealed case,
     * or via a write-side migration.
     */
    @Test
    fun `frozen fixture decodes to the expected AST and re-encodes byte-identical`() {
        val frozen = minifiedJson(
            """
            {
              "blocks": [
                {
                  "type": "fr.forumhfr.redface2.core.model.PostBlock.Paragraph",
                  "inlines": [
                    {
                      "type": "fr.forumhfr.redface2.core.model.PostInline.Text",
                      "value": "hello"
                    },
                    {
                      "type": "fr.forumhfr.redface2.core.model.PostInline.LineBreak"
                    },
                    {
                      "type": "fr.forumhfr.redface2.core.model.PostInline.Strong",
                      "children": [
                        {
                          "type": "fr.forumhfr.redface2.core.model.PostInline.Text",
                          "value": "bold"
                        }
                      ]
                    },
                    {
                      "type": "fr.forumhfr.redface2.core.model.PostInline.Emphasis",
                      "children": [
                        {
                          "type": "fr.forumhfr.redface2.core.model.PostInline.Text",
                          "value": "em"
                        }
                      ]
                    },
                    {
                      "type": "fr.forumhfr.redface2.core.model.PostInline.Underline",
                      "children": [
                        {
                          "type": "fr.forumhfr.redface2.core.model.PostInline.Text",
                          "value": "under"
                        }
                      ]
                    },
                    {
                      "type": "fr.forumhfr.redface2.core.model.PostInline.Strike",
                      "children": [
                        {
                          "type": "fr.forumhfr.redface2.core.model.PostInline.Text",
                          "value": "strike"
                        }
                      ]
                    },
                    {
                      "type": "fr.forumhfr.redface2.core.model.PostInline.Color",
                      "colorHex": "#FF0000",
                      "children": [
                        {
                          "type": "fr.forumhfr.redface2.core.model.PostInline.Text",
                          "value": "red"
                        }
                      ]
                    },
                    {
                      "type": "fr.forumhfr.redface2.core.model.PostInline.Link",
                      "url": "https://example.invalid",
                      "children": [
                        {
                          "type": "fr.forumhfr.redface2.core.model.PostInline.Text",
                          "value": "link"
                        }
                      ]
                    },
                    {
                      "type": "fr.forumhfr.redface2.core.model.PostInline.InlineImage",
                      "url": "https://example.invalid/i.png",
                      "description": "alt"
                    },
                    {
                      "type": "fr.forumhfr.redface2.core.model.PostInline.Smiley",
                      "kind": {
                        "type": "fr.forumhfr.redface2.core.model.SmileyKind.Builtin",
                        "code": ":jap:"
                      },
                      "imageUrl": "https://forum-images.hardware.fr/icones/smilies/jap.gif"
                    },
                    {
                      "type": "fr.forumhfr.redface2.core.model.PostInline.Smiley",
                      "kind": {
                        "type": "fr.forumhfr.redface2.core.model.SmileyKind.Perso",
                        "name": "ouich"
                      },
                      "imageUrl": "https://forum-images.hardware.fr/images/perso/ouich.gif"
                    }
                  ]
                },
                {
                  "type": "fr.forumhfr.redface2.core.model.PostBlock.Quote",
                  "author": "alice",
                  "numreponse": 100,
                  "page": 2,
                  "content": {
                    "blocks": [
                      {
                        "type": "fr.forumhfr.redface2.core.model.PostBlock.Paragraph",
                        "inlines": [
                          {
                            "type": "fr.forumhfr.redface2.core.model.PostInline.Text",
                            "value": "quoted"
                          }
                        ]
                      }
                    ]
                  }
                },
                {
                  "type": "fr.forumhfr.redface2.core.model.PostBlock.Spoiler",
                  "label": "secret",
                  "content": {
                    "blocks": [
                      {
                        "type": "fr.forumhfr.redface2.core.model.PostBlock.Paragraph",
                        "inlines": [
                          {
                            "type": "fr.forumhfr.redface2.core.model.PostInline.Text",
                            "value": "hidden"
                          }
                        ]
                      }
                    ]
                  }
                },
                {
                  "type": "fr.forumhfr.redface2.core.model.PostBlock.Image",
                  "url": "https://example.invalid/i.png",
                  "description": "block alt"
                },
                {
                  "type": "fr.forumhfr.redface2.core.model.PostBlock.Fixed",
                  "text": "indented line\nsecond line"
                },
                {
                  "type": "fr.forumhfr.redface2.core.model.PostBlock.CodeBlock",
                  "text": "println(\"hi\")",
                  "language": "kotlin"
                },
                {
                  "type": "fr.forumhfr.redface2.core.model.PostBlock.CodeBlock",
                  "text": "no lang here",
                  "language": null
                }
              ]
            }
            """,
        )

        val decoded = PostContentSerializer.decode(frozen)

        assertEquals(samplePostContent(), decoded)
        assertEquals(frozen, PostContentSerializer.encode(decoded))
    }

    @Test
    fun `unknown fields in a row are tolerated by the lenient json config`() {
        val payloadWithFutureField = """
            {
              "blocks": [
                {
                  "type": "fr.forumhfr.redface2.core.model.PostBlock.Paragraph",
                  "inlines": [
                    {
                      "type": "fr.forumhfr.redface2.core.model.PostInline.Text",
                      "value": "hello",
                      "futureField": 42
                    }
                  ],
                  "anotherFutureField": "ignored"
                }
              ],
              "rootFutureField": true
            }
        """.trimIndent()

        val decoded = PostContentSerializer.decode(payloadWithFutureField)

        assertEquals(
            PostContent(
                blocks = listOf(
                    PostBlock.Paragraph(inlines = listOf(PostInline.Text("hello"))),
                ),
            ),
            decoded,
        )
    }

    private fun samplePostContent(): PostContent = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(
                inlines = listOf(
                    PostInline.Text("hello"),
                    PostInline.LineBreak,
                    PostInline.Strong(children = listOf(PostInline.Text("bold"))),
                    PostInline.Emphasis(children = listOf(PostInline.Text("em"))),
                    PostInline.Underline(children = listOf(PostInline.Text("under"))),
                    PostInline.Strike(children = listOf(PostInline.Text("strike"))),
                    PostInline.Color(
                        colorHex = "#FF0000",
                        children = listOf(PostInline.Text("red")),
                    ),
                    PostInline.Link(
                        url = "https://example.invalid",
                        children = listOf(PostInline.Text("link")),
                    ),
                    PostInline.InlineImage(
                        url = "https://example.invalid/i.png",
                        description = "alt",
                    ),
                    PostInline.Smiley(
                        kind = SmileyKind.Builtin(code = ":jap:"),
                        imageUrl = "https://forum-images.hardware.fr/icones/smilies/jap.gif",
                    ),
                    PostInline.Smiley(
                        kind = SmileyKind.Perso(name = "ouich"),
                        imageUrl = "https://forum-images.hardware.fr/images/perso/ouich.gif",
                    ),
                ),
            ),
            PostBlock.Quote(
                author = "alice",
                numreponse = 100,
                page = 2,
                content = PostContent(
                    blocks = listOf(
                        PostBlock.Paragraph(inlines = listOf(PostInline.Text("quoted"))),
                    ),
                ),
            ),
            PostBlock.Spoiler(
                label = "secret",
                content = PostContent(
                    blocks = listOf(
                        PostBlock.Paragraph(inlines = listOf(PostInline.Text("hidden"))),
                    ),
                ),
            ),
            PostBlock.Image(
                url = "https://example.invalid/i.png",
                description = "block alt",
            ),
            PostBlock.Fixed(text = "indented line\nsecond line"),
            PostBlock.CodeBlock(text = "println(\"hi\")", language = "kotlin"),
            PostBlock.CodeBlock(text = "no lang here", language = null),
        ),
    )

    private fun minifiedJson(value: String): String = Json.parseToJsonElement(value.trimIndent()).toString()
}
