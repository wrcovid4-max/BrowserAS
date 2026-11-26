package com.thebrowser.core.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import com.thebrowser.core.state.AppJson
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Thrown when the user must authenticate (biometric / device credential) before the vault can be used. */
class VaultLockedException : Exception("Authenticate to use the password manager")
class VaultUnavailableException(msg: String) : Exception(msg)

/**
 * Passwords are encrypted with an AES-256-GCM key that lives in the Android Keystore, is never
 * exportable, and can only be used within 30 seconds of a biometric / device-credential unlock.
 * No custom cryptography: this is the platform API used as intended. Passwords are never logged.
 * If the user changes biometrics the key is invalidated and the vault becomes unreadable on purpose
 * (restore from an encrypted export).
 */
class CredentialVault(context: Context) {
    private val file = File(context.filesDir, "vault.bin")
    private val originIndex = File(context.filesDir, "vault_origins.txt")
    private val saltFile = File(context.filesDir, "vault_salt.bin")

    /** The index holds salted hashes, so the file on disk no longer lists which sites you have logins for. The salt is random per install. */
    private fun salt(): ByteArray = saltFile.takeIf { it.exists() }?.readBytes()
        ?: ByteArray(16).also { java.security.SecureRandom().nextBytes(it); saltFile.writeBytes(it) }
    private fun originToken(origin: String): String =
        java.security.MessageDigest.getInstance("SHA-256").apply { update(salt()) }.digest(origin.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return try {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build(),
            )
            gen.generateKey()
        } catch (e: Exception) {
            throw VaultUnavailableException("A screen lock (PIN, pattern, password or biometric) is required to use the password manager.")
        }
    }

    fun exists() = file.exists()

    /** True if a login is saved for this origin. Checked against salted hashes, so it needs no authentication and reveals no site names. */
    fun hasCredentialFor(origin: String): Boolean = originIndex.takeIf { it.exists() }?.readLines()?.contains(originToken(origin)) == true

    fun load(): List<Credential> {
        if (!file.exists()) return emptyList()
        try {
            val k = key(false) ?: throw VaultUnavailableException("Vault key is gone (biometrics changed or app data was restored). Restore from an encrypted export.")
            val raw = Base64.decode(file.readText(), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            return AppJson.decodeFromString(ListSerializer(Credential.serializer()), String(c.doFinal(raw.copyOfRange(12, raw.size))))
        } catch (e: UserNotAuthenticatedException) { throw VaultLockedException() }
        catch (e: KeyPermanentlyInvalidatedException) { throw VaultUnavailableException("Biometrics changed, so the vault key was invalidated. Restore from an encrypted export.") }
    }

    fun save(list: List<Credential>) {
        try {
            val k = key(true)!!
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k)
            val out = c.iv + c.doFinal(AppJson.encodeToString(ListSerializer(Credential.serializer()), list).toByteArray())
            file.writeText(Base64.encodeToString(out, Base64.NO_WRAP))
            originIndex.writeText(list.map { originToken(it.origin) }.distinct().joinToString("\n"))
        } catch (e: UserNotAuthenticatedException) { throw VaultLockedException() }
        catch (e: KeyPermanentlyInvalidatedException) { throw VaultUnavailableException("Biometrics changed, so the vault key was invalidated.") }
    }

    /** Deletes all saved passwords and the key. Cannot be undone. */
    fun wipe() {
        file.delete(); originIndex.delete(); saltFile.delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    companion object { private const val ALIAS = "thebrowser_vault_key" }
}
