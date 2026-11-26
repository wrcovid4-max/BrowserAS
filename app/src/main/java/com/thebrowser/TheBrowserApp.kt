package com.thebrowser

import android.app.Application
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import android.content.Intent
import com.thebrowser.core.ai.GeminiClient
import com.thebrowser.core.ai.SecretStore
import com.thebrowser.core.engine.BrowserEngine
import com.thebrowser.core.model.Space
import com.thebrowser.core.state.StateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual DI, same pattern as the other projects. Everything is built lazily. */
class AppContainer(private val app: Application) {
    val stateStore by lazy { StateStore(app) }
    val engine: BrowserEngine by lazy { EngineFactory.create(app) }
    val secrets by lazy { SecretStore(app) }
    val extensions by lazy { ExtensionManager(app) }
    val vault by lazy { com.thebrowser.core.vault.CredentialVault(app) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Set by the agent hub so the service's Stop button can cancel every running task. */
    var hubStopper: (() -> Unit)? = null
    val gemini by lazy {
        GeminiClient(
            keyProvider = { secrets.get(SecretStore.GEMINI_KEY) },
            baseUrl = BuildConfig.GEMINI_PROXY_URL.ifBlank { GeminiClient.DEFAULT_BASE },
        )
    }
}

class TheBrowserApp : Application() {
    val container by lazy { AppContainer(this) }

    /** Launcher long-press shortcuts: one per Space (max 3 dynamic shortcuts). */
    fun publishSpaceShortcuts(spaces: List<Space>) {
        runCatching {
            val list = spaces.take(3).map { sp ->
                ShortcutInfoCompat.Builder(this, "space-${sp.id}")
                    .setShortLabel("${sp.emoji} ${sp.name}")
                    .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                    .setIntent(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SWITCH_SPACE).putExtra("spaceId", sp.id))
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(this, list)
        }
    }
}
