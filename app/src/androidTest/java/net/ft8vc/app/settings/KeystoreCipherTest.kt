package net.ft8vc.app.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreCipherTest {

    @Test
    fun encryptDecrypt_roundTrips() {
        val encoded = KeystoreCipher.encrypt("ABCD-1234-EFGH-5678")
        checkNotNull(encoded)
        assertEquals("ABCD-1234-EFGH-5678", KeystoreCipher.decrypt(encoded))
    }

    @Test
    fun encrypt_producesFreshCiphertextEachTime() {
        val a = KeystoreCipher.encrypt("same")
        val b = KeystoreCipher.encrypt("same")
        assertNotEquals(a, b) // fresh IV per encryption
    }

    @Test
    fun decrypt_garbageReturnsNull() {
        assertNull(KeystoreCipher.decrypt("not base64 !!!"))
        assertNull(KeystoreCipher.decrypt("aGVsbG8="))
    }
}
