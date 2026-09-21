package com.mirunubi.bjstock.core.security

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM-safe AES/GCM cipher for unit tests. This is not Android Keystore.
 */
class FakeSecretCipher : SecretCipher {
    private val key = SecretKeySpec(ByteArray(KEY_SIZE_BYTES) { 9 }, "AES")

    override fun encrypt(plainText: String): EncryptedPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(
            ciphertext = encode(ciphertext),
            iv = encode(cipher.iv),
            version = 1,
        )
    }

    override fun decrypt(payload: EncryptedPayload): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, decode(payload.iv)),
        )
        return String(cipher.doFinal(decode(payload.ciphertext)), Charsets.UTF_8)
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BYTES = 32
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
