package com.thebrowser.core.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import com.thebrowser.core.shield.SecurityBlock
import com.thebrowser.core.shield.ShieldConfig
import com.thebrowser.core.shield.ShieldEventKind

/** A content script to run in pages whose origin matches [originRules] (WebView origin-rule syntax). */
data class ContentScript(val extensionId: String, val js: String, val originRules: Set<String>)

/** Callbacks from an engine session. All calls happen on the main thread. */
interface EngineListener {
    fun onUrlChanged(url: String)
    fun onTitleChanged(title: String)
    fun onProgress(percent: Int)
    fun onNavState(canGoBack: Boolean, canGoForward: Boolean)
    fun onPageFinished(url: String, title: String)
    fun onOpenInNewTab(url: String)
    fun onDownload(url: String, userAgent: String, contentDisposition: String?, mimeType: String?)

    /** kinds: "camera", "microphone", "location". Call [decide] exactly once. */
    fun onPermissionRequest(origin: String, kinds: Set<String>, decide: (Boolean) -> Unit)

    /** Launch [intent] for a result and report it through [complete]. */
    fun onFileChooser(intent: Intent, complete: (resultCode: Int, data: Intent?) -> Unit)

    /** Fullscreen video. [view] null means leave fullscreen. */
    fun onCustomView(view: View?, onHidden: (() -> Unit)?)

    /** A top-level navigation began: per-page counters reset. */
    fun onPageCommitted(url: String)

    /** The engine really did something (blocked a request, rewrote a URL, altered an API result…). */
    fun onShieldEvent(kind: ShieldEventKind, host: String)

    /** A full-page warning must be shown (certificate, Safe Browsing, HTTPS-only, lookalike, redirect chain). */
    fun onSecurityBlock(block: SecurityBlock)

    /** A login form was submitted (main frame only). Never log the arguments. */
    fun onCredentialSubmitted(origin: String, username: String, password: String)
}

data class EngineSettings(
    val shield: ShieldConfig,
    val blockPopups: Boolean = true,
    val contentScripts: List<ContentScript> = emptyList(),
)

/** What the engine can honestly report about its own protections. Unknowns are null, never guessed. */
data class EngineSecurityInfo(
    val engineName: String,
    val engineVersion: String,
    val engineUpdatedDaysAgo: Int?,
    val safeBrowsingSupported: Boolean,
    val safeBrowsingEnabled: Boolean?,
    val profilesIsolated: Boolean,
    val documentStartScripts: Boolean,
    val webMessageListener: Boolean,
    val notes: List<String>,
)

interface EngineSession {
    val view: View
    val canGoBack: Boolean
    val canGoForward: Boolean
    fun load(url: String)
    fun goBack()
    fun goForward()
    fun reload()
    fun stop()
    fun evaluate(js: String, callback: (String?) -> Unit)
    fun findAll(query: String)
    fun findNext(forward: Boolean)
    fun clearFind()
    fun print(jobName: String)
    fun updateShield(config: ShieldConfig)
    fun fillCredential(user: String, pass: String, callback: (Boolean) -> Unit)
    fun snapshot(maxWidth: Int): Bitmap?
    fun pause()
    fun resume()
    fun destroy()
}

/**
 * Rendering-engine abstraction. The UI only talks to this interface, so another engine can be
 * added later without touching UI code.
 */
interface BrowserEngine {
    /** Shown in the UI, e.g. "Chromium · com.google.android.webview 131.0". */
    val displayName: String

    /** True when the engine can give each Profile genuinely separate cookies/storage. */
    val supportsIsolatedProfiles: Boolean

    /** True when the engine can run injected document-start scripts (needed for extensions). */
    val supportsContentScripts: Boolean

    fun securityInfo(): EngineSecurityInfo

    fun createSession(context: Context, profileId: String, settings: EngineSettings, listener: EngineListener): EngineSession
    fun cookiesFor(profileId: String, url: String): String?
    fun clearCookies(profileId: String)
    fun deleteProfileData(profileId: String)

    /** Removes leftover private-browsing profiles (e.g. after a crash). Call at startup, before any session exists. */
    fun purgePrivateProfiles()
}
