package com.urtv.player.data

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-encrypted backup container. The passphrase never leaves the TV and is never stored.
 * Layout: "URB1" | iterations (4 bytes) | salt (16) | IV (12) | AES-256-GCM(ciphertext + tag).
 * Key = PBKDF2-HMAC-SHA256(passphrase, salt, iterations). The header is authenticated as GCM associated data,
 * so any change to the file (or a wrong passphrase) makes decryption fail instead of producing garbage.
 */
object Backup {
    private val MAGIC = "URB1".toByteArray()
    const val DEFAULT_ITERATIONS = 200_000
    private const val MAX_ITERATIONS = 2_000_000
    const val MIN_PASS = 8

    private fun key(pass: CharArray, salt: ByteArray, iter: Int): SecretKeySpec {
        val spec = PBEKeySpec(pass, salt, iter, 256)
        try { return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }

    fun encrypt(plain: ByteArray, pass: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        val r = SecureRandom()
        val salt = ByteArray(16).also(r::nextBytes); val iv = ByteArray(12).also(r::nextBytes)
        val header = MAGIC + byteArrayOf((iterations ushr 24).toByte(), (iterations ushr 16).toByte(), (iterations ushr 8).toByte(), iterations.toByte()) + salt + iv
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(pass, salt, iterations), GCMParameterSpec(128, iv)); updateAAD(header) }
        return header + c.doFinal(plain)
    }

    /** Returns the plaintext, or null for a wrong passphrase, a damaged file, or something that is not a backup. */
    fun decrypt(blob: ByteArray, pass: CharArray): ByteArray? {
        if (blob.size < 4 + 4 + 16 + 12 + 16 || !blob.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val iter = ((blob[4].toInt() and 0xFF) shl 24) or ((blob[5].toInt() and 0xFF) shl 16) or ((blob[6].toInt() and 0xFF) shl 8) or (blob[7].toInt() and 0xFF)
        if (iter < 10_000 || iter > MAX_ITERATIONS) return null          // refuse absurd work factors from a hostile file
        val header = blob.copyOfRange(0, 36)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(pass, blob.copyOfRange(8, 24), iter), GCMParameterSpec(128, blob.copyOfRange(24, 36))); updateAAD(header)
            }
            c.doFinal(blob, 36, blob.size - 36)
        } catch (_: AEADBadTagException) { null } catch (_: Exception) { null }
    }
}
