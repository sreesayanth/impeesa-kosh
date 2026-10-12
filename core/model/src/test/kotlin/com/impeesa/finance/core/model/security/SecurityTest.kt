package com.impeesa.finance.core.model.security

import com.impeesa.finance.core.model.backup.BackupException
import com.impeesa.finance.core.model.backup.BackupFormat
import com.impeesa.finance.core.model.backup.Csv
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PinHasherTest {
    @Test
    fun rightPinVerifiesWrongPinDoesNot() {
        val stored = PinHasher.hash("4821", iterations = 2_000)
        assertTrue(PinHasher.verify("4821", stored))
        assertFalse(PinHasher.verify("4822", stored))
        assertFalse(PinHasher.verify("48210", stored))
    }

    @Test
    fun saltMakesEqualPinsHashDifferently() {
        val a = PinHasher.hash("123456", iterations = 2_000)
        val b = PinHasher.hash("123456", iterations = 2_000)
        assertFalse(a.hash.contentEquals(b.hash))
    }

    @Test
    fun onlyFourToSixDigits() {
        assertTrue(PinHasher.isValid("1234"))
        assertTrue(PinHasher.isValid("123456"))
        assertFalse(PinHasher.isValid("123"))
        assertFalse(PinHasher.isValid("1234567"))
        assertFalse(PinHasher.isValid("12a4"))
    }
}

class LockPolicyTest {
    @Test
    fun locksAfterTheTimeout() {
        assertTrue(LockPolicy.shouldLock(enabled = true, backgroundedAt = null, now = 0, timeoutSeconds = 60))
        assertFalse(LockPolicy.shouldLock(enabled = true, backgroundedAt = 1_000, now = 50_000, timeoutSeconds = 60))
        assertTrue(LockPolicy.shouldLock(enabled = true, backgroundedAt = 1_000, now = 61_000, timeoutSeconds = 60))
        assertTrue(LockPolicy.shouldLock(enabled = true, backgroundedAt = 1_000, now = 1_000, timeoutSeconds = 0))
        assertFalse(LockPolicy.shouldLock(enabled = false, backgroundedAt = null, now = 0, timeoutSeconds = 0))
    }

    @Test
    fun backoffStartsAfterFiveFailuresAndDoubles() {
        assertEquals(0L, LockPolicy.lockoutMillis(4))
        assertEquals(30_000L, LockPolicy.lockoutMillis(5))
        assertEquals(60_000L, LockPolicy.lockoutMillis(6))
        assertEquals(120_000L, LockPolicy.lockoutMillis(7))
        assertEquals(3_600_000L, LockPolicy.lockoutMillis(40))
    }
}

class BackupFormatTest {
    private val payload = """{"schemaVersion":6,"tables":{"accounts":{"rows":[[1,"HDFC"]]}}}""".toByteArray()
    private val pass = "correct horse battery".toCharArray()

    @Test
    fun roundTrip() {
        val file = BackupFormat.encrypt(payload, pass, iterations = 2_000)
        assertArrayEquals(payload, BackupFormat.decrypt(file, pass))
        assertEquals(BackupFormat.VERSION, BackupFormat.peekVersion(file))
    }

    @Test
    fun wrongPassphraseIsReported() {
        val file = BackupFormat.encrypt(payload, pass, iterations = 2_000)
        try {
            BackupFormat.decrypt(file, "wrong horse battery".toCharArray())
            fail()
        } catch (_: BackupException.WrongPassphrase) {
        }
    }

    @Test
    fun tamperingWithHeaderOrBodyIsDetected() {
        val file = BackupFormat.encrypt(payload, pass, iterations = 2_000)
        val body = file.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val header = file.copyOf().also { it[20] = (it[20] + 1).toByte() } // inside the salt
        listOf(body, header).forEach { broken ->
            try {
                BackupFormat.decrypt(broken, pass)
                fail()
            } catch (_: BackupException.WrongPassphrase) {
            }
        }
    }

    @Test
    fun otherFilesAndNewerVersionsAreRefused() {
        try {
            BackupFormat.decrypt("date,amount\n".toByteArray(), pass)
            fail()
        } catch (_: BackupException.NotABackup) {
        }
        val newer = BackupFormat.encrypt(payload, pass, iterations = 2_000).also { it[6] = 9 }
        try {
            BackupFormat.decrypt(newer, pass)
            fail()
        } catch (e: BackupException.UnsupportedVersion) {
            assertEquals(9, e.version)
        }
    }

    @Test
    fun eachBackupUsesFreshSaltAndIv() {
        assertNotEquals(BackupFormat.encrypt(payload, pass, 2_000).toList(), BackupFormat.encrypt(payload, pass, 2_000).toList())
    }

    @Test
    fun shortPassphrasesAreRejected() {
        try {
            BackupFormat.encrypt(payload, "short".toCharArray(), 2_000)
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }
}

class CsvTest {
    @Test
    fun quotesWhenNeeded() {
        assertEquals("Swiggy,486.00", Csv.row(listOf("Swiggy", "486.00")))
        assertEquals("\"Food, dining\",\"He said \"\"hi\"\"\"", Csv.row(listOf("Food, dining", "He said \"hi\"")))
        assertEquals("\"line\nbreak\"", Csv.escape("line\nbreak"))
        assertEquals(",x", Csv.row(listOf(null, "x")))
    }

    @Test
    fun formulasBecomeText() {
        assertEquals("'=HYPERLINK(\"x\")".let { "\"" + it.replace("\"", "\"\"") + "\"" }, Csv.escape("=HYPERLINK(\"x\")"))
        assertEquals("'@cmd", Csv.escape("@cmd"))
        assertEquals("-486.00", Csv.escape("-486.00")) // negative numbers stay numbers
    }
}
