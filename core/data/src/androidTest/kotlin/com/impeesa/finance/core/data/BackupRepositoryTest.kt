package com.impeesa.finance.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.impeesa.finance.core.data.capture.CaptureRepository
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.engine.DetectionEngine
import com.impeesa.finance.core.engine.MessageEnvelope
import com.impeesa.finance.core.engine.MessageSource
import com.impeesa.finance.core.engine.category.RuleAction
import com.impeesa.finance.core.engine.category.RuleMatch
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.backup.BackupException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Phase 8 acceptance: backup → wipe → restore reproduces identical data; delete-all leaves nothing. */
@RunWith(AndroidJUnit4::class)
class BackupRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clock = Clock.fixed(Instant.parse("2026-10-12T04:00:00Z"), ZoneId.of("Asia/Kolkata"))
    private val pass = "correct horse battery".toCharArray()
    private lateinit var source: ImpeesaDatabase
    private lateinit var target: ImpeesaDatabase

    private fun memoryDb() = Room.inMemoryDatabaseBuilder(context, ImpeesaDatabase::class.java).allowMainThreadQueries().build()

    @Before
    fun setUp() = runTest {
        source = memoryDb()
        target = memoryDb()
        CategoryRepository(source, clock).ensureSeeded()
        val accounts = AccountRepository(source.accountDao(), clock, source)
        accounts.ensureCashWallet()
        accounts.save(AccountDraft(type = AccountType.BANK, name = "HDFC", mask = "4821"))
        val capture = CaptureRepository(context, source, DetectionEngine::withBundledData, clock)
        listOf(
            "Rs.486.00 debited from A/c XX4821 to VPA swiggy@icici on 11-10-26. UPI Ref 628491037215. Avl Bal Rs.1,84,350.00" to "2026-10-11T07:54:00Z",
            "Rs.120.00 debited from A/c XX4821 to VPA rapido@ybl on 11-10-26. UPI Ref 628455667788." to "2026-10-11T09:00:00Z",
            "Rs.5000 withdrawn from A/c XX4821 at ATM S1AW1234 on 11-10-26. Avl Bal Rs.1,79,230.00" to "2026-10-11T10:00:00Z",
        ).forEach { (body, at) -> capture.ingest(MessageEnvelope(MessageSource.SMS, "VM-HDFCBK", body, Instant.parse(at))) }
        CategorizationRepository(source, clock).saveRule(RuleDraft(match = RuleMatch.MERCHANT, pattern = "Rapido", action = RuleAction.SET_CATEGORY, categoryId = 3))
        BudgetRepository(source, clock).save(Budget(categoryId = null, amount = Money(60_000_00)))
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    @Test
    fun restoreIntoAnEmptyDatabaseReproducesEveryTable() = runTest {
        val original = BackupRepository(source, clock)
        val file = original.create(pass, includeMessages = true, settings = mapOf("month_start_day" to "25"), iterations = 2_000)

        val restored = BackupRepository(target, clock)
        val opened = restored.open(file, pass)
        // Swiggy, Rapido, and the ATM withdrawal's two legs (bank out, cash in).
        assertEquals(4, opened.preview.transactions)
        assertEquals("25", opened.settings["month_start_day"])
        restored.restore(opened)

        assertEquals(original.fingerprint(), restored.fingerprint())
        assertTrue(TransactionRepository(target, clock).observeRecent(10).first().any { it.counterparty == "Swiggy" })
        // The search index is rebuilt from the restored rows.
        target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM transactions_fts WHERE transactions_fts MATCH 'Swiggy*'").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
    }

    @Test
    fun messagesAreLeftOutUnlessAsked() = runTest {
        val file = BackupRepository(source, clock).create(pass, includeMessages = false, settings = emptyMap(), iterations = 2_000)
        val restored = BackupRepository(target, clock)
        restored.restore(restored.open(file, pass))
        target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM raw_messages WHERE body IS NOT NULL").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM raw_messages").use {
            it.moveToFirst()
            assertEquals(3, it.getInt(0)) // hashes kept, so the same SMS is never captured twice
        }
    }

    @Test
    fun restoreReplacesWhatWasThere() = runTest {
        CategoryRepository(target, clock).ensureSeeded()
        AccountRepository(target.accountDao(), clock, target).save(AccountDraft(type = AccountType.WALLET, name = "Old wallet"))
        val file = BackupRepository(source, clock).create(pass, includeMessages = false, settings = emptyMap(), iterations = 2_000)
        val restored = BackupRepository(target, clock)
        restored.restore(restored.open(file, pass))
        val names = AccountRepository(target.accountDao(), clock, target).observeAccounts().first().map { it.name }
        assertTrue("Old wallet" !in names)
        assertTrue("HDFC" in names)
    }

    @Test
    fun wrongPassphraseIsRefused() = runTest {
        val file = BackupRepository(source, clock).create(pass, includeMessages = false, settings = emptyMap(), iterations = 2_000)
        try {
            BackupRepository(target, clock).open(file, "not the passphrase".toCharArray())
            fail()
        } catch (_: BackupException.WrongPassphrase) {
        }
    }

    @Test
    fun retentionClearsOldMessageTexts() = runTest {
        val capture = CaptureRepository(context, source, DetectionEngine::withBundledData, Clock.fixed(Instant.parse("2026-12-30T04:00:00Z"), clock.zone))
        capture.messageRetentionDays = 30
        assertEquals(3, capture.purgeExpired())
        capture.messageRetentionDays = CaptureRepository.DEFAULT_RETENTION_DAYS
    }

    @Test
    fun deleteAllLeavesNoDatabaseOrKey() = runTest {
        val graph = DataGraph(context, clock)
        graph.seed() // opens the real encrypted database and creates its key
        assertTrue(graph.leftovers().isNotEmpty())
        graph.wipe()
        assertEquals(emptyList<String>(), graph.leftovers())
    }
}
