package com.mirunubi.bjstock.core.security

data class EncryptedPayload(
    val ciphertext: String,
    val iv: String,
    val version: Int = 1,
)

interface SecretCipher {
    fun encrypt(plainText: String): EncryptedPayload
    fun decrypt(payload: EncryptedPayload): String
}
