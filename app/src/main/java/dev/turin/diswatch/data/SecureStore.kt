package dev.turin.diswatch.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore AES-GCM, private no-backup directory, atomic replacement. No plaintext preferences. */
class SecureStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "vault").apply { mkdirs() }
    private val alias = "diswatch.v1"
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun write(name: String, bytes: ByteArray) {
        require(name.matches(Regex("[a-z0-9_-]+")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(name.toByteArray()) }
        val payload = cipher.iv + cipher.doFinal(bytes)
        val file = AtomicFile(File(directory, name))
        val output = file.startWrite()
        try { output.write(payload); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
    }
    @Synchronized fun read(name: String): ByteArray? = runCatching {
        val bytes = AtomicFile(File(directory, name)).readFully()
        require(bytes.size in 28..2_000_000)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(name.toByteArray()); doFinal(bytes, 12, bytes.size - 12)
        }
    }.getOrNull()
    @Synchronized fun clear() { directory.listFiles()?.forEach { it.delete() } }
}
