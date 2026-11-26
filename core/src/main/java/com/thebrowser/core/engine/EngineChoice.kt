package com.thebrowser.core.engine

import android.content.Context

/** Rendering engines Hydrogen knows about. Not every engine exists on every platform. */
enum class EngineKind(val id: String, val label: String) {
    CHROMIUM("chromium", "Chromium"),
    GECKO("gecko", "Gecko"),
    WEBKIT("webkit", "WebKit");

    companion object { fun fromId(id: String?): EngineKind? = entries.firstOrNull { it.id == id } }
}

/**
 * The user's engine choice. Kept in plain SharedPreferences (not in the async state file) because the
 * engine is created once at process start, before the state store has loaded. Changing it takes effect
 * after Hydrogen restarts, since a process can only host one engine safely.
 */
object EngineChoice {
    private const val PREFS = "hydrogen_engine"
    private const val KEY = "engine"
    private const val KEY_ERROR = "engine_error"

    /** Android has Chromium (System WebView) and, when the optional engine-gecko module is built in, Gecko. WebKit exists only on Apple platforms. */
    fun isAvailable(kind: EngineKind, geckoPresent: Boolean): Boolean = when (kind) {
        EngineKind.CHROMIUM -> true
        EngineKind.GECKO -> geckoPresent
        EngineKind.WEBKIT -> false
    }

    fun get(context: Context, geckoPresent: Boolean): EngineKind {
        val saved = EngineKind.fromId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
        return if (saved != null && isAvailable(saved, geckoPresent)) saved else EngineKind.CHROMIUM
    }

    /** What the user picked, even if it is not available right now (so the UI can say so). */
    fun requested(context: Context): EngineKind? =
        EngineKind.fromId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun set(context: Context, kind: EngineKind) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, kind.id).remove(KEY_ERROR).apply()
    }

    /** Set when the chosen engine failed to start and Hydrogen fell back to Chromium. */
    fun lastError(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ERROR, null)
    fun setError(context: Context, message: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { if (message == null) remove(KEY_ERROR) else putString(KEY_ERROR, message) }.apply()
    }
}
