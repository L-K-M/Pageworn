package ch.lkmc.pageworn.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Token pairs encrypted with an AES-GCM key that never leaves Android Keystore. Each address has
 * one file holding the IV and the ciphertext of the whole pair, written to a temporary file and
 * renamed, so a pair is replaced as a unit. The files live in no-backup storage; the app backs up
 * nothing anyway, and the key cannot be restored on another device.
 */
class KeystoreTokenVault(context: Context) : TokenVault {
    private val dir = File(context.noBackupFilesDir, "credentials").apply { mkdirs() }
    private val lock = Any()

    @Serializable
    private data class Stored(val access: String, val refresh: String)

    override fun read(addressId: String): TokenPair? = synchronized(lock) {
        val file = fileFor(addressId)
        if (!file.isFile) return null
        val bytes = Base64.decode(file.readText(), Base64.NO_WRAP)
        if (bytes.size <= IV_BYTES) return null
        // A key that Keystore lost (for example after a lock-screen reset) makes the pair
        // unreadable: the address then needs a new sign-in, as an expired session does.
        val plain = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        }.getOrNull() ?: return null
        val stored = Json.decodeFromString(Stored.serializer(), plain.decodeToString())
        TokenPair(stored.access, stored.refresh)
    }

    override fun write(addressId: String, tokens: TokenPair) = synchronized(lock) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = Json.encodeToString(Stored.serializer(), Stored(tokens.accessToken, tokens.refreshToken)).encodeToByteArray()
        val sealed = cipher.iv + cipher.doFinal(plain)
        val target = fileFor(addressId)
        val temp = File(dir, "${target.name}.tmp")
        temp.writeText(Base64.encodeToString(sealed, Base64.NO_WRAP))
        check(temp.renameTo(target)) { "Could not store the credential for an address" }
    }

    override fun delete(addressId: String) {
        synchronized(lock) { fileFor(addressId).delete() }
    }

    override fun deleteAll() {
        synchronized(lock) { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun fileFor(addressId: String): File {
        // Address IDs are local UUIDs; refuse anything that could leave the directory.
        require(addressId.matches(SAFE_ID)) { "Unexpected address ID" }
        return File(dir, "$addressId.bin")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pageworn-credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val SAFE_ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}
