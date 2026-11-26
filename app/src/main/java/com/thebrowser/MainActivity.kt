package com.thebrowser

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.thebrowser.ui.BrowserApp
import com.thebrowser.ui.TheBrowserTheme

class MainActivity : FragmentActivity() {
    private val vm: BrowserViewModel by viewModels()

    private var pendingChooser: ((Int, Intent?) -> Unit)? = null
    private var pendingPermissions: ((Boolean) -> Unit)? = null

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingChooser?.invoke(it.resultCode, it.data); pendingChooser = null
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Location: either fine or coarse is enough; camera/mic: all requested must be granted.
        pendingPermissions?.invoke(result.isNotEmpty() && result.values.any { it } && (result.keys.all { it.contains("LOCATION") } || result.values.all { it }))
        pendingPermissions = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.uiContext = this
        vm.fileChooserLauncher = { intent, done -> pendingChooser = done; chooser.launch(intent) }
        vm.runtimePermissionLauncher = { perms, done -> pendingPermissions = done; permissions.launch(perms) }
        vm.authenticate = { title, subtitle, result -> authenticate(title, subtitle, result) }
        setContent { TheBrowserTheme(vm) { BrowserApp(vm) } }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Biometric with device PIN / pattern / password fallback. If the device has no lock at all, nothing can be protected, so it reports success (Security Check flags this). */
    private fun authenticate(title: String, subtitle: String, result: (Boolean) -> Unit) {
        val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) { result(true); return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = result(true)
            override fun onAuthenticationError(code: Int, msg: CharSequence) = result(false)
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle).setAllowedAuthenticators(auth).build())
    }

    private fun handle(i: Intent?) {
        i ?: return
        if (i.getBooleanExtra(EXTRA_STANDALONE, false)) vm.standalone = true
        when (i.action) {
            Intent.ACTION_VIEW -> vm.handleExternalIntent(i.dataString)
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let(vm::handleSharedText)
            Intent.ACTION_WEB_SEARCH -> i.getStringExtra("query")?.let(vm::handleSharedText)
            Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.let(vm::askGeminiAbout)
            ACTION_NEW_TAB -> vm.newTab()
            ACTION_NEW_PRIVATE_TAB -> vm.newTab(private = true)
            ACTION_ASK_GEMINI -> vm.geminiOpen = true
            ACTION_SWITCH_SPACE -> i.getStringExtra("spaceId")?.let(vm::switchSpace)
        }
    }

    // Hardware-keyboard shortcuts must be caught here: a focused WebView would swallow them.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = vm.onShortcut(event) || super.dispatchKeyEvent(event)

    // Picture-in-Picture: leaving the app while a web video is fullscreen keeps it playing in a small window.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.customView != null) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip = isInPictureInPictureMode
    }

    override fun onDestroy() { if (vm.uiContext === this) vm.uiContext = null; super.onDestroy() }

    override fun onStop() {
        super.onStop()
        vm.captureThumbs()
        vm.onAppBackgrounded() // locks private tabs and the password vault
    }

    companion object {
        const val EXTRA_STANDALONE = "tb_standalone"
        const val ACTION_NEW_TAB = "com.thebrowser.NEW_TAB"
        const val ACTION_NEW_PRIVATE_TAB = "com.thebrowser.NEW_PRIVATE_TAB"
        const val ACTION_ASK_GEMINI = "com.thebrowser.ASK_GEMINI"
        const val ACTION_SWITCH_SPACE = "com.thebrowser.SWITCH_SPACE"
    }
}
