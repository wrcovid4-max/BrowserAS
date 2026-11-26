package com.thebrowser

import android.app.Application
import android.content.Context
import com.thebrowser.core.engine.BrowserEngine
import com.thebrowser.core.engine.EngineChoice
import com.thebrowser.core.engine.EngineKind
import com.thebrowser.engine.chromium.ChromiumEngine

/**
 * Creates the engine the user chose in Settings → About → Rendering engine.
 * Gecko lives in the optional :engine-gecko module and is loaded by name, so the app still builds
 * (Chromium only) with `hydrogen.gecko=false` in gradle.properties.
 * If the chosen engine cannot start, Hydrogen falls back to Chromium and says why in Settings.
 */
object EngineFactory {
    private const val GECKO_CLASS = "com.thebrowser.engine.gecko.GeckoEngine"

    val geckoPresent: Boolean by lazy { runCatching { Class.forName(GECKO_CLASS) }.isSuccess }

    fun create(app: Application): BrowserEngine {
        if (EngineChoice.get(app, geckoPresent) == EngineKind.GECKO) {
            try {
                val e = Class.forName(GECKO_CLASS).getConstructor(Context::class.java).newInstance(app) as BrowserEngine
                EngineChoice.setError(app, null)
                return e
            } catch (t: Throwable) {
                EngineChoice.setError(app, "Gecko could not start (${t.cause?.message ?: t.message ?: t.javaClass.simpleName}). Using Chromium instead.")
            }
        }
        return ChromiumEngine(app)
    }
}
