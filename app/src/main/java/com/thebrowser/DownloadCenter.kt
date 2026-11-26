package com.thebrowser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.compose.runtime.mutableStateListOf
import androidx.core.net.toUri
import com.thebrowser.core.model.DownloadItem
import com.thebrowser.core.state.StateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * In-app download manager with real pause / resume (HTTP Range) and per-download progress.
 * Data is downloaded to app storage first, then copied to Downloads (MediaStore) or to the
 * folder the user chose (Storage Access Framework tree).
 * Limits: http(s) only (no blob:/data: URLs), and servers that ignore Range restart from zero.
 */
class DownloadCenter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: StateStore,
    private val cookies: (profileId: String, url: String) -> String?,
    private val treeUri: () -> String?,
) {
    val items = mutableStateListOf<DownloadItem>().apply {
        // A download that was running when the app died comes back paused, ready to resume.
        addAll(store.loadDownloads().map { if (it.status == "running") it.copy(status = "paused") else it })
    }
    private val jobs = HashMap<String, Job>()
    private val dir = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }

    private fun part(id: String) = File(dir, "$id.part")

    private fun set(id: String, f: (DownloadItem) -> DownloadItem, persist: Boolean = true) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = f(items[i])
        if (persist) store.saveDownloads(items.toList())
    }

    fun start(url: String, ua: String, contentDisposition: String?, mime: String?, profileId: String, isPrivate: Boolean = false): Boolean {
        if (!url.startsWith("http")) return false
        val name = URLUtil.guessFileName(url, contentDisposition, mime)
        val item = DownloadItem(UUID.randomUUID().toString(), url, name, mime, profileId, ua, "running", time = System.currentTimeMillis(), isPrivate = isPrivate)
        items.add(0, item)
        store.saveDownloads(items.toList())
        run(item.id)
        return true
    }

    /** Removes every private download from the list (files already saved to Downloads are the user's). */
    fun clearPrivate() { jobs.keys.toList().forEach { id -> if (items.firstOrNull { it.id == id }?.isPrivate == true) jobs.remove(id)?.cancel() }; items.removeAll { it.isPrivate }; store.saveDownloads(items.toList()) }

    fun pause(id: String) { jobs.remove(id)?.cancel(); set(id, { it.copy(status = "paused") }) }
    fun resume(id: String) { set(id, { it.copy(status = "running", error = null) }); run(id) }
    fun cancel(id: String) { jobs.remove(id)?.cancel(); part(id).delete(); set(id, { it.copy(status = "cancelled") }) }
    fun remove(id: String) { jobs.remove(id)?.cancel(); part(id).delete(); items.removeAll { it.id == id }; store.saveDownloads(items.toList()) }

    private fun run(id: String) {
        jobs[id]?.cancel()
        jobs[id] = scope.launch(Dispatchers.IO) {
            val item = items.firstOrNull { it.id == id } ?: return@launch
            val file = part(id)
            var conn: HttpURLConnection? = null
            try {
                var offset = if (file.exists()) file.length() else 0L
                conn = (URL(item.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000
                    setRequestProperty("User-Agent", item.userAgent)
                    cookies(item.profileId, item.url)?.let { setRequestProperty("Cookie", it) }
                    if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
                }
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                if (code == 200 && offset > 0) { file.delete(); offset = 0 } // server ignored Range
                val total = conn.contentLengthLong.let { if (it > 0) it + offset else -1L }
                var received = offset
                var lastUi = 0L
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(file, offset > 0).use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            received += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 300) { lastUi = now; set(id, { it.copy(received = received, total = total) }, persist = false) }
                        }
                    }
                }
                val uri = publish(item, file)
                file.delete()
                set(id, { it.copy(status = "done", received = received, total = received, savedUri = uri?.toString()) })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                set(id, { it.copy(status = "failed", error = e.message ?: "Download failed") })
            } finally {
                conn?.disconnect()
                jobs.remove(id)
            }
        }
    }

    /** Copies the finished file to the user's chosen folder, or to the public Downloads collection. */
    private fun publish(item: DownloadItem, file: File): Uri? {
        val resolver = context.contentResolver
        val mime = item.mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        treeUri()?.let { tree ->
            runCatching {
                val treeU = tree.toUri()
                val parent = DocumentsContract.buildDocumentUriUsingTree(treeU, DocumentsContract.getTreeDocumentId(treeU))
                val doc = DocumentsContract.createDocument(resolver, parent, mime, item.fileName) ?: error("createDocument failed")
                resolver.openOutputStream(doc)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                return doc
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, item.fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    fun shutdown() = scope.cancel()
}
