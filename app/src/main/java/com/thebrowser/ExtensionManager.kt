package com.thebrowser

import android.content.Context
import android.net.Uri
import com.thebrowser.core.engine.ContentScript
import com.thebrowser.core.ext.ExtensionManifest
import com.thebrowser.core.model.InstalledExtension
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/** Installs unpacked-extension ZIPs and turns enabled ones into content scripts. See core/ext/Extensions.kt for the honest scope. */
class ExtensionManager(private val context: Context) {
    private val root = File(context.filesDir, "extensions").apply { mkdirs() }

    /** Unzips [uri] into a fresh folder and reads its manifest. Throws with a readable message on failure. */
    fun install(uri: Uri): InstalledExtension {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        try {
            var bytes = 0L
            context.contentResolver.openInputStream(uri)!!.use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val out = File(dir, e.name).canonicalFile
                        if (!out.path.startsWith(dir.canonicalPath + File.separator)) error("Unsafe path in archive") // zip-slip
                        if (e.isDirectory) { out.mkdirs(); continue }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o -> bytes += zip.copyTo(o) }
                        if (bytes > 50L * 1024 * 1024) error("Extension is larger than 50 MB")
                    }
                }
            }
            // Some zips wrap everything in one folder.
            val base = if (File(dir, "manifest.json").exists()) dir else dir.listFiles()?.singleOrNull { File(it, "manifest.json").exists() } ?: error("manifest.json not found")
            if (base != dir) { base.copyRecursively(dir, overwrite = true); base.deleteRecursively() }
            val info = ExtensionManifest.parse(File(dir, "manifest.json").readText())
            val findings = ExtensionManifest.scan(info) { path -> File(dir, path).canonicalFile.takeIf { it.path.startsWith(dir.canonicalPath) && it.exists() }?.readText() }
            val contentPatterns = info.contentScripts.flatMap { it.matches }.distinct()
            return InstalledExtension(
                id, info.name, info.version, info.manifestVersion, enabled = false, granted = false,
                permissions = info.permissions, optionalPermissions = info.optionalPermissions,
                hostPatterns = contentPatterns, allowedPatterns = emptyList(),
                unsupported = info.unsupported, findings = findings,
                publisher = info.publisher, homepage = info.homepage, contentScriptCount = info.contentScripts.size,
            )
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    fun uninstall(id: String) { File(root, id).deleteRecursively() }

    /** Content scripts of every enabled and user-approved extension, ready to inject into new sessions. */
    fun scripts(all: List<InstalledExtension>): List<ContentScript> = all.filter { it.enabled && it.granted }.flatMap { ext ->
        val dir = File(root, ext.id)
        val mf = File(dir, "manifest.json").takeIf { it.exists() } ?: return@flatMap emptyList()
        runCatching {
            val info = ExtensionManifest.parse(mf.readText())
            ExtensionManifest.buildScripts(ext.id, info, ext.allowedPatterns.toSet()) { path ->
                File(dir, path).canonicalFile.takeIf { it.path.startsWith(dir.canonicalPath) && it.exists() }?.readText()
            }
        }.getOrDefault(emptyList())
    }
}
