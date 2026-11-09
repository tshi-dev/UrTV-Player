package com.urtv.player.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

/**
 * Files that can hold credentials (playlists, stream URLs, guide URLs, watch list) are written encrypted with an
 * AES key that lives in the Android Keystore (never leaves secure hardware where available, never in the app's files).
 * Format: "UR1" + 16-byte IV + AES/CTR stream. Legacy plain files are still read (and rewritten encrypted on next save).
 * If the device has no usable Keystore the file is written plain: the app keeps working, the sandbox still protects it.
 */
object Secrets {
    private const val ALIAS = "urtv_files_v1"
    private val MAGIC = byteArrayOf('U'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())
    private const val TRANSFORM = "AES/CTR/NoPadding"

    private val key: SecretKey? by lazy {
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CTR).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
                generateKey()
            }
        }.getOrNull()
    }

    /** Streaming writer; close it to finish. Writes to a temp file and renames, so a crash never leaves a half file. */
    fun write(file: File, block: (OutputStream) -> Unit) {
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().buffered().use { raw ->
            val k = key
            if (k == null) block(raw) else {
                val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, k) }
                raw.write(MAGIC); raw.write(c.iv)
                val out = CipherOutputStream(raw, c)
                block(out); out.flush(); out.close()
            }
        }
        file.delete(); tmp.renameTo(file)
    }

    fun <T> read(file: File, block: (InputStream) -> T): T? {
        if (!file.exists()) return null
        return runCatching {
            file.inputStream().buffered().use { raw ->
                raw.mark(3)
                val head = ByteArray(3); val n = raw.read(head); raw.reset()
                if (n == 3 && head.contentEquals(MAGIC)) {
                    val k = key ?: return@use null
                    raw.skip(3); val iv = ByteArray(16); if (raw.read(iv) != 16) return@use null
                    val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, k, IvParameterSpec(iv)) }
                    block(CipherInputStream(raw, c))
                } else block(raw)
            }
        }.getOrNull()
    }

    fun writeText(file: File, text: String) = write(file) { it.write(text.toByteArray(Charsets.UTF_8)) }
    fun readText(file: File): String? = read(file) { it.readBytes().toString(Charsets.UTF_8) }
}
