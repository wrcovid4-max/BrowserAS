package com.thebrowser.engine.gecko

import android.content.Context
import com.thebrowser.core.engine.BrowserEngine
import com.thebrowser.core.engine.EngineListener
import com.thebrowser.core.engine.EngineSecurityInfo
import com.thebrowser.core.engine.EngineSession
import com.thebrowser.core.engine.EngineSettings
import com.thebrowser.core.model.DEFAULT_PROFILE_ID
import com.thebrowser.core.shield.ShieldConfig
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.StorageController

/**
 * Gecko (Firefox) rendering through GeckoView. WRITTEN BLIND: never compiled against GeckoView and never run.
 * GeckoView's API changes between major versions, so expect to fix signatures on the first build.
 *
 * What Gecko mode honestly lacks today (see [securityInfo]): no page-script injection or evaluation (so no password fill,
 * extensions or agent page tools), no per-site cookie read, no print, no per-request tracker counts beyond what GeckoView reports.
 */
class GeckoEngine(private val appContext: Context) : BrowserEngine {

    internal val runtime: GeckoRuntime by lazy {
        GeckoRuntime.create(
            appContext.applicationContext,
            GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(false)
                .remoteDebuggingEnabled(false)
                .consoleOutput(false)
                .build(),
        )
    }

    private val version: String get() = runCatching { org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION }.getOrDefault("")

    override val supportsIsolatedProfiles: Boolean = true   // GeckoSessionSettings.contextId gives separate storage per Profile
    override val supportsContentScripts: Boolean = false    // no evaluateJS / document-start scripts without a bundled WebExtension
    override val displayName: String get() = "Gecko · GeckoView $version".trim()

    internal fun userAgent(): String {
        val major = version.substringBefore('.').ifBlank { "0" }
        return "Mozilla/5.0 (Android 14; Mobile; rv:$major.0) Gecko/$major.0 Firefox/$major.0"
    }

    internal fun contextId(profileId: String): String? =
        if (profileId == DEFAULT_PROFILE_ID) null else "tb_" + profileId.filter { it.isLetterOrDigit() || it == '_' }

    /** Maps Hydrogen's shield switches onto Gecko's own content blocking. Runtime-wide, so the last session to set it wins. */
    internal fun applyShield(c: ShieldConfig) {
        runCatching {
            val t = c.toggles
            var at = ContentBlocking.AntiTracking.NONE
            if (c.enabled) {
                if (t.ads) at = at or ContentBlocking.AntiTracking.AD
                if (t.trackers) at = at or ContentBlocking.AntiTracking.ANALYTIC or ContentBlocking.AntiTracking.CONTENT
                if (t.social) at = at or ContentBlocking.AntiTracking.SOCIAL or ContentBlocking.AntiTracking.STP
                if (t.fpCanvas || t.fpWebgl || t.fpAudio || t.fpFonts || t.fpApis) at = at or ContentBlocking.AntiTracking.FINGERPRINTING or ContentBlocking.AntiTracking.CRYPTOMINING
            }
            val cb = runtime.settings.contentBlocking
            cb.setAntiTracking(at)
            cb.setCookieBehavior(
                if (c.enabled && t.thirdPartyCookies) ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
                else ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS,
            )
        }
        runCatching { runtime.settings.setAllowInsecureConnections(if (c.httpsMode == "strict") GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL) }
        runCatching { runtime.settings.setGlobalPrivacyControl(c.gpc) }
    }

    override fun securityInfo(): EngineSecurityInfo = EngineSecurityInfo(
        engineName = "Gecko (GeckoView)",
        engineVersion = version,
        engineUpdatedDaysAgo = null,   // the engine ships inside the app, so it is as old as this app build
        safeBrowsingSupported = true,
        safeBrowsingEnabled = null,    // not readable from GeckoView; shown as Not checked
        profilesIsolated = true,
        documentStartScripts = false,
        webMessageListener = false,
        notes = listOf(
            "Gecko is bundled with the app, so its security updates arrive only when Hydrogen itself is updated.",
            "Gecko shows its own certificate and Safe Browsing error pages; Hydrogen's warning pages are not used in this mode, and Gecko's certificate page may offer an Advanced option.",
            "Not available in Gecko mode: password fill, extensions, agent page tools, print, cookie read for downloads, fingerprint hooks and cookie-banner hiding (Gecko's built-in protections apply instead).",
        ),
    )

    override fun createSession(context: Context, profileId: String, settings: EngineSettings, listener: EngineListener): EngineSession =
        GeckoEngineSession(context, this, profileId, settings, listener)

    // GeckoView has no API to read cookies, so authenticated downloads cannot reuse the tab's cookies in this mode.
    override fun cookiesFor(profileId: String, url: String): String? = null

    override fun clearCookies(profileId: String) {
        runCatching {
            val id = contextId(profileId)
            if (id != null) runtime.storageController.clearDataForSessionContext(id)
            else runtime.storageController.clearData(StorageController.ClearFlags.COOKIES)
        }
    }

    override fun deleteProfileData(profileId: String) {
        val id = contextId(profileId) ?: return
        runCatching { runtime.storageController.clearDataForSessionContext(id) }
    }

    /** Private GeckoSessions keep nothing on disk, so there is nothing left over to purge. */
    override fun purgePrivateProfiles() {}
}
