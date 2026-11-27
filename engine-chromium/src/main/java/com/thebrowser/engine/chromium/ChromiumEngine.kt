package com.thebrowser.engine.chromium

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.thebrowser.core.engine.BrowserEngine
import com.thebrowser.core.engine.EngineListener
import com.thebrowser.core.engine.EngineSession
import com.thebrowser.core.engine.EngineSecurityInfo
import com.thebrowser.core.engine.EngineSettings
import com.thebrowser.core.model.DEFAULT_PROFILE_ID

/**
 * Chromium-based rendering through Android System WebView.
 *
 * This is the real Chromium engine, but it is NOT desktop Google Chrome: no extensions,
 * no Chrome Sync, no Chrome UI, and the engine version follows whichever WebView provider
 * the device has installed.
 */
@SuppressLint("RequiresFeature")
class ChromiumEngine(private val appContext: Context) : BrowserEngine {

    override val supportsIsolatedProfiles: Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    override val supportsContentScripts: Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    override val displayName: String
        get() {
            val pkg = runCatching { WebViewCompat.getCurrentWebViewPackage(appContext) }.getOrNull()
            return "Chromium · ${pkg?.packageName ?: "Android System WebView"} ${pkg?.versionName.orEmpty()}".trim()
        }

    /** Maps our profile id to a WebView profile name. Names are restricted, so sanitise. */
    internal fun profileName(profileId: String): String =
        if (profileId == DEFAULT_PROFILE_ID) WEBVIEW_DEFAULT_PROFILE
        else "tb_" + profileId.filter { it.isLetterOrDigit() || it == '_' }

    internal val cname by lazy { CnameChecker(appContext) }

    @Volatile private var passkeyMode: String? = null   // "browser" | "app" | "off" | "unsupported", set when a tab enables it

    /**
     * Turns on WebAuthn/passkeys for a tab through Credential Manager. Tries "browser" mode first (needs Hydrogen to be on Google's
     * privileged-browser allowlist); otherwise falls back to "app" mode, which only works for sites whose Digital Asset Links name this app.
     */
    @SuppressLint("RequiresFeature")
    internal fun configurePasskeys(settings: android.webkit.WebSettings, enabled: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) { passkeyMode = "unsupported"; return }
        if (!enabled) { runCatching { androidx.webkit.WebSettingsCompat.setWebAuthenticationSupport(settings, runCatching { androidx.webkit.WebSettingsCompat::class.java.getField("WEB_AUTHENTICATION_SUPPORT_NONE").getInt(null) }.getOrDefault(0)) }; passkeyMode = "off"; return }
        val browserMode = runCatching { androidx.webkit.WebSettingsCompat::class.java.getField("WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER").getInt(null) }.getOrNull()
        if (browserMode != null) runCatching { androidx.webkit.WebSettingsCompat.setWebAuthenticationSupport(settings, browserMode) }
        val applied = runCatching { androidx.webkit.WebSettingsCompat.getWebAuthenticationSupport(settings) }.getOrNull()
        if (browserMode != null && applied == browserMode) { passkeyMode = "browser"; return }
        runCatching { androidx.webkit.WebSettingsCompat.setWebAuthenticationSupport(settings, androidx.webkit.WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP) }
        passkeyMode = if (runCatching { androidx.webkit.WebSettingsCompat.getWebAuthenticationSupport(settings) }.getOrNull() == androidx.webkit.WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP) "app" else "off"
    }

    override fun passkeyStatus(): String = when (passkeyMode) {
        "browser" -> "On (browser mode). Passkeys work on any site."
        "app" -> "On (app mode). Works only on sites that list Hydrogen in their Digital Asset Links. Passkeys on every site need Hydrogen to be added to Google's privileged-browser allowlist."
        "off" -> "Off."
        "unsupported" -> "This Android System WebView does not support passkeys. Update WebView."
        else -> if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) "Supported. Turns on when you open a tab." else "This Android System WebView does not support passkeys. Update WebView."
    }

    private companion object { const val WEBVIEW_DEFAULT_PROFILE = "Default" }

    @SuppressLint("RequiresFeature")
    override fun securityInfo(): EngineSecurityInfo {
        val pkg = runCatching { WebViewCompat.getCurrentWebViewPackage(appContext) }.getOrNull()
        val days = pkg?.let { ((System.currentTimeMillis() - appContext.packageManager.getPackageInfo(it.packageName, 0).lastUpdateTime) / 86_400_000L).toInt() }
        val sbSupported = WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)
        var sbEnabled: Boolean? = null
        if (sbSupported) {
            // Read the real setting from a throw-away WebView instead of assuming it.
            runCatching {
                val w = android.webkit.WebView(appContext)
                sbEnabled = androidx.webkit.WebSettingsCompat.getSafeBrowsingEnabled(w.settings)
                w.destroy()
            }
        }
        return EngineSecurityInfo(
            engineName = "Chromium (Android System WebView)",
            engineVersion = pkg?.versionName.orEmpty(),
            engineUpdatedDaysAgo = days,
            safeBrowsingSupported = sbSupported,
            safeBrowsingEnabled = sbEnabled,
            profilesIsolated = supportsIsolatedProfiles,
            documentStartScripts = supportsContentScripts,
            webMessageListener = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER),
            notes = listOf(
                "Renderer sandbox and Same-Origin/CSP/CORS/COOP/COEP enforcement are provided by Chromium and cannot be configured or verified from the app.",
                "Android WebView does not offer full desktop Site Isolation; partitioned storage and caches are engine-managed.",
                "HSTS and Certificate Transparency are enforced by the engine where the installed WebView version supports them.",
            ),
        )
    }

    override fun createSession(
        context: Context,
        profileId: String,
        settings: EngineSettings,
        listener: EngineListener,
    ): EngineSession = ChromiumSession(context, this, profileId, settings, listener)

    private fun cookieManager(profileId: String): CookieManager =
        if (supportsIsolatedProfiles) {
            ProfileStore.getInstance().getOrCreateProfile(profileName(profileId)).cookieManager
        } else {
            CookieManager.getInstance()
        }

    override fun cookiesFor(profileId: String, url: String): String? =
        runCatching { cookieManager(profileId).getCookie(url) }.getOrNull()

    override fun clearCookies(profileId: String) {
        runCatching {
            val cm = cookieManager(profileId)
            cm.removeAllCookies(null)
            cm.flush()
        }
    }

    override fun deleteProfileData(profileId: String) {
        if (!supportsIsolatedProfiles || profileId == DEFAULT_PROFILE_ID) return
        // Throws if a WebView still uses the profile; callers destroy its sessions first.
        runCatching { ProfileStore.getInstance().deleteProfile(profileName(profileId)) }
    }

    override fun purgePrivateProfiles() {
        if (!supportsIsolatedProfiles) return
        runCatching {
            val store = ProfileStore.getInstance()
            store.allProfileNames.filter { it.startsWith("tb_private_") }.forEach { runCatching { store.deleteProfile(it) } }
        }
    }
}
