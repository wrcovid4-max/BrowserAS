package com.thebrowser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.thebrowser.core.atc.AirTrafficControl
import com.thebrowser.core.engine.EngineListener
import com.thebrowser.core.engine.EngineSession
import com.thebrowser.core.engine.EngineSettings
import com.thebrowser.core.model.DEFAULT_PROFILE_ID
import com.thebrowser.core.shield.FilterEngine
import com.thebrowser.core.shield.SecurityBlock
import com.thebrowser.core.shield.ShieldConfig
import com.thebrowser.core.shield.ShieldEventKind

/**
 * Little-Arc-style lightweight window: one page, no tabs or sidebar, its own task so it can float
 * in multi-window / freeform / desktop modes. Uses the default Profile. "Open in TheBrowser"
 * hands the page to the full browser.
 */
class LittleActivity : ComponentActivity() {
    private var session: EngineSession? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TheBrowserApp).container
        val start = intent.getStringExtra(EXTRA_URL).orEmpty()
        var url by mutableStateOf(start)
        var progress by mutableIntStateOf(0)
        var title by mutableStateOf("")
        var block by mutableStateOf<SecurityBlock?>(null)

        val listener = object : EngineListener {
            override fun onUrlChanged(url2: String) { url = url2 }
            override fun onTitleChanged(t: String) { title = t }
            override fun onProgress(percent: Int) { progress = percent }
            override fun onNavState(canGoBack: Boolean, canGoForward: Boolean) {}
            override fun onPageFinished(url: String, title: String) {}
            override fun onOpenInNewTab(url: String) { session?.load(url) }
            override fun onDownload(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {}
            override fun onPermissionRequest(origin: String, kinds: Set<String>, decide: (Boolean) -> Unit) = decide(false)
            override fun onFileChooser(intent: Intent, complete: (Int, Intent?) -> Unit) = complete(RESULT_CANCELED, null)
            override fun onCustomView(view: View?, onHidden: (() -> Unit)?) {}
            override fun onPageCommitted(url: String) { block = null }
            override fun onShieldEvent(kind: ShieldEventKind, host: String) {}
            override fun onSecurityBlock(b: SecurityBlock) { block = b }
            override fun onCredentialSubmitted(origin: String, username: String, password: String) {}
        }
        // Little windows honour the user's protection level (built-in lists; no password capture, no extensions).
        val sh = container.stateStore.loadState().settings.shield
        val shield = ShieldConfig(sh.enabled, sh.effective(), sh.httpsMode, sh.webRtc, sh.gpc, sh.dnt, sh.siteOff.toSet(), FilterEngine(), java.util.UUID.randomUUID().toString(), isPrivate = false)
        val s = container.engine.createSession(this, DEFAULT_PROFILE_ID, EngineSettings(shield = shield, blockPopups = true), listener)
        session = s
        if (start.isNotBlank()) s.load(start)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ finish() }) { Icon(Icons.Default.Close, "Close") }
                            val secure = url.startsWith("https://")
                            Icon(if (secure) Icons.Default.Lock else Icons.Default.Warning, null, Modifier.size(16.dp))
                            Text(AirTrafficControl.hostOf(url) ?: url, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton({
                                startActivity(Intent(this@LittleActivity, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url)))
                                finish()
                            }) { Text("Open in TheBrowser") }
                        }
                        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        block?.let { b ->
                            AlertDialog(
                                onDismissRequest = { b.back(); finish() }, title = { Text(b.title) },
                                text = { Text(b.explanation + "\n\n" + b.details.joinToString("\n") { "• $it" }) },
                                confirmButton = { TextButton({ b.back(); finish() }) { Text("Back to safety") } },
                                dismissButton = { if (b.proceed != null) TextButton({ val p = b.proceed; block = null; p?.invoke() }) { Text("Continue (unsafe)") } },
                            )
                        }
                        AndroidView(factory = { s.view }, modifier = Modifier.fillMaxSize(), onRelease = { (it.parent as? android.view.ViewGroup)?.removeView(it) })
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        session?.destroy()
        super.onDestroy()
    }

    companion object { const val EXTRA_URL = "url" }
}
