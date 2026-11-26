package com.thebrowser.core.sync

import com.thebrowser.core.model.*
import com.thebrowser.core.state.AppJson
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/** What the user chose to sync. Sensitive categories default OFF. Passwords, cookies and the Gemini key are never included. */
@Serializable
data class SyncScope(
    val bookmarks: Boolean = true,
    val spaces: Boolean = true,
    val groups: Boolean = true,
    val rules: Boolean = true,
    val readingList: Boolean = true,
    val notes: Boolean = false,
    val settings: Boolean = true,
    val history: Boolean = false,
)

@Serializable
data class SyncPayload(
    val version: Int = 1,
    val profiles: List<Profile> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val spaces: List<Space> = emptyList(),
    val groups: List<TabGroup> = emptyList(),
    val rules: List<AtcRule> = emptyList(),
    val articles: List<SavedArticle> = emptyList(),
    val notes: List<Note> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val settings: Settings? = null,
)

/**
 * File-based, local-first sync: export to a file in a folder you already sync (Drive, Dropbox,
 * iCloud Drive via Files…). Merge is union-by-id, existing local items win. There is no
 * account, no server and no conflict resolution beyond that.
 */
object Sync {
    fun export(s: BrowserState, scope: SyncScope): SyncPayload = SyncPayload(
        profiles = s.profiles,
        bookmarks = if (scope.bookmarks) s.bookmarks else emptyList(),
        spaces = if (scope.spaces) s.spaces else emptyList(),
        groups = if (scope.groups) s.groups else emptyList(),
        rules = if (scope.rules) s.rules else emptyList(),
        articles = if (scope.readingList) s.articles else emptyList(),
        notes = if (scope.notes) s.notes else emptyList(),
        history = if (scope.history) s.history else emptyList(),
        settings = if (scope.settings) s.settings.copy(downloadTreeUri = null) else null,
    )

    fun merge(local: BrowserState, remote: SyncPayload): BrowserState {
        fun <T> union(a: List<T>, b: List<T>, id: (T) -> String) = a + b.filter { r -> a.none { id(it) == id(r) } }
        val profiles = union(local.profiles, remote.profiles) { it.id }
        val knownProfiles = profiles.map { it.id }.toSet()
        return local.copy(
            profiles = profiles,
            spaces = union(local.spaces, remote.spaces.filter { it.profileId in knownProfiles }) { it.id },
            groups = union(local.groups, remote.groups) { it.id },
            rules = union(local.rules, remote.rules) { it.id },
            bookmarks = union(local.bookmarks, remote.bookmarks.filter { it.profileId in knownProfiles }) { it.id },
            articles = union(local.articles, remote.articles) { it.id },
            notes = union(local.notes, remote.notes) { it.id },
            history = union(local.history, remote.history) { it.id }.sortedByDescending { it.time }.take(2000),
            settings = remote.settings ?: local.settings,
        )
    }

    fun toJson(p: SyncPayload): String = AppJson.encodeToString(SyncPayload.serializer(), p)
    fun fromJson(s: String): SyncPayload = AppJson.decodeFromString(SyncPayload.serializer(), s)

    // Optional passphrase encryption (PBKDF2-HMAC-SHA256 + AES-256-GCM).
    private fun key(pass: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(f.generateSecret(PBEKeySpec(pass.toCharArray(), salt, 210_000, 256)).encoded, "AES")
    }

    fun encrypt(text: String, pass: String): String {
        val rnd = SecureRandom(); val salt = ByteArray(16).also(rnd::nextBytes); val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv))
        return "TBSYNC1:" + Base64.encodeToString(salt + iv + c.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    fun isEncrypted(s: String) = s.startsWith("TBSYNC1:")

    fun decrypt(text: String, pass: String): String {
        val b = Base64.decode(text.removePrefix("TBSYNC1:"), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(pass, b.copyOfRange(0, 16)), GCMParameterSpec(128, b.copyOfRange(16, 28)))
        return String(c.doFinal(b.copyOfRange(28, b.size)))
    }
}
