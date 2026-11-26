package com.thebrowser.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.thebrowser.core.util.PdfMath
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

sealed class PdfOp {
    /** [pts] are normalised (0..1, top-left origin) so they survive zooming. */
    data class Ink(val page: Int, val pts: List<Pair<Float, Float>>, val argb: Int, val widthPt: Float, val highlighter: Boolean) : PdfOp()
    data class Text(val page: Int, val x: Float, val y: Float, val text: String, val sizePt: Float, val argb: Int) : PdfOp()
}

data class FormField(val name: String, val kind: String, val value: String, val options: List<String> = emptyList())
data class SearchHit(val page: Int, val snippet: String)

/** Viewing uses the platform PdfRenderer (pages as bitmaps). Edits are baked into a SAVED COPY with PDFBox; the original is never changed. */
class PdfViewerSession(ctx: Context, val file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = Mutex()
    val pageCount = renderer.pageCount
    init { PDFBoxResourceLoader.init(ctx.applicationContext) }

    suspend fun size(i: Int): Pair<Int, Int> = lock.withLock { renderer.openPage(i).use { it.width to it.height } }

    suspend fun render(i: Int, widthPx: Int): Bitmap = lock.withLock {
        renderer.openPage(i).use { p ->
            val h = (widthPx.toFloat() / p.width * p.height).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE); p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        }
    }

    fun close() { runCatching { renderer.close() }; runCatching { pfd.close() } }

    /** Text search (needs a text layer; scanned pages have none). */
    fun search(query: String): List<SearchHit> {
        if (query.length < 2) return emptyList()
        val out = mutableListOf<SearchHit>()
        PDDocument.load(file).use { doc ->
            val st = PDFTextStripper()
            for (p in 1..doc.numberOfPages) {
                st.startPage = p; st.endPage = p
                val t = st.getText(doc).replace(Regex("\\s+"), " ")
                var i = t.indexOf(query, ignoreCase = true)
                while (i >= 0 && out.size < 100) { out += SearchHit(p - 1, "…" + t.substring((i - 30).coerceAtLeast(0), (i + query.length + 40).coerceAtMost(t.length)) + "…"); i = t.indexOf(query, i + query.length, ignoreCase = true) }
            }
        }
        return out
    }

    fun formFields(): List<FormField> = PDDocument.load(file).use { doc ->
        doc.documentCatalog.acroForm?.fieldTree?.mapNotNull { f ->
            when (f) {
                is PDTextField -> FormField(f.fullyQualifiedName, "text", f.value.orEmpty())
                is PDCheckBox -> FormField(f.fullyQualifiedName, "check", if (f.isChecked) "true" else "false")
                is PDComboBox -> FormField(f.fullyQualifiedName, "choice", f.value.firstOrNull().orEmpty(), f.options)
                else -> null
            }
        }.orEmpty()
    }
}

object PdfWriter {
    /** Applies edits to a copy at [dest]. [rotations] = page → degrees to add; [deletes] = page indexes to remove (applied last). */
    fun save(ctx: Context, src: File, dest: File, ops: List<PdfOp>, form: Map<String, String>, rotations: Map<Int, Int>, deletes: Set<Int>) {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        PDDocument.load(src).use { doc ->
            doc.documentCatalog.acroForm?.let { af -> form.forEach { (k, v) -> runCatching { af.getField(k)?.let { f -> if (f is PDCheckBox) { if (v == "true") f.check() else f.unCheck() } else f.setValue(v) } } } }
            ops.groupBy { it.page }.forEach { (pi, list) ->
                val page = doc.getPage(pi); val box = page.mediaBox
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    list.forEach { op ->
                        when (op) {
                            is PdfOp.Ink -> if (op.pts.size > 1) {
                                cs.saveGraphicsState()
                                if (op.highlighter) cs.setGraphicsStateParameters(PDExtendedGraphicsState().also { it.strokingAlphaConstant = 0.35f })
                                cs.setStrokingColor(Color.red(op.argb), Color.green(op.argb), Color.blue(op.argb))
                                cs.setLineWidth(op.widthPt); cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
                                val f = PdfMath.toPdf(op.pts[0].first, op.pts[0].second, box.width, box.height); cs.moveTo(f.x + box.lowerLeftX, f.y + box.lowerLeftY)
                                op.pts.drop(1).forEach { (nx, ny) -> val q = PdfMath.toPdf(nx, ny, box.width, box.height); cs.lineTo(q.x + box.lowerLeftX, q.y + box.lowerLeftY) }
                                cs.stroke(); cs.restoreGraphicsState()
                            }
                            is PdfOp.Text -> {
                                val p = PdfMath.toPdf(op.x, op.y, box.width, box.height)
                                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, op.sizePt); cs.setNonStrokingColor(Color.red(op.argb), Color.green(op.argb), Color.blue(op.argb))
                                cs.newLineAtOffset(p.x + box.lowerLeftX, p.y + box.lowerLeftY - op.sizePt); cs.showText(op.text.filter { it.code in 32..255 }); cs.endText()
                            }
                        }
                    }
                }
            }
            rotations.forEach { (i, d) -> doc.getPage(i).rotation = PdfMath.rotate(doc.getPage(i).rotation, d) }
            PdfMath.deletionOrder(deletes).forEach { if (doc.numberOfPages > 1) doc.removePage(it) }
            doc.save(dest)
        }
    }
}
