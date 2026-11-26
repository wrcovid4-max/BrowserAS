package com.thebrowser.core.model

/** Built-in looks. Colours are ARGB longs so Android and Apple share them. */
object ThemePresets {
    data class Gradient(val name: String, val a: Long, val b: Long)
    val gradients = listOf(
        Gradient("Aurora", 0xFF6C5CE7, 0xFF00B894), Gradient("Sunset", 0xFFFF7675, 0xFFFDCB6E), Gradient("Ocean", 0xFF0984E3, 0xFF74B9FF),
        Gradient("Forest", 0xFF00B894, 0xFF55EFC4), Gradient("Berry", 0xFFE84393, 0xFF6C5CE7), Gradient("Slate", 0xFF2D3436, 0xFF636E72),
        Gradient("Peach", 0xFFFAB1A0, 0xFFFFEAA7), Gradient("Midnight", 0xFF0B1030, 0xFF16235A),
    )
    val colours = listOf(0xFFF5F5F8, 0xFFFFF8E1, 0xFFE3F2FD, 0xFFE8F5E9, 0xFFFCE4EC, 0xFF1B1B1F, 0xFF0B1030, 0xFF263238)
    val accents = listOf(0xFF6C5CE7, 0xFF0984E3, 0xFF00B894, 0xFFE17055, 0xFFD63031, 0xFFE84393, 0xFFFDCB6E, 0xFF636E72)

    /** True when white text/icons read better than dark ones on [argb]. */
    fun isDark(argb: Long): Boolean {
        val r = (argb shr 16 and 0xFF) / 255.0; val g = (argb shr 8 and 0xFF) / 255.0; val b = (argb and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b < 0.5
    }
}
