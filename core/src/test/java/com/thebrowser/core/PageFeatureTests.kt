package com.thebrowser.core

import com.thebrowser.core.page.DistractionJs
import com.thebrowser.core.page.TabMemoryJs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFeatureTests {
    @Test fun keepsPlainSelectors() {
        val ok = listOf("#cookie-banner", "div.modal > p:nth-of-type(2)", "body > div:nth-of-type(3) > section.promo", "#a\\:b")
        assertEquals(ok, DistractionJs.safe(ok))
    }
    @Test fun dropsSelectorsThatCouldInjectCss() {
        assertTrue(DistractionJs.safe(listOf("a{}b", "x;y", "@import url(x)", "p<script>", "ok\nnewline", "")).isEmpty())
    }
    @Test fun dropsDuplicatesAndOverlong() {
        assertEquals(listOf("#a"), DistractionJs.safe(listOf("#a", "#a", "x".repeat(601))))
    }
    @Test fun formatsMemory() {
        assertEquals("512 KB", TabMemoryJs.format(512L * 1024)); assertEquals("34.0 MB", TabMemoryJs.format(34L * 1024 * 1024)); assertEquals("1.5 GB", TabMemoryJs.format(1536L * 1024 * 1024))
    }
}
