package com.thebrowser.pdf

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thebrowser.BrowserViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PdfViewerDialog(vm: BrowserViewModel, file: File) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(file) { PdfViewerSession(ctx, file) }
    DisposableEffect(file) { onDispose { session.close(); vm.closePdf(file) } }
    val ops = remember { mutableStateListOf<PdfOp>() }
    var tool by remember { mutableStateOf("view") }   // view | pen | marker | sign | text
    var zoom by remember { mutableStateOf(1f) }
    val rotations = remember { mutableStateMapOf<Int, Int>() }
    val deletes = remember { mutableStateListOf<Int>() }
    val form = remember { mutableStateMapOf<String, String>() }
    var fields by remember { mutableStateOf<List<FormField>?>(null) }
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var textAt by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    val list = rememberLazyListState()
    var status by remember { mutableStateOf<String?>(null) }

    fun savedCopy(): File = File(ctx.cacheDir, "pdf/edited-${System.currentTimeMillis()}.pdf").also { it.parentFile?.mkdirs(); PdfWriter.save(ctx, file, it, ops.toList(), form.toMap(), rotations.toMap(), deletes.toSet()) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { u -> scope.launch { status = runCatching { withContext(Dispatchers.IO) { val f = savedCopy(); ctx.contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } }; f.delete() } }.fold({ "Saved a copy. The original is unchanged." }, { "Couldn't save: ${it.message}" }) } }
    }

    Dialog(onDismissRequest = { vm.closePdf(file) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton({ vm.closePdf(file) }) { Icon(Icons.Default.Close, "Close") }
                    Text(file.name.take(24), fontSize = 13.sp)
                    listOf("view" to "👁", "pen" to "✏️", "marker" to "🖍", "sign" to "✍️", "text" to "T").forEach { (id, l) -> FilterChip(tool == id, { tool = id }, { Text(l) }) }
                    TextButton({ ops.removeLastOrNull() }, enabled = ops.isNotEmpty()) { Text("Undo") }
                    TextButton({ zoom = (zoom - 0.25f).coerceAtLeast(0.5f) }) { Text("−") }; Text("${(zoom * 100).toInt()}%", fontSize = 12.sp); TextButton({ zoom = (zoom + 0.25f).coerceAtMost(3f) }) { Text("+") }
                    TextButton({ searching = true }) { Text("Search") }
                    TextButton({ scope.launch { fields = withContext(Dispatchers.IO) { session.formFields() } } }) { Text("Forms") }
                    TextButton({ val p = list.firstVisibleItemIndex; rotations[p] = ((rotations[p] ?: 0) + 90) % 360 }) { Text("Rotate pg") }
                    TextButton({ val p = list.firstVisibleItemIndex; if (p in deletes) deletes.remove(p) else deletes.add(p) }) { Text("Delete pg") }
                    TextButton({ exporter.launch("edited-${file.nameWithoutExtension}.pdf") }) { Text("Save copy") }
                    TextButton({
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { savedCopy() }
                            (ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager).print(file.name, object : PrintDocumentAdapter() {
                                override fun onLayout(o: PrintAttributes?, n: PrintAttributes?, c: CancellationSignal?, cb: LayoutResultCallback, e: Bundle?) { cb.onLayoutFinished(PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true) }
                                override fun onWrite(p: Array<PageRange>, d: ParcelFileDescriptor, c: CancellationSignal?, cb: WriteResultCallback) { ParcelFileDescriptor.AutoCloseOutputStream(d).use { o -> f.inputStream().use { it.copyTo(o) } }; cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
                            }, null)
                        }
                    }) { Text("Print") }
                }
                status?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
                if (tool != "view") Text(when (tool) { "pen" -> "Draw on a page with your finger or stylus."; "marker" -> "Highlighter: drag over text. It is a freehand marker, not text-aware."; "sign" -> "Draw your signature where it belongs."; else -> "Tap where the text goes." } + " Edits are saved into a copy.", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val widthPx = (constraints.maxWidth * zoom).toInt()
                    val viewportWidth = maxWidth   // read here: inside the nested Box the outer scope's maxWidth is not reachable
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        LazyColumn(Modifier.width(viewportWidth * zoom), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(List(session.pageCount) { it }) { i, _ ->
                                val bmp by produceState<Bitmap?>(null, i, widthPx) { value = withContext(Dispatchers.IO) { runCatching { session.render(i, widthPx) }.getOrNull() } }
                                Box(Modifier.fillMaxWidth()) {
                                    bmp?.let { Image(it.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxWidth()) }
                                    var stroke by remember(i) { mutableStateOf<List<Offset>>(emptyList()) }
                                    Canvas(Modifier.matchParentSize().pointerInput(tool, i) {
                                        if (tool == "pen" || tool == "marker" || tool == "sign") detectDragGestures(
                                            onDragStart = { stroke = listOf(it) }, onDrag = { c, _ -> stroke = stroke + c.position },
                                            onDragEnd = {
                                                if (rotations[i] != null) status = "Draw before rotating this page." else if (stroke.size > 1) ops.add(PdfOp.Ink(i, stroke.map { it.x / size.width to it.y / size.height }, if (tool == "marker") 0xFFFFEB3B.toInt() else if (tool == "sign") 0xFF1A3FA0.toInt() else 0xFFD63031.toInt(), if (tool == "marker") 14f else 2f, tool == "marker"))
                                                stroke = emptyList()
                                            })
                                        else if (tool == "text") detectTapGestures { textAt = Triple(i, it.x / size.width, it.y / size.height) }
                                    }) {
                                        fun draw(pts: List<Offset>, c: Color, w: Float, a: Float) { if (pts.size > 1) drawPath(Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }, c.copy(alpha = a), style = Stroke(w, cap = StrokeCap.Round)) }
                                        ops.filterIsInstance<PdfOp.Ink>().filter { it.page == i }.forEach { op -> draw(op.pts.map { Offset(it.first * size.width, it.second * size.height) }, Color(op.argb), op.widthPt * size.width / 600f, if (op.highlighter) 0.35f else 1f) }
                                        draw(stroke, if (tool == "marker") Color(0xFFFFEB3B) else Color(0xFFD63031), if (tool == "marker") 14f * size.width / 600f else 3f, if (tool == "marker") 0.35f else 1f)
                                    }
                                    if (i in deletes) Surface(Modifier.align(Alignment.Center), color = Color(0xCCB3261E)) { Text(" This page will be deleted in the saved copy ", color = Color.White, fontSize = 12.sp) }
                                    Text("${i + 1}/${session.pageCount}", Modifier.align(Alignment.BottomEnd).padding(6.dp), fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    textAt?.let { (pg, x, y) ->
        var t by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { textAt = null }, title = { Text("Add text") }, text = { OutlinedTextField(t, { t = it }, singleLine = true) },
            confirmButton = { TextButton({ if (t.isNotBlank()) ops.add(PdfOp.Text(pg, x, y, t, 12f, 0xFF000000.toInt())); textAt = null }) { Text("Add") } }, dismissButton = { TextButton({ textAt = null }) { Text("Cancel") } })
    }
    if (searching) {
        var q by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { searching = false }, title = { Text("Search PDF") },
            text = { OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Text to find") }) },
            confirmButton = { TextButton({ searching = false; scope.launch { hits = withContext(Dispatchers.IO) { session.search(q) } } }) { Text("Search") } }, dismissButton = { TextButton({ searching = false }) { Text("Cancel") } })
    }
    hits?.let { h ->
        AlertDialog(onDismissRequest = { hits = null }, title = { Text(if (h.isEmpty()) "No matches (scanned pages have no text layer)" else "${h.size} match(es)") },
            text = { LazyColumn(Modifier.heightIn(max = 320.dp)) { itemsIndexed(h) { _, x -> TextButton({ scope.launch { list.scrollToItem(x.page) }; hits = null }) { Text("p.${x.page + 1}  ${x.snippet}", fontSize = 12.sp) } } } },
            confirmButton = { TextButton({ hits = null }) { Text("Close") } })
    }
    fields?.let { fs ->
        AlertDialog(onDismissRequest = { fields = null }, title = { Text(if (fs.isEmpty()) "This PDF has no fillable form" else "Fill form") },
            text = { LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { itemsIndexed(fs) { _, f ->
                if (f.kind == "check") Row(verticalAlignment = Alignment.CenterVertically) { Checkbox((form[f.name] ?: f.value) == "true", { form[f.name] = it.toString() }); Text(f.name, fontSize = 13.sp) }
                else OutlinedTextField(form[f.name] ?: f.value, { form[f.name] = it }, label = { Text(f.name) }, singleLine = true)
            } } },
            confirmButton = { TextButton({ fields = null }) { Text("Done (use Save copy)") } })
    }
}
