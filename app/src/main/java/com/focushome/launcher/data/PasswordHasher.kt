package com.focushome.launcher.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Salted SHA-256, as requested. Deliberately NOT a real KDF.
 *
 * Be clear-eyed about what this buys you: it stops the password from sitting in
 * shared_prefs as readable text, and that is all. A single SHA-256 round is
 * trivially brute-forced offline by anyone who can read the file (root, or an
 * adb backup of a debuggable build). For a personal single-device app that is a
 * reasonable trade; if this ever guards something that matters, swap the hash()
 * body for PBKDF2 (javax.crypto.SecretKeyFactory, "PBKDF2WithHmacSHA256") or
 * Argon2 and bump a stored format version.
 *
 * Uses java.util.Base64, which is API 26+ only. That is exactly this app's
 * minSdk, so it is safe here; it would break if minSdk were ever lowered.
 */
object PasswordHasher {

    private const val SALT_BYTES = 16

    fun newSalt(): String {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        return Base64.getEncoder().encodeToString(salt)
    }

    fun hash(password: String, saltBase64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Base64.getDecoder().decode(saltBase64))
        digest.update(password.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    /** Constant-time compare so the check does not leak the hash byte by byte. */
    fun verify(password: String, saltBase64: String, expectedHash: String): Boolean {
        val actual = hash(password, saltBase64).toByteArray(Charsets.UTF_8)
        val expected = expectedHash.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(actual, expected)
    }
}
