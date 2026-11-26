package com.thebrowser.engine.chromium

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.thebrowser.core.engine.EngineListener
import com.thebrowser.core.engine.EngineSession
import com.thebrowser.core.engine.EngineSettings
import com.thebrowser.core.shield.FilterCategory
import com.thebrowser.core.shield.Registrable
import com.thebrowser.core.shield.SecurityBlock
import com.thebrowser.core.shield.ShieldConfig
import com.thebrowser.core.shield.ShieldEventKind
import com.thebrowser.core.shield.ShieldScripts
import com.thebrowser.core.shield.UrlCleaner
import com.thebrowser.core.shield.UrlSafety
import org.json.JSONObject
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
internal class ChromiumSession(
    private val context: Context,
    private val engine: ChromiumEngine,
    profileId: String,
    private val engineSettings: EngineSettings,
    private val listener: EngineListener,
) : EngineSession {

    private val webView = WebView(context)
    @Volatile private var shield: ShieldConfig = engineSettings.shield
    @Volatile private var topHost: String? = null
    @Volatile private var topUrl: String = ""
    private val upgradedHosts = HashSet<String>()
    private val httpAllowed = HashSet<String>()
    private val lookalikeAllowed = HashSet<String>()
    private var hops = 0
    private var lastNavTime = 0L
    private val scriptHandlers = mutableListOf<ScriptHandler>()

    private fun navState() = listener.onNavState(webView.canGoBack(), webView.canGoForward())
    private fun emit(kind: ShieldEventKind, host: String) { webView.post { listener.onShieldEvent(kind, host) } }
    private fun hostOf(url: String?): String? = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()

    // ───────── navigation decisions (also used for our own load() calls) ─────────
    private sealed class Decision {
        data object Allow : Decision()
        data class Rewrite(val url: String) : Decision()
        data class Stop(val block: SecurityBlock?) : Decision()
    }

    private fun decide(input: String, depth: Int = 0): Decision {
        var url = input
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme !in setOf("http", "https", "about", "data", "blob")) return Decision.Stop(null)
        if (scheme == "about" || scheme == "data" || scheme == "blob") return Decision.Allow
        var host = hostOf(url) ?: return Decision.Allow
        val s = shield
        val privacyOn = s.activeFor(host)
        var changed = false

        // 1. IDN / lookalike warning (security: stays on even if shields are off for the site)
        if (host !in lookalikeAllowed) {
            val findings = UrlSafety.analyseHost(host)
            if (findings.isNotEmpty()) {
                emit(ShieldEventKind.Lookalike, host)
                return Decision.Stop(SecurityBlock(
                    "lookalike", url, host, "This address may be imitating another site",
                    "The address uses characters or spelling that resemble a well-known site. Phishing pages often do this.",
                    findings.map { it.message },
                    proceed = { lookalikeAllowed += host; webView.post { webView.loadUrl(url) } },
                    back = {},
                ))
            }
        }
        // 2. Known link-wrapper bounce pages
        if (privacyOn && s.toggles.bounce && depth < 2) {
            UrlCleaner.unwrapRedirector(url)?.let { dest -> emit(ShieldEventKind.BounceUnwrapped, host); return decide(dest, depth + 1).let { if (it is Decision.Allow) Decision.Rewrite(dest) else it } }
        }
        // 3. Tracking query parameters
        if (privacyOn && s.toggles.stripParams) {
            UrlCleaner.clean(url)?.let { url = it; changed = true; emit(ShieldEventKind.ParamStripped, host) }
        }
        // 4. HTTPS-First / Strict
        if (url.startsWith("http://") && s.httpsMode != "off" && host !in httpAllowed && host !in s.strictHttpsAllowedHosts && !isLocal(host)) {
            url = "https://" + url.removePrefix("http://"); changed = true
            upgradedHosts += host
            emit(ShieldEventKind.HttpsUpgrade, host)
        }
        return if (changed) Decision.Rewrite(url) else Decision.Allow
    }

    private fun isLocal(h: String) = h == "localhost" || h.endsWith(".local") || Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(h) || h.contains(':')

    /** true = the navigation was handled here (rewritten or stopped); false = let the original URL load. */
    private fun handle(d: Decision): Boolean = when (d) {
        is Decision.Allow -> false
        is Decision.Rewrite -> { webView.post { webView.loadUrl(d.url) }; true }
        is Decision.Stop -> { d.block?.let { b -> webView.post { listener.onSecurityBlock(b) } }; true }
    }

    private fun applySitePolicy(host: String?) {
        val s = shield
        webView.settings.javaScriptEnabled = host == null || host !in s.jsBlockedHosts
        webView.settings.javaScriptCanOpenWindowsAutomatically = !engineSettings.blockPopups || (host != null && host in s.popupAllowedHosts)
        val blockTp = s.activeFor(host) && s.toggles.thirdPartyCookies && (host == null || host !in s.thirdPartyCookiesAllowedHosts)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !blockTp)
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (request.isForMainFrame) {
                // redirect-chain tracking for suspicious redirect warnings
                val now = System.currentTimeMillis()
                if (request.isRedirect) { hops = if (now - lastNavTime < 6000) hops + 1 else 1 } else hops = 0
                lastNavTime = now
                if (hops >= 4) {
                    hops = 0
                    emit(ShieldEventKind.SuspiciousRedirect, hostOf(url).orEmpty())
                    webView.post { listener.onSecurityBlock(SecurityBlock(
                        "redirect", url, hostOf(url).orEmpty(), "This page keeps redirecting you",
                        "Several automatic redirects happened in a row. Some phishing and tracking chains work this way.",
                        listOf("Next stop: ${hostOf(url)}"), proceed = { webView.loadUrl(url) }, back = {},
                    )) }
                    return true
                }
            }
            return handle(decide(url))
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.isForMainFrame) return null
            val s = shield; val top = topHost
            if (top == null || !s.activeFor(top)) return null
            val host = request.url.host?.lowercase() ?: return null
            val cat = s.filters.categoryOf(host)
            if (cat != null && Registrable.isThirdParty(host, top)) {
                val on = when (cat) { FilterCategory.Tracker -> s.toggles.trackers; FilterCategory.Ad -> s.toggles.ads; FilterCategory.Social -> s.toggles.social }
                if (on) {
                    emit(when (cat) { FilterCategory.Tracker -> ShieldEventKind.Tracker; FilterCategory.Ad -> ShieldEventKind.Ad; FilterCategory.Social -> ShieldEventKind.Social }, host)
                    return blocked()
                }
            }
            if (s.toggles.cname && host != top && !Registrable.isThirdParty(host, top) && engine.cname.isCloaked(host, s.filters)) {
                emit(ShieldEventKind.Cname, host); return blocked()
            }
            return null
        }

        private fun blocked() = WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (url != null) {
                topUrl = url; topHost = hostOf(url)
                applySitePolicy(topHost)
                listener.onUrlChanged(url)
                listener.onPageCommitted(url)
            }
            navState()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { url?.let(listener::onUrlChanged); navState() }

        override fun onPageFinished(view: WebView, url: String?) {
            navState()
            if (url != null && url != "about:blank") listener.onPageFinished(url, view.title.orEmpty())
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            val host = request.url.host?.lowercase() ?: return
            if (!request.isForMainFrame || host !in upgradedHosts) return
            val retryable = error.errorCode in setOf(ERROR_CONNECT, ERROR_TIMEOUT, ERROR_IO, ERROR_FAILED_SSL_HANDSHAKE, ERROR_UNKNOWN)
            if (!retryable) return
            val httpUrl = "http://" + request.url.toString().removePrefix("https://")
            val strict = shield.httpsMode == "strict"
            webView.post { listener.onSecurityBlock(SecurityBlock(
                "https_only", httpUrl, host, "This site doesn't support a secure connection",
                if (strict) "Strict HTTPS mode is on, so TheBrowser will not open this site over an unencrypted connection."
                else "HTTPS-First tried a secure connection and it failed. Opening the site over HTTP would let others on the network read or change what you send and receive.",
                listOf("Tried: ${request.url}", "Error: ${error.description}"),
                proceed = if (strict) null else ({ httpAllowed += host; webView.loadUrl(httpUrl) }),
                back = {},
            )) }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val host = hostOf(error.url).orEmpty()
            val mainFrame = host == topHost && (error.url == topUrl || hostOf(topUrl) == host)
            if (!mainFrame) { handler.cancel(); return } // sub-resource: always refuse
            emit(ShieldEventKind.CertError, host)
            val cert = error.certificate
            val reason = when (error.primaryError) {
                SslError.SSL_UNTRUSTED -> "The certificate was not issued by an authority your device trusts."
                SslError.SSL_EXPIRED -> "The certificate has expired."
                SslError.SSL_IDMISMATCH -> "The certificate was issued for a different website name."
                SslError.SSL_NOTYETVALID -> "The certificate is not valid yet (check your device's date and time)."
                SslError.SSL_DATE_INVALID -> "The certificate's dates are invalid."
                else -> "The certificate is invalid."
            }
            val strict = shield.httpsMode == "strict"
            webView.post { listener.onSecurityBlock(SecurityBlock(
                "cert", error.url, host, "Your connection is not private",
                "Attackers might be trying to steal your information from $host (for example passwords, messages or cards). TheBrowser stopped the page because its certificate can't be trusted.",
                listOf(reason, "Issued to: ${cert?.issuedTo?.dName}", "Issued by: ${cert?.issuedBy?.dName}", "Valid: ${cert?.validNotBefore} → ${cert?.validNotAfter}"),
                proceed = if (strict) null else ({ handler.proceed() }),
                back = { handler.cancel() },
            )) }
        }

        override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) {
            val host = request.url.host.orEmpty()
            emit(ShieldEventKind.Dangerous, host)
            if (!request.isForMainFrame) { callback.showInterstitial(false); return }
            val (kind, title, why) = when (threatType) {
                WebViewClient.SAFE_BROWSING_THREAT_MALWARE -> Triple("malware", "This site may harm your device", "Google Safe Browsing lists this site as hosting malware.")
                WebViewClient.SAFE_BROWSING_THREAT_PHISHING -> Triple("phishing", "Deceptive site ahead", "Google Safe Browsing lists this site as a phishing page that tries to trick you into sharing passwords or payment details.")
                WebViewClient.SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE -> Triple("unwanted", "This site may install unwanted software", "Google Safe Browsing lists this site as distributing unwanted software.")
                else -> Triple("billing", "This site may charge you unexpectedly", "Google Safe Browsing flags this site for deceptive billing.")
            }
            webView.post { listener.onSecurityBlock(SecurityBlock(
                kind, request.url.toString(), host, title, why,
                listOf("Provider: Google Safe Browsing through Android System WebView (checks a local hash-prefix list; Google may receive partial hashes of URLs that match it).", "Threat type code: $threatType"),
                proceed = { callback.proceed(false) }, back = { callback.backToSafety(false) },
            )) }
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) = listener.onProgress(newProgress)
        override fun onReceivedTitle(view: WebView, title: String?) { if (!title.isNullOrBlank()) listener.onTitleChanged(title) }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val popupsOk = !engineSettings.blockPopups || (topHost?.let { it in shield.popupAllowedHosts } == true)
            if (!popupsOk && !isUserGesture) return false // pop-up protection
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val temp = WebView(context)
            runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) WebViewCompat.setProfile(temp, WebViewCompat.getProfile(view).name)
            }
            temp.webViewClient = object : WebViewClient() {
                private var done = false
                private fun hand(url: String?) {
                    if (done || url.isNullOrBlank() || url == "about:blank") return
                    done = true; listener.onOpenInNewTab(url); temp.post { temp.destroy() }
                }
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { hand(r.url.toString()); return true }
                override fun onPageStarted(v: WebView, url: String?, f: Bitmap?) { hand(url) }
            }
            transport.webView = temp
            resultMsg.sendToTarget()
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val kinds = mutableSetOf<String>(); val grantable = mutableListOf<String>()
            request.resources.forEach {
                when (it) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> { kinds += "camera"; grantable += it }
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> { kinds += "microphone"; grantable += it }
                    PermissionRequest.RESOURCE_MIDI_SYSEX -> { kinds += "midi"; grantable += it }
                }
            }
            if (grantable.isEmpty()) { request.deny(); return }
            listener.onPermissionRequest(request.origin.toString(), kinds) { ok -> if (ok) request.grant(grantable.toTypedArray()) else request.deny() }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            listener.onPermissionRequest(origin, setOf("location")) { ok -> callback.invoke(origin, ok, false) }
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            listener.onFileChooser(params.createIntent()) { code, data -> callback.onReceiveValue(FileChooserParams.parseResult(code, data)) }
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) { listener.onCustomView(view) { callback.onCustomViewHidden() } }
        override fun onHideCustomView() = listener.onCustomView(null, null)
    }

    init {
        // The profile MUST be set before anything is loaded.
        if (engine.supportsIsolatedProfiles) WebViewCompat.setProfile(webView, engine.profileName(profileId))
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW // blocks mixed content, incl. ws:// from https pages
            setSupportMultipleWindows(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) WebSettingsCompat.setSafeBrowsingEnabled(this, true)
        }
        if (shield.isPrivate) webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        webView.webViewClient = client
        webView.webChromeClient = chrome
        webView.setDownloadListener { url, ua, cd, mime, _ -> listener.onDownload(url, ua, cd, mime) }
        applySitePolicy(null)
        installScripts()
    }

    private fun installScripts() {
        scriptHandlers.forEach { runCatching { it.remove() } }; scriptHandlers.clear()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching {
                WebViewCompat.addWebMessageListener(webView, "__tbShield", setOf("*")) { _, message: WebMessageCompat, sourceOrigin, isMainFrame, _ -> onPageMessage(message.data.orEmpty(), sourceOrigin.toString(), isMainFrame) }
            }
        }
        if (!engine.supportsContentScripts) return
        fun add(js: String, origins: Set<String>) { runCatching { scriptHandlers += WebViewCompat.addDocumentStartJavaScript(webView, js, origins) } }
        val s = shield
        if (s.enabled) add(ShieldScripts.pageScript(s), setOf("*"))
        if (s.captureCredentials && !s.isPrivate) add(ShieldScripts.CREDENTIAL_SCRIPT, setOf("*"))
        engineSettings.contentScripts.forEach { add(it.js, it.originRules) } // never passed for private sessions
    }

    private fun onPageMessage(data: String, origin: String, mainFrame: Boolean) {
        val sep = data.indexOf('|'); if (sep < 0) return
        val type = data.substring(0, sep); val body = data.substring(sep + 1)
        when (type) {
            "ev" -> {
                if (!shield.activeFor(topHost)) return
                val kind = when (body) {
                    "fp_canvas" -> ShieldEventKind.FpCanvas; "fp_webgl" -> ShieldEventKind.FpWebgl; "fp_audio" -> ShieldEventKind.FpAudio
                    "fp_fonts" -> ShieldEventKind.FpFonts; "fp_api" -> ShieldEventKind.FpApi; "webrtc" -> ShieldEventKind.WebRtc
                    "consent" -> ShieldEventKind.ConsentHidden; "ads_hidden" -> ShieldEventKind.AdsHidden
                    else -> return
                }
                listener.onShieldEvent(kind, topHost.orEmpty())
            }
            "cred" -> if (mainFrame && !shield.isPrivate && shield.captureCredentials) runCatching {
                val o = JSONObject(body)
                // Only accept a login report that really came from the page's own origin.
                if (o.optString("origin") == origin) listener.onCredentialSubmitted(origin, o.optString("user"), o.optString("pass"))
            }
        }
    }

    override fun updateShield(config: ShieldConfig) {
        shield = config
        applySitePolicy(topHost)
        installScripts() // takes effect on the next page load
    }

    override fun fillCredential(user: String, pass: String, callback: (Boolean) -> Unit) {
        webView.evaluateJavascript(ShieldScripts.fillScript(user, pass)) { callback(it?.contains("ok") == true) }
    }

    override val view: View get() = webView
    override val canGoBack get() = webView.canGoBack()
    override val canGoForward get() = webView.canGoForward()

    override fun load(url: String) {
        // Our own loads skip shouldOverrideUrlLoading, so run the same checks here.
        if (!handle(decide(url))) webView.loadUrl(url, if (shield.gpc) mapOf("Sec-GPC" to "1", "DNT" to "1") else emptyMap())
    }

    override fun goBack() = webView.goBack()
    override fun goForward() = webView.goForward()
    override fun reload() = webView.reload()
    override fun stop() = webView.stopLoading()
    override fun evaluate(js: String, callback: (String?) -> Unit) = webView.evaluateJavascript(js, callback)
    override fun findAll(query: String) = webView.findAllAsync(query)
    override fun findNext(forward: Boolean) = webView.findNext(forward)
    override fun clearFind() = webView.clearMatches()

    override fun print(jobName: String) {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(jobName, webView.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
    }

    override fun snapshot(maxWidth: Int): Bitmap? = runCatching {
        val w = webView.width; val h = webView.height
        if (w <= 0 || h <= 0) return null
        val scale = maxWidth.toFloat() / w
        val bmp = Bitmap.createBitmap(maxWidth, (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
        val c = Canvas(bmp); c.scale(scale, scale); webView.draw(c); bmp
    }.getOrNull()

    override fun pause() = webView.onPause()
    override fun resume() = webView.onResume()

    override fun destroy() {
        scriptHandlers.forEach { runCatching { it.remove() } }
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
    }
}
