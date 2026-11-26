package com.thebrowser.core

import com.thebrowser.core.atc.AirTrafficControl
import com.thebrowser.core.atc.AtcDecision
import com.thebrowser.core.model.AtcRule
import com.thebrowser.core.model.Space
import com.thebrowser.core.util.UrlResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTests {
    private val work = Space("w", "Work", 0, "💼", "work-profile")
    private val home = Space("h", "Home", 0, "🏠", "default")

    @Test fun wildcardSubdomainDoesNotMatchApex() {
        assertTrue(AirTrafficControl.matches("*.example.com", "docs.example.com"))
        assertFalse(AirTrafficControl.matches("*.example.com", "example.com"))
    }

    @Test fun plainPatternMatchesWww() {
        assertTrue(AirTrafficControl.matches("example.com", "www.example.com"))
        assertFalse(AirTrafficControl.matches("example.com", "evil-example.com"))
    }

    @Test fun exceptionBeatsRoute() {
        val rules = listOf(
            AtcRule("1", "*.corp.com", "w"),
            AtcRule("2", "public.corp.com", "w", exception = true),
        )
        assertEquals(AtcDecision.None, AirTrafficControl.decide("https://public.corp.com/x", rules, listOf(work, home), "h"))
    }

    @Test fun differentProfileRequiresConsent() {
        val rules = listOf(AtcRule("1", "jira.corp.com", "w"))
        val d = AirTrafficControl.decide("https://jira.corp.com/a", rules, listOf(work, home), "h")
        assertTrue(d is AtcDecision.Route && d.needsProfileConsent)
    }

    @Test fun sameProfileNeedsNoConsent() {
        val rules = listOf(AtcRule("1", "news.com", "h"))
        val d = AirTrafficControl.decide("https://news.com", rules, listOf(work, home), "h")
        assertTrue(d is AtcDecision.Route && !d.needsProfileConsent)
    }

    @Test fun urlResolver() {
        assertEquals("https://example.com", UrlResolver.resolve("example.com", "duckduckgo"))
        assertEquals("https://duckduckgo.com/?q=hello+world", UrlResolver.resolve("hello world", "duckduckgo"))
        assertEquals("http://a.b", UrlResolver.resolve("http://a.b", "google"))
    }
}

class ExtensionTests {
    @org.junit.Test fun matchPatternRegex() {
        val re = Regex(com.thebrowser.core.ext.MatchPattern.toRegex("*://*.example.com/*")!!)
        org.junit.Assert.assertTrue(re.matches("https://a.example.com/x"))
        org.junit.Assert.assertTrue(re.matches("http://example.com/"))
        org.junit.Assert.assertFalse(re.matches("https://evil.com/example.com/"))
    }

    @org.junit.Test fun originRules() {
        org.junit.Assert.assertEquals(setOf("http://x.com", "https://x.com"), com.thebrowser.core.ext.MatchPattern.originRules("*://x.com/*"))
        org.junit.Assert.assertEquals(setOf("*"), com.thebrowser.core.ext.MatchPattern.originRules("<all_urls>"))
    }

    @org.junit.Test fun manifestReportsUnsupported() {
        val info = com.thebrowser.core.ext.ExtensionManifest.parse(
            """{"manifest_version":3,"name":"T","version":"1","permissions":["storage","tabs"],"background":{"service_worker":"b.js"},
               "content_scripts":[{"matches":["https://a.com/*"],"js":["c.js"]}]}""",
        )
        org.junit.Assert.assertTrue(info.unsupported.any { it.contains("background") })
        org.junit.Assert.assertTrue(info.unsupported.any { it.contains("tabs") })
        org.junit.Assert.assertEquals(1, info.contentScripts.size)
    }
}

class NetSpeedTests {
    @org.junit.Test fun mathAndFormatting() {
        org.junit.Assert.assertEquals(8.0, com.thebrowser.core.net.NetSpeed.mbps(1_000_000, 1000), 0.001)
        org.junit.Assert.assertEquals(0.0, com.thebrowser.core.net.NetSpeed.mbps(10, 0), 0.0)
        org.junit.Assert.assertEquals("250 Mbps", com.thebrowser.core.net.NetSpeed.format(250.4))
        org.junit.Assert.assertEquals("48 Mbps", com.thebrowser.core.net.NetSpeed.format(48.2))
        org.junit.Assert.assertEquals("2.5 Mbps", com.thebrowser.core.net.NetSpeed.format(2.5))
    }
    @org.junit.Test(expected = IllegalArgumentException::class) fun refusesPlainHttp() { com.thebrowser.core.net.NetSpeed.measure("http://x.test/f") }
}

class ThemeTests {
    @org.junit.Test fun presetsAreUsableAndContrastHelperWorks() {
        val g = com.thebrowser.core.model.ThemePresets.gradients
        org.junit.Assert.assertEquals(g.size, g.map { it.name }.toSet().size)
        org.junit.Assert.assertTrue(g.all { it.a != it.b })
        org.junit.Assert.assertTrue(com.thebrowser.core.model.ThemePresets.isDark(0xFF0B1030)); org.junit.Assert.assertFalse(com.thebrowser.core.model.ThemePresets.isDark(0xFFFFF8E1))
    }
    @org.junit.Test fun themeSettingsRoundTripAndDefaultsAreUnchangedLook() {
        val t = com.thebrowser.core.model.ThemeSettings(mode = "image", imageUri = "content://x", imageDim = 0.4f)
        val json = com.thebrowser.core.state.AppJson.encodeToString(com.thebrowser.core.model.ThemeSettings.serializer(), t)
        org.junit.Assert.assertEquals(t, com.thebrowser.core.state.AppJson.decodeFromString(com.thebrowser.core.model.ThemeSettings.serializer(), json))
        org.junit.Assert.assertEquals("default", com.thebrowser.core.model.ThemeSettings().mode)
    }
}

class PdfMathTests {
    @org.junit.Test fun coordinateMappingAndPageOps() {
        val p = com.thebrowser.core.util.PdfMath.toPdf(0.5f, 0.25f, 600f, 800f)
        org.junit.Assert.assertEquals(300f, p.x, 0.01f); org.junit.Assert.assertEquals(600f, p.y, 0.01f)
        val n = com.thebrowser.core.util.PdfMath.toNorm(300f, 600f, 600f, 800f)
        org.junit.Assert.assertEquals(0.5f, n.x, 0.01f); org.junit.Assert.assertEquals(0.25f, n.y, 0.01f)
        org.junit.Assert.assertEquals(270, com.thebrowser.core.util.PdfMath.rotate(0, -90)); org.junit.Assert.assertEquals(0, com.thebrowser.core.util.PdfMath.rotate(270, 90))
        org.junit.Assert.assertEquals(listOf(5, 2, 0), com.thebrowser.core.util.PdfMath.deletionOrder(listOf(0, 5, 2, 5)))
    }
}
