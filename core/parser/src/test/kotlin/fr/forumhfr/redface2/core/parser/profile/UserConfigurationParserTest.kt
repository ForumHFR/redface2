package fr.forumhfr.redface2.core.parser.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserConfigurationParserTest {
    private val parser = ProfileParser()

    @Test
    fun `XaTriX configuration preserves all twelve hardware lines in order`() {
        assertEquals(
            listOf(
                "#PC1# P8Z77-V / i7 3770K / 32GB / GTX670 2GB / mint17.1",
                "#PC2# GA-P35C-DS3R / C2D E8500 / 4GB / 4870 1GB",
                "#LAPTOP# LENOVO IDEAPAD Z570 / i7-2670QM / 6GB / GT540M",
                "#PHONE# Galaxy Nexus / A9 1.2GHZ / 16GB / Free@4€",
                "#SERVER@HOME# GA-EP35C-DS3R / C2Q Q6600 / 4GB / 2x250GB raid1 / XenServer6.2",
                "#SERVER@DC# P8H77-M / XEON E3-1225V2 / 16GB / 2x1TB raid1 / XenServer6.2 / eth0/wan 100mbps fr",
                "#PV-SERVER@DC# vCPU 1GHZ / 1GB / 10GB / debian7 / eth0/wan 100mbps ca",
                "#MODEM@BRIDGE# Technicolor TG788vn",
                "#ROUTER@HOME# Ubiquiti EdgeRouter Lite",
                "#AP@HOME# Ubiquiti UniFi AP",
                "#SWITCH@HOME# Netgear GS108Ev2",
                "#FAI@ADSL# OVH / 15Mbps -_-",
            ),
            parser.parseConfiguration(fixture("configuration_xatrix_anonymous")),
        )
    }

    @Test
    fun `Colonel MythO configuration preserves accents and the final audio line`() {
        assertEquals(
            listOf(
                "Fractal Design Torrent Nano noir vitré",
                "AMD Ryzen 5 5600X + Noctua NH-U12S",
                "ASRock Fatal1ty AB350 Gaming-ITXac",
                "DDR4 Corsair Vengeance LED 2 x 8Go 3200MHz C16",
                "ZOTAC GeForce GTX 1060 AMP! Edition 6GB",
                "SSD NVMe WD Black 500Go",
                "SSD SATA Crucial MX500 1T0",
                "HDD SATA WD Caviar Green 3To",
                "Seasonic Focus+ 550 Gold",
                "Samsung SM2232BW",
                "Iiyama ProLite E2271HDS",
                "Sure TDA7492 2x50W Class D Audio Amplifier + JBL",
            ),
            parser.parseConfiguration(fixture("configuration_colonel_anonymous")),
        )
    }

    @Test
    fun `existing user with an empty configuration returns no lines`() {
        assertEquals(emptyList<String>(), parser.parseConfiguration(fixture("configuration_ezzz_anonymous")))
    }

    @Test
    fun `unknown pseudo has the same empty result as an existing empty user`() {
        assertEquals(emptyList<String>(), parser.parseConfiguration(fixture("configuration_unknown_anonymous")))
    }

    @Test
    fun `entities whitespace and blank lines are normalized without losing br boundaries`() {
        // Derive a whitespace/entity variant from the captured cell, without inventing a page fixture.
        val html = fixture("configuration_xatrix_anonymous")
            .replace("#PC1#", " \t#PC1# &amp; &lt;PC&gt;")
            .replace("<br />", "<br />&nbsp;<br />")
        val lines = parser.parseConfiguration(html)

        assertEquals(12, lines.size)
        assertEquals("#PC1# & <PC> P8Z77-V / i7 3770K / 32GB / GTX670 2GB / mint17.1", lines.first())
        assertTrue(lines.none { it.isBlank() || it.contains('\u00a0') || it != it.trim() })
    }

    @Test
    fun `missing configuration heading is a failure even if the table exists`() {
        assertInvalid(fixture("configuration_ezzz_anonymous").replace("h4", "div"))
    }

    @Test
    fun `unrelated heading is a failure even if the table exists`() {
        assertInvalid(fixture("configuration_ezzz_anonymous").replace(
            "\"Configuration matérielle\" d'un membre", "Informations sur",
        ))
    }

    @Test
    fun `missing table is a failure even if the configuration heading exists`() {
        assertInvalid(fixture("configuration_ezzz_anonymous").substringBefore("<table"))
    }

    @Test
    fun `missing label content row or cell are failures rather than empty configurations`() {
        val html = fixture("configuration_ezzz_anonymous")
        assertInvalid(html.replace("cBackTab2", "unrecognized"))
        assertInvalid(html.replace("<td>&nbsp;</td>", ""))
        assertInvalid(html.replace("<tr class=\"profil\">\n        <td>&nbsp;</td>\n    </tr>", ""))
    }

    @Test
    fun `a profile page is not mistaken for a configuration page`() {
        assertInvalid(fixture("profile_xatrix_authenticated"))
    }

    private fun assertInvalid(html: String) {
        val error = runCatching { parser.parseConfiguration(html) }.exceptionOrNull()
        assertTrue("Unexpected structure must fail, got $error", error is IllegalArgumentException)
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/profile/$name.html")).readText()
}
