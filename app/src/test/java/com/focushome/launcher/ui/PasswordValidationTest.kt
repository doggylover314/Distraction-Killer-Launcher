package com.focushome.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PasswordValidationTest {

    @Test
    fun `a matching password of sufficient length is accepted`() {
        assertNull(validateNewPassword("1234", "1234"))
        assertNull(validateNewPassword("a long passphrase", "a long passphrase"))
    }

    @Test
    fun `too short is rejected`() {
        assertNotNull(validateNewPassword("abc", "abc"))
        assertNotNull(validateNewPassword("", ""))
    }

    @Test
    fun `mismatch is rejected and reported separately from length`() {
        assertEquals("The two entries do not match.", validateNewPassword("abcd", "abce"))
    }

    @Test
    fun `length is checked before matching`() {
        // Both problems at once should mention the length, the fixable one.
        assertEquals(
            "Use at least $MIN_PASSWORD_LENGTH characters.",
            validateNewPassword("ab", "xy"),
        )
    }

    @Test
    fun `whitespace is treated as a real character, not trimmed away`() {
        assertNull(validateNewPassword("    ", "    "))
        assertNotNull(validateNewPassword(" abcd", "abcd"))
    }
}
