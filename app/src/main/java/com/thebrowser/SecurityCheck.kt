package com.thebrowser

import android.app.Application
import android.net.ConnectivityManager
import android.os.Build
import androidx.biometric.BiometricManager
import com.thebrowser.core.engine.BrowserEngine
import com.thebrowser.core.ext.ExtensionManifest
import com.thebrowser.core.model.BrowserState
import com.thebrowser.core.vault.CredentialVault
import java.text.SimpleDateFormat
import java.util.Locale

enum class CheckStatus(val label: String) { Secure("Secure"), Attention("Attention required"), Unsupported("Unsupported"), NotChecked("Not checked") }

data class CheckItem(val title: String, val status: CheckStatus, val detail: String)

/**
 * Every row is derived from a real signal (engine query, OS API, stored configuration).
 * A toggle being ON is never treated as proof of safety; when the app cannot verify something it says so.
 */
object SecurityCheck {
    fun run(app: Application, engine: BrowserEngine, s: BrowserState, vaultExists: Boolean, vaultWeak: Int?, vaultReused: Int?, breachResult: String?): List<CheckItem> {
        val out = mutableListOf<CheckItem>()
        val info = engine.securityInfo()
        val sh = s.settings.shield

        // Engine freshness
        val days = info.engineUpdatedDaysAgo
        out += CheckItem("Browser engine", when {
            days == null -> CheckStatus.NotChecked
            days <= 45 -> CheckStatus.Secure
            else -> CheckStatus.Attention
        }, "${info.engineName} ${info.engineVersion}. " + (days?.let { "Updated $it days ago. The app cannot see whether a newer version exists; keep Android System WebView updated in Play Store." } ?: "Update date unavailable."))

        // OS patch level
        val patch = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(Build.VERSION.SECURITY_PATCH) }.getOrNull()
        val patchDays = patch?.let { ((System.currentTimeMillis() - it.time) / 86_400_000L).toInt() }
        out += CheckItem("Android security patch", when { patchDays == null -> CheckStatus.NotChecked; patchDays <= 120 -> CheckStatus.Secure; else -> CheckStatus.Attention },
            "Patch level ${Build.VERSION.SECURITY_PATCH}" + (patchDays?.let { " ($it days old)" } ?: ""))

        // Safe Browsing (really read from the engine)
        out += CheckItem("Safe Browsing", when {
            !info.safeBrowsingSupported -> CheckStatus.Unsupported
            info.safeBrowsingEnabled == true -> CheckStatus.Secure
            info.safeBrowsingEnabled == false -> CheckStatus.Attention
            else -> CheckStatus.NotChecked
        }, "Google Safe Browsing via Android System WebView (local hash-prefix database; Google may receive partial URL hashes for matches). Whether a given page is dangerous is decided by that service.")

        // HTTPS
        out += CheckItem("HTTPS protection", if (sh.httpsMode == "off") CheckStatus.Attention else CheckStatus.Secure,
            when (sh.httpsMode) { "strict" -> "Strict: HTTP sites are blocked."; "first" -> "HTTPS-First: HTTP is upgraded, with a warning if a site can't do HTTPS."; else -> "Off: HTTP sites open without warning." })

        // Tracker/ad shields
        val lvl = sh.level
        out += CheckItem("Tracker & ad protection", when { !sh.enabled -> CheckStatus.Attention; else -> CheckStatus.Secure },
            if (!sh.enabled) "Shields are turned off." else "Level: $lvl. Request blocking is domain-based (built-in lists plus any lists you added); it will not catch first-party ads or trackers served from unlisted hosts.")

        out += CheckItem("Fingerprinting protection", if (!sh.enabled) CheckStatus.Attention else if (info.documentStartScripts) CheckStatus.Secure else CheckStatus.Unsupported,
            if (info.documentStartScripts) "Applied by JavaScript hooks at page start. Determined trackers can bypass hooks (for example inside Web Workers), so this reduces, not eliminates, fingerprinting." else "This WebView cannot run start-of-page scripts, so these protections are inactive.")

        out += CheckItem("Third-party cookies", if (sh.enabled && sh.effective().thirdPartyCookies) CheckStatus.Secure else CheckStatus.Attention,
            if (sh.effective().thirdPartyCookies) "Blocked for sites without an exception." else "Allowed.")

        out += CheckItem("Storage & cache partitioning", CheckStatus.Unsupported, "Partitioning is handled inside Chromium and cannot be read or changed from an app. Separate Profiles are fully isolated instead.")
        out += CheckItem("Site Isolation / renderer sandbox", CheckStatus.Unsupported, "Android WebView does not expose these; they cannot be verified from the app.")

        // Private DNS
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val priv = runCatching { cm.getLinkProperties(cm.activeNetwork)?.isPrivateDnsActive }.getOrNull()
        out += CheckItem("Secure DNS (DNS-over-TLS/HTTPS)", when (priv) { true -> CheckStatus.Secure; false -> CheckStatus.Attention; null -> CheckStatus.NotChecked },
            when (priv) { true -> "Private DNS is active on the current network."; false -> "Private DNS is not active. WebView uses the system resolver, so set Private DNS in Android network settings."; null -> "No active network to check." })

        // Permissions
        val risky = s.permissions.filter { it.mode == "allow" && it.expiresAt == 0L && it.kind in setOf("camera", "microphone", "location") }
        out += CheckItem("Website permissions", if (risky.isEmpty()) CheckStatus.Secure else CheckStatus.Attention,
            if (risky.isEmpty()) "No site has permanent camera, microphone or location access." else "${risky.size} permanent grant(s): " + risky.take(4).joinToString { "${it.origin.substringAfter("://")} (${it.kind})" })

        // Extensions
        val active = s.extensions.filter { it.enabled }
        val flagged = active.filter { e -> e.allowedPatterns.any(ExtensionManifest::isBroad) || e.findings.isNotEmpty() }
        out += CheckItem("Extensions", when { active.isEmpty() -> CheckStatus.Secure; flagged.isEmpty() -> CheckStatus.Secure; else -> CheckStatus.Attention },
            if (active.isEmpty()) "No extensions enabled." else if (flagged.isEmpty()) "${active.size} enabled, limited to the sites you approved." else "Needs review: " + flagged.joinToString { it.name })

        // Passwords
        out += CheckItem("Password manager", when {
            !vaultExists -> CheckStatus.NotChecked
            (vaultWeak ?: 0) + (vaultReused ?: 0) > 0 -> CheckStatus.Attention
            vaultWeak == null -> CheckStatus.NotChecked
            else -> CheckStatus.Secure
        }, if (!vaultExists) "No saved passwords." else if (vaultWeak == null) "Unlock the vault in Passwords to check for weak and reused passwords." else "$vaultWeak weak, $vaultReused reused.")
        out += CheckItem("Compromised passwords", if (breachResult == null) CheckStatus.NotChecked else if (breachResult.startsWith("0")) CheckStatus.Secure else CheckStatus.Attention,
            breachResult ?: "Not run. Use “Check for breaches” in Passwords (sends only the first 5 characters of each password's SHA-1 hash).")

        // Private browsing protection
        val bio = BiometricManager.from(app).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        out += CheckItem("Private browsing lock", when {
            bio != BiometricManager.BIOMETRIC_SUCCESS -> CheckStatus.Unsupported
            s.settings.privateAutoLock -> CheckStatus.Secure
            else -> CheckStatus.Attention
        }, when { bio != BiometricManager.BIOMETRIC_SUCCESS -> "No screen lock or biometric is set up on this device."; s.settings.privateAutoLock -> "Private tabs lock when you leave the app and need biometrics or your device PIN."; else -> "Auto-lock is off." })
        out += CheckItem("Passkeys / WebAuthn", CheckStatus.NotChecked, "Support depends on the installed WebView and Google Play services; test a passkey site on your device.")
        out += CheckItem("Telemetry", CheckStatus.Secure, "TheBrowser contains no analytics or telemetry. Network requests are limited to pages you open, optional filter-list updates, optional search suggestions, Gemini when you use it, and the optional breach check.")
        return out
    }
}
