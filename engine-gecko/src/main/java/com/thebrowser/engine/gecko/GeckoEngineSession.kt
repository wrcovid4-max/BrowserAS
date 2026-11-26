package com.thebrowser.engine.gecko

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import androidx.core.content.ContextCompat
import com.thebrowser.core.engine.EngineListener
import com.thebrowser.core.engine.EngineSession
import com.thebrowser.core.engine.EngineSettings
import com.thebrowser.core.shield.ShieldConfig
import com.thebrowser.core.shield.ShieldEventKind
import com.thebrowser.core.shield.UrlCleaner
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse

/** One tab rendered by GeckoView. Blind-written against GeckoView's public API; see [GeckoEngine]. */
internal class GeckoEngineSession(
    private val context: Context,
    private val engine: GeckoEngine,
    profileId: String,
    engineSettings: EngineSettings,
    private val listener: EngineListener,
) : EngineSession {

    @Volatile private var shield: ShieldConfig = engineSettings.shield
    private val blockPopups = engineSettings.blockPopups
    private val geckoView = GeckoView(context)
    private lateinit var session: GeckoSession
    private var back = false
    private var forward = false
    private var url = ""
    private var title = ""
    @Volatile private var lastSnapshot: Bitmap? = null

    private fun hostOf(u: String?): String? = runCatching { Uri.parse(u).host?.lowercase() }.getOrNull()
    private fun originOf(u: String?): String = runCatching { Uri.parse(u).let { "${it.scheme}://${it.host}" } }.getOrDefault(u.orEmpty())

    // ───────── navigation ─────────
    private val nav = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(s: GeckoSession, u: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
            if (u != null) { url = u; listener.onUrlChanged(u) }
        }
        override fun onCanGoBack(s: GeckoSession, canGoBack: Boolean) { back = canGoBack; listener.onNavState(back, forward) }
        override fun onCanGoForward(s: GeckoSession, canGoForward: Boolean) { forward = canGoForward; listener.onNavState(back, forward) }

        override fun onLoadRequest(s: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
            val target = request.uri
            if (shield.activeFor(hostOf(url)) && shield.toggles.stripParams) {
                val cleaned = UrlCleaner.unwrapRedirector(target)?.also { listener.onShieldEvent(ShieldEventKind.BounceUnwrapped, hostOf(target).orEmpty()) }
                    ?: UrlCleaner.clean(target)?.also { listener.onShieldEvent(ShieldEventKind.ParamStripped, hostOf(target).orEmpty()) }
                if (cleaned != null && cleaned != target) { s.loadUri(cleaned); return GeckoResult.fromValue(AllowOrDeny.DENY) }
            }
            return GeckoResult.fromValue(AllowOrDeny.ALLOW)
        }

        override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            val host = hostOf(uri)
            val allowed = !blockPopups || (host != null && host in shield.popupAllowedHosts)
            if (allowed) listener.onOpenInNewTab(uri)
            return GeckoResult.fromValue(null)   // Hydrogen opens the tab itself
        }
    }

    // ───────── progress ─────────
    private val progress = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(s: GeckoSession, u: String) { listener.onPageCommitted(u) }
        override fun onProgressChange(s: GeckoSession, p: Int) { listener.onProgress(p) }
        override fun onPageStop(s: GeckoSession, success: Boolean) { listener.onPageFinished(url, title) }
    }

    // ───────── content: title, downloads ─────────
    private val content = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(s: GeckoSession, t: String?) { title = t.orEmpty(); listener.onTitleChanged(title) }
        override fun onExternalResponse(s: GeckoSession, response: WebResponse) {
            val h = response.headers
            listener.onDownload(response.uri, engine.userAgent(), h["content-disposition"], h["content-type"])
        }
    }

    // ───────── permissions (camera, microphone, location) ─────────
    private val perms = object : GeckoSession.PermissionDelegate {
        override fun onAndroidPermissionsRequest(s: GeckoSession, permissions: Array<out String>?, callback: GeckoSession.PermissionDelegate.Callback) {
            // Hydrogen does not show Android's system dialog from here; it only passes on what the user already granted in system settings.
            val ok = permissions.orEmpty().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            if (ok) callback.grant() else callback.reject()
        }

        override fun onContentPermissionRequest(s: GeckoSession, perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int>? {
            if (perm.permission != GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION)
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            val r = GeckoResult<Int>()
            listener.onPermissionRequest(originOf(perm.uri), setOf("location")) { ok ->
                r.complete(if (ok) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
            return r
        }

        override fun onMediaPermissionRequest(
            s: GeckoSession, uri: String,
            video: Array<out GeckoSession.PermissionDelegate.MediaSource>?, audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
            val kinds = buildSet { if (!video.isNullOrEmpty()) add("camera"); if (!audio.isNullOrEmpty()) add("microphone") }
            if (kinds.isEmpty()) { callback.reject(); return }
            listener.onPermissionRequest(originOf(uri), kinds) { ok -> if (ok) callback.grant(video?.firstOrNull(), audio?.firstOrNull()) else callback.reject() }
        }
    }

    // ───────── file chooser ─────────
    private val prompts = object : GeckoSession.PromptDelegate {
        override fun onFilePrompt(s: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            val r = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            prompt.mimeTypes?.takeIf { it.isNotEmpty() }?.let { i.putExtra(Intent.EXTRA_MIME_TYPES, it) }
            if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            listener.onFileChooser(i) { code, data ->
                val clip = data?.clipData
                val uris: Array<Uri>? = if (code != Activity.RESULT_OK || data == null) null
                    else if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri } else data.data?.let { arrayOf(it) }
                r.complete(when {
                    uris == null || uris.isEmpty() -> prompt.dismiss()
                    uris.size == 1 -> prompt.confirm(context, uris[0])
                    else -> prompt.confirm(context, uris)
                })
            }
            return r
        }
    }

    // ───────── Gecko's own blocking reports → real shield counters ─────────
    private val blocking = object : ContentBlocking.Delegate {
        override fun onContentBlocked(s: GeckoSession, event: ContentBlocking.BlockEvent) {
            if (!event.isBlocking) return
            val host = hostOf(event.uri).orEmpty()
            val cat = event.antiTrackingCategory
            val kind = when {
                event.safeBrowsingCategory != 0 -> ShieldEventKind.Dangerous
                cat and ContentBlocking.AntiTracking.AD != 0 -> ShieldEventKind.Ad
                cat and ContentBlocking.AntiTracking.SOCIAL != 0 -> ShieldEventKind.Social
                else -> ShieldEventKind.Tracker
            }
            listener.onShieldEvent(kind, host)
        }
    }

    // Must stay below the delegates: Kotlin initialises properties and init blocks in source order.
    init {
        val b = GeckoSessionSettings.Builder().usePrivateMode(shield.isPrivate).userAgentOverride(engine.userAgent())
        if (!shield.isPrivate) engine.contextId(profileId)?.let { b.contextId(it) }
        session = GeckoSession(b.build())
        session.navigationDelegate = nav
        session.progressDelegate = progress
        session.contentDelegate = content
        session.permissionDelegate = perms
        session.promptDelegate = prompts
        session.contentBlockingDelegate = blocking
        engine.applyShield(shield)
        session.open(engine.runtime)
        geckoView.setSession(session)
    }

    // ───────── EngineSession ─────────
    override val view: View get() = geckoView
    override val canGoBack: Boolean get() = back
    override val canGoForward: Boolean get() = forward
    override fun load(url: String) { session.loadUri(url) }
    override fun goBack() { session.goBack() }
    override fun goForward() { session.goForward() }
    override fun reload() { session.reload() }
    override fun stop() { session.stop() }

    /** Gecko cannot run page scripts without a bundled WebExtension, so this reports "no result". Callers treat null as unsupported. */
    override fun evaluate(js: String, callback: (String?) -> Unit) { callback(null) }

    override fun findAll(query: String) { session.finder.find(query, 0) }
    override fun findNext(forward: Boolean) { session.finder.find(null, if (forward) 0 else GeckoSession.FINDER_FIND_BACKWARDS) }
    override fun clearFind() { session.finder.clear() }
    override fun print(jobName: String) { /* not supported in Gecko mode */ }
    override fun updateShield(config: ShieldConfig) { shield = config; engine.applyShield(config) }
    override fun fillCredential(user: String, pass: String, callback: (Boolean) -> Unit) { callback(false) }

    /** Asynchronous capture: returns the previous frame and refreshes it in the background. */
    override fun snapshot(maxWidth: Int): Bitmap? {
        runCatching {
            geckoView.capturePixels().accept({ bmp ->
                if (bmp != null) lastSnapshot = if (bmp.width > maxWidth) Bitmap.createScaledBitmap(bmp, maxWidth, bmp.height * maxWidth / bmp.width, true) else bmp
            }, { _ -> })
        }
        return lastSnapshot
    }

    override fun pause() { session.setActive(false) }
    override fun resume() { session.setActive(true) }
    override fun destroy() { runCatching { geckoView.releaseSession() }; runCatching { session.close() } }
}
