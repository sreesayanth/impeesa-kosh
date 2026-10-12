package com.impeesa.finance.feature.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {
    @Test
    fun initialsUseFirstTwoWords() {
        assertEquals("AK", Profile("Aarav Kumar").initials)
        assertEquals("AK", Profile("aarav kumar sharma").initials)
        assertEquals("A", Profile("Aarav").initials)
        assertEquals("", Profile("   ").initials)
    }

    @Test
    fun firstNameIsTheFirstWord() {
        assertEquals("Aarav", Profile("  Aarav Kumar ").firstName)
    }

    @Test
    fun cleanNameTrimsCollapsesAndCaps() {
        assertEquals("Aarav Kumar", Profile.cleanName("  Aarav   Kumar  "))
        assertEquals(Profile.MAX_NAME, Profile.cleanName("x".repeat(50)).length)
        assertEquals("", Profile.cleanName("   "))
    }

    @Test
    fun blankNameMeansNoName() {
        assertFalse(Profile("  ").hasName)
        assertTrue(Profile("Aarav").hasName)
    }
}
