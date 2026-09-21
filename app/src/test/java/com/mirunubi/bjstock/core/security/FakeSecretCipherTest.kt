package com.mirunubi.bjstock.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FakeSecretCipherTest {
    private val cipher = FakeSecretCipher()

    @Test
    fun encryptDecrypt_roundTrip() {
        val payload = cipher.encrypt("TEST_APP_SECRET")
        assertEquals("TEST_APP_SECRET", cipher.decrypt(payload))
        assertNotEquals("TEST_APP_SECRET", payload.ciphertext)
        assertFalse(payload.iv.isBlank())
        assertEquals(1, payload.version)
    }
}
