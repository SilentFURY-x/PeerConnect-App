package com.fury.peerconnect.logic

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecurityHelper {
    // HARDCODED KEY (32 chars for AES-256)
    // In production, use Diffie-Hellman to derive this.
    private const val SECRET_KEY = "12345678901234567890123456789012"
    private const val ALGORITHM = "AES/CBC/PKCS5Padding"

    fun encrypt(plainText: String): String {
        try {
            val key = SecretKeySpec(SECRET_KEY.toByteArray(), "AES")
            // Use a zero IV for simplicity in demo (Not prod safe, but stable for demo)
            val iv = IvParameterSpec(ByteArray(16))

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key, iv)

            val encryptedBytes = cipher.doFinal(plainText.toByteArray())
            return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            return plainText // Fallback (Fail open for demo)
        }
    }

    fun decrypt(cipherText: String): String {
        try {
            val key = SecretKeySpec(SECRET_KEY.toByteArray(), "AES")
            val iv = IvParameterSpec(ByteArray(16))

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, iv)

            val decodedBytes = Base64.decode(cipherText, Base64.NO_WRAP)
            val plainBytes = cipher.doFinal(decodedBytes)
            return String(plainBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            return "[Decryption Failed]"
        }
    }
}