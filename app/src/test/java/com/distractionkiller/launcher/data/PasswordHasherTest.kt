package com.distractionkiller.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {

    @Test
    fun `same password and salt hash to the same value`() {
        val salt = PasswordHasher.newSalt()
        assertEquals(PasswordHasher.hash("hunter2", salt), PasswordHasher.hash("hunter2", salt))
    }

    @Test
    fun `same password with different salts hashes differently`() {
        val first = PasswordHasher.hash("hunter2", PasswordHasher.newSalt())
        val second = PasswordHasher.hash("hunter2", PasswordHasher.newSalt())
        assertNotEquals(first, second)
    }

    @Test
    fun `salts are not reused`() {
        val salts = List(50) { PasswordHasher.newSalt() }
        assertEquals(salts.size, salts.toSet().size)
    }

    @Test
    fun `verify accepts the right password`() {
        val salt = PasswordHasher.newSalt()
        val hash = PasswordHasher.hash("correct horse", salt)
        assertTrue(PasswordHasher.verify("correct horse", salt, hash))
    }

    @Test
    fun `verify rejects the wrong password`() {
        val salt = PasswordHasher.newSalt()
        val hash = PasswordHasher.hash("correct horse", salt)
        assertFalse(PasswordHasher.verify("correct hors", salt, hash))
        assertFalse(PasswordHasher.verify("", salt, hash))
        assertFalse(PasswordHasher.verify("Correct Horse", salt, hash))
    }

    @Test
    fun `hash does not contain the password`() {
        val salt = PasswordHasher.newSalt()
        val hash = PasswordHasher.hash("plaintextpassword", salt)
        assertFalse(hash.contains("plaintextpassword"))
    }

    @Test
    fun `unicode passwords round trip`() {
        val salt = PasswordHasher.newSalt()
        val password = "pässwörd-é中文"
        assertTrue(PasswordHasher.verify(password, salt, PasswordHasher.hash(password, salt)))
    }
}
