package fr.forumhfr.redface2

import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon
import fr.forumhfr.redface2.feature.settings.launcherAliasFor
import fr.forumhfr.redface2.navigation.appLauncherIconResource
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Source-manifest contract, independent of a running launcher or a particular application-id suffix. */
class AppLauncherManifestTest {
    private val root: Path = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { it.resolve("settings.gradle.kts").exists() }

    @Test
    fun `eight launcher aliases remain declared with only Classic enabled by default`() {
        val manifest = xml("app/src/main/AndroidManifest.xml")
        val aliases = manifest.getElementsByTagName("activity-alias")
        val elements = (0 until aliases.length).map { aliases.item(it) as Element }

        assertEquals(8, elements.size)
        assertEquals(
            AppLauncherIcon.entries.map { launcherAliasFor(it).substringAfterLast('.') }.toSet(),
            elements.map { it.android("name").removePrefix(".") }.toSet(),
        )
        assertEquals(listOf(".LauncherClassic"), elements.filter { it.android("enabled") == "true" }.map {
            it.android("name")
        })
        elements.forEach { alias ->
            assertEquals(if (alias.android("name") == ".LauncherClassic") "true" else "false", alias.android("enabled"))
            assertEquals(".MainActivity", alias.android("targetActivity"))
            assertEquals("true", alias.android("exported"))
            assertEquals("android.intent.action.MAIN", alias.child("action").android("name"))
            assertEquals("android.intent.category.LAUNCHER", alias.child("category").android("name"))
        }
        val rf1 = elements.single { it.android("name") == ".LauncherRf1" }
        assertEquals("@mipmap/ic_launcher_rf1", rf1.android("icon"))
        assertEquals("@mipmap/ic_launcher_rf1_round", rf1.android("roundIcon"))
        val activity = manifest.child("activity")
        assertEquals(".MainActivity", activity.android("name"))
        assertEquals("android.intent.action.VIEW", activity.child("action").android("name"))
    }

    @Test
    fun `every alias has its own existing adaptive resources and layers`() {
        val aliases = xml("app/src/main/AndroidManifest.xml").getElementsByTagName("activity-alias")
        val expectedNames = mapOf(
            ".LauncherClassic" to "ic_launcher",
            ".LauncherRf1" to "ic_launcher_rf1",
            ".LauncherMonogram" to "ic_launcher_monogram",
            ".LauncherBubbles" to "ic_launcher_bubbles",
            ".LauncherChip" to "ic_launcher_chip",
            ".LauncherDark" to "ic_launcher_dark",
            ".LauncherRose" to "ic_launcher_rose",
            ".LauncherRed" to "ic_launcher_red",
        )
        (0 until aliases.length).map { aliases.item(it) as Element }.forEach { alias ->
            val name = expectedNames.getValue(alias.android("name"))
            assertEquals("@mipmap/$name", alias.android("icon"))
            assertEquals("@mipmap/${name}_round", alias.android("roundIcon"))
            listOf(name, "${name}_round").forEach { resource ->
                val icon = xml("app/src/main/res/mipmap-anydpi-v26/$resource.xml")
                assertEquals("adaptive-icon", icon.tagName)
                val layers = icon.getElementsByTagName("*")
                (0 until layers.length).map { layers.item(it) as Element }.forEach { layer ->
                    val reference = layer.android("drawable")
                    assertTrue("Missing resource $reference in $resource", resourceExists(reference))
                }
            }
        }
    }

    @Test
    fun `gallery previews use the five corresponding launcher resources`() {
        assertEquals(R.mipmap.ic_launcher, appLauncherIconResource(AppLauncherIcon.CLASSIC))
        assertEquals(R.mipmap.ic_launcher_rf1, appLauncherIconResource(AppLauncherIcon.RF1))
        assertEquals(R.mipmap.ic_launcher_monogram, appLauncherIconResource(AppLauncherIcon.MONOGRAM))
        assertEquals(R.mipmap.ic_launcher_bubbles, appLauncherIconResource(AppLauncherIcon.BUBBLES))
        assertEquals(R.mipmap.ic_launcher_chip, appLauncherIconResource(AppLauncherIcon.CHIP))
    }

    @Test
    fun `restart activity has an isolated process and task without launcher or recents exposure`() {
        val activities = xml("app/src/main/AndroidManifest.xml").getElementsByTagName("activity")
        val restart = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.android("name") == ".LauncherIconRestartActivity" }

        assertEquals(":launcherIconRestart", restart.android("process"))
        assertTrue(restart.hasAttributeNS("http://schemas.android.com/apk/res/android", "taskAffinity"))
        assertEquals("", restart.android("taskAffinity"))
        assertEquals("false", restart.android("exported"))
        assertEquals("true", restart.android("excludeFromRecents"))
        assertEquals("singleInstance", restart.android("launchMode"))
        assertEquals("@android:style/Theme.Translucent.NoTitleBar", restart.android("theme"))
        assertEquals(0, restart.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun `RF1 adaptive resources include the original foreground and its monochrome layer`() {
        listOf("ic_launcher_rf1", "ic_launcher_rf1_round").forEach { name ->
            val icon = xml("app/src/main/res/mipmap-anydpi-v26/$name.xml")
            assertEquals("adaptive-icon", icon.tagName)
            assertEquals("@mipmap/ic_launcher_rf1_background", icon.child("background").android("drawable"))
            assertEquals("@mipmap/ic_launcher_rf1_foreground", icon.child("foreground").android("drawable"))
            assertEquals("@mipmap/ic_launcher_rf1_foreground", icon.child("monochrome").android("drawable"))
        }
        listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi").forEach { density ->
            assertTrue(root.resolve("app/src/main/res/mipmap-$density/ic_launcher_rf1_foreground.png").exists())
            assertTrue(root.resolve("app/src/main/res/mipmap-$density/ic_launcher_rf1_background.png").exists())
        }
    }

    private fun resourceExists(reference: String): Boolean {
        val type = reference.removePrefix("@").substringBefore('/')
        val name = reference.substringAfter('/')
        return root.resolve("app/src/main/res").toFile().listFiles().orEmpty()
            .filter { it.isDirectory && (it.name == type || it.name.startsWith("$type-")) }
            .any { it.resolve("$name.xml").exists() || it.resolve("$name.png").exists() }
    }

    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(root.resolve(path).toFile()).documentElement

    private fun Element.android(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
    private fun Element.child(name: String): Element = getElementsByTagName(name).item(0) as Element
}
