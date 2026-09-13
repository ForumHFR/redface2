package fr.forumhfr.redface2

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
class NetworkSecurityConfigTest {

    @Test
    fun `network security config permits cleartext with system trust anchors`() {
        val resources = RuntimeEnvironment.getApplication().resources
        val parser = resources.getXml(R.xml.network_security_config)

        parser.use {
            assertNextTag(parser, "network-security-config")
            assertNextTag(parser, "base-config")
            assertEquals("true", parser.getAttributeValue(null, "cleartextTrafficPermitted"))
            assertNextTag(parser, "trust-anchors")
            assertNextTag(parser, "certificates")
            assertEquals("system", parser.getAttributeValue(null, "src"))
        }
    }

    @Test
    fun `merged manifest references the network security config`() {
        val applicationInfo = RuntimeEnvironment.getApplication().applicationInfo
        val networkSecurityConfigRes =
            ApplicationInfo::class.java
                .getDeclaredField("networkSecurityConfigRes")
                .apply { isAccessible = true }
                .getInt(applicationInfo)

        assertEquals(R.xml.network_security_config, networkSecurityConfigRes)
    }

    private fun assertNextTag(parser: XmlPullParser, expectedName: String) {
        var eventType = parser.next()
        while (eventType != XmlPullParser.START_TAG && eventType != XmlPullParser.END_DOCUMENT) {
            eventType = parser.next()
        }

        assertEquals(XmlPullParser.START_TAG, eventType)
        assertEquals(expectedName, parser.name)
    }
}
