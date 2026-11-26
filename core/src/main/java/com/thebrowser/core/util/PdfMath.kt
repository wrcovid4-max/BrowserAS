package com.thebrowser.core.util

/** Page-coordinate helpers shared by the viewer and the saver. Screen space: origin top-left, 0..1. PDF space: origin bottom-left, points. */
object PdfMath {
    data class P(val x: Float, val y: Float)
    fun toPdf(nx: Float, ny: Float, pageW: Float, pageH: Float) = P(nx.coerceIn(0f, 1f) * pageW, (1f - ny.coerceIn(0f, 1f)) * pageH)
    fun toNorm(x: Float, y: Float, pageW: Float, pageH: Float) = P(x / pageW, 1f - y / pageH)
    fun rotate(current: Int, delta: Int) = ((current + delta) % 360 + 360) % 360
    /** Page numbers to delete, highest first, so earlier indexes stay valid. */
    fun deletionOrder(pages: Collection<Int>) = pages.distinct().sortedDescending()
}
