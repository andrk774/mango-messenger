package com.mango.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

/** Runs in the library test APK, never in the installed Mango application's UID. */
@RunWith(AndroidJUnit4::class)
class LocalStorageCipherDeviceTest {
    private fun alias() = "mango_test_" + UUID.randomUUID()
    private fun mustFail(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Operation must reject unauthenticated data", failed)
    }

    @Test fun roundTripNonceAndPersistentKey() {
        val name = alias()
        val cipher = LocalStorageCipher(name)
        val plaintext = "local history fixture".toByteArray()
        val binding = "account-a/database-key/v1".toByteArray()
        val first = cipher.encrypt(plaintext, binding)
        assertFalse(first.contentEquals(cipher.encrypt(plaintext, binding)))
        assertArrayEquals(plaintext, LocalStorageCipher(name).decrypt(first, binding))
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNull("Keystore secret must not be exportable", store.getKey(name, null).encoded)
    }

    @Test fun tamperingAccountSwapTruncationAndWrongKeyAreRejected() {
        val cipher = LocalStorageCipher(alias())
        val binding = "account-a/file-123/v1".toByteArray()
        val encrypted = cipher.encrypt(byteArrayOf(1, 2, 3), binding)
        val changed = encrypted.clone().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        mustFail { cipher.decrypt(changed, binding) }
        mustFail { cipher.decrypt(encrypted, "account-b/file-123/v1".toByteArray()) }
        mustFail { cipher.decrypt(encrypted.copyOf(10), binding) }
        val other = LocalStorageCipher(alias())
        other.encrypt(byteArrayOf(4), binding)
        mustFail { other.decrypt(encrypted, binding) }
    }

    @Test fun missingKeyReadDoesNotCreateReplacement() {
        val name = alias()
        val binding = "account-a/database-key/v1".toByteArray()
        val encrypted = LocalStorageCipher(alias()).encrypt(byteArrayOf(1), binding)
        mustFail { LocalStorageCipher(name).decrypt(encrypted, binding) }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(store.containsAlias(name))
    }
}
