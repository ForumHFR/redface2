package fr.forumhfr.redface2

import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Source-resource contracts for the original vector drawings, without a launcher or emulator. */
class AppLauncherIconResourcesTest {
    private val root: Path = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { it.resolve("settings.gradle.kts").exists() }
    private val drawings = listOf("monogram", "bubbles", "chip")
    private val layers = listOf("background", "foreground", "monochrome")

    @Test
    fun `all nine original layers are 108dp path vectors without gradients or text`() {
        drawings.forEach { drawing ->
            layers.forEach { layer ->
                assertPathVector("drawable/ic_launcher_${drawing}_$layer.xml")
            }
        }
    }

    @Test
    fun `original backgrounds cover the full canvas with the specified flat colors`() {
        mapOf("monogram" to "#D32F2F", "bubbles" to "#FAFAFA", "chip" to "#263238").forEach { (drawing, color) ->
            val background = xml("drawable/ic_launcher_${drawing}_background.xml")
            val paths = background.getElementsByTagName("path")
            assertEquals(1, paths.length)
            val path = paths.item(0) as Element
            assertEquals(color, path.android("fillColor"))
            assertEquals("M0,0H108V108H0Z", path.android("pathData"))
        }
    }

    @Test
    fun `normal and round adaptive icons reference all three existing vector layers`() {
        drawings.forEach { drawing ->
            listOf("", "_round").forEach { suffix ->
                val icon = xml("mipmap-anydpi-v26/ic_launcher_$drawing$suffix.xml")
                assertEquals("adaptive-icon", icon.tagName)
                assertEquals(3, icon.getElementsByTagName("*").length)
                layers.forEach { layer ->
                    val elements = icon.getElementsByTagName(layer)
                    assertEquals(1, elements.length)
                    val reference = (elements.item(0) as Element).android("drawable")
                    assertEquals("@drawable/ic_launcher_${drawing}_$layer", reference)
                    assertPathVector("${reference.removePrefix("@")}.xml")
                }
            }
        }
    }

    @Test
    fun `Classic normal and round icons share a flat vector monochrome flag`() {
        listOf("", "_round").forEach { suffix ->
            val icon = xml("mipmap-anydpi-v26/ic_launcher$suffix.xml")
            val monochrome = icon.getElementsByTagName("monochrome")
            assertEquals(1, monochrome.length)
            assertEquals("@drawable/ic_launcher_monochrome", (monochrome.item(0) as Element).android("drawable"))
        }
        assertPathVector("drawable/ic_launcher_monochrome.xml")
    }

    private fun assertPathVector(path: String) {
        val vector = xml(path)
        assertEquals(path, "vector", vector.tagName)
        listOf("width", "height").forEach { assertEquals(path, "108dp", vector.android(it)) }
        listOf("viewportWidth", "viewportHeight").forEach { assertEquals(path, "108", vector.android(it)) }
        assertEquals(path, 0, vector.getElementsByTagName("gradient").length)
        assertEquals(path, 0, vector.getElementsByTagName("text").length)
        assertTrue(path, vector.textContent.isBlank())
        val elements = vector.getElementsByTagName("*")
        assertTrue(path, elements.length > 0)
        (0 until elements.length).map { elements.item(it) as Element }.forEach { element ->
            assertEquals(path, "path", element.tagName)
            assertTrue(path, element.android("pathData").isNotBlank())
        }
    }

    private fun xml(path: String): Element {
        val resource = root.resolve("app/src/main/res/$path")
        assertTrue("Missing resource $path", resource.exists())
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(resource.toFile()).documentElement
    }

    private fun Element.android(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
