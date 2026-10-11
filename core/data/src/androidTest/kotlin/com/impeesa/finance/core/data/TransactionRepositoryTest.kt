package com.impeesa.finance.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.model.DateKey
import com.impeesa.finance.core.model.EntryKind
import com.impeesa.finance.core.model.Money
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class TransactionRepositoryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val clock = Clock.fixed(Instant.parse("2026-10-11T08:00:00Z"), zone)
    private lateinit var db: ImpeesaDatabase
    private lateinit var accounts: AccountRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var categories: CategoryRepository

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ImpeesaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        categories = CategoryRepository(db.categoryDao())
        accounts = AccountRepository(db.accountDao(), clock)
        transactions = TransactionRepository(db, clock)
        categories.ensureSeeded()
        accounts.ensureCashWallet()
    }

    @After
    fun tearDown() = db.close()

    private val now = LocalDateTime.of(2026, 10, 11, 13, 24)

    private suspend fun bank(opening: Long) =
        accounts.save(AccountDraft(type = AccountType.BANK, name = "HDFC", mask = "XX4821", openingBalance = Money.ofRupees(opening)))

    private suspend fun cashId() = accounts.observeAccounts().first().single { it.type == AccountType.CASH }.id

    private suspend fun foodId() = categories.observe(com.impeesa.finance.core.model.CategoryKind.EXPENSE).first().first().id

    @Test
    fun expenseReducesBalanceAndCountsAsSpending() = runTest {
        val cash = cashId()
        transactions.save(TransactionDraft(kind = EntryKind.EXPENSE, amount = Money.ofRupees(120), accountId = cash, categoryId = foodId(), occurredAt = now))

        assertEquals(Money.ofRupees(-120), accounts.observeAccounts().first().single { it.id == cash }.balance)
        val summary = transactions.observeSummary(DateKey.monthRange(YearMonth.of(2026, 10))).first()
        assertEquals(Money.ofRupees(120), summary.spent)
    }

    @Test
    fun atmWithdrawalMovesMoneyWithoutSpending() = runTest {
        val bank = bank(10_000)
        val cash = cashId()
        transactions.save(TransactionDraft(kind = EntryKind.TRANSFER, amount = Money.ofRupees(5_000), accountId = bank, toAccountId = cash, occurredAt = now))

        val list = accounts.observeAccounts().first()
        assertEquals(Money.ofRupees(5_000), list.single { it.id == bank }.balance)
        assertEquals(Money.ofRupees(5_000), list.single { it.id == cash }.balance)
        val summary = transactions.observeSummary(DateKey.monthRange(YearMonth.of(2026, 10))).first()
        assertEquals(Money.ZERO, summary.spent)
        assertEquals(1, transactions.observeRecent(10).first().size)
    }

    @Test
    fun cardOpeningBalanceIsOwed() = runTest {
        val card = accounts.save(AccountDraft(type = AccountType.CARD, name = "ICICI", openingBalance = Money.ofRupees(18_680), creditLimit = Money.ofRupees(1_67_000)))
        val model = accounts.observeAccounts().first().single { it.id == card }
        assertEquals(Money.ofRupees(18_680), model.owed)
        assertEquals(Money.ofRupees(18_680), accounts.draftFor(card)!!.openingBalance)
    }

    @Test
    fun deleteAndRestoreTransferBothLegs() = runTest {
        val bank = bank(1_000)
        val id = transactions.save(TransactionDraft(kind = EntryKind.TRANSFER, amount = Money.ofRupees(400), accountId = bank, toAccountId = cashId(), occurredAt = now))

        val removed = transactions.delete(id)
        assertEquals(2, removed.size)
        assertNull(transactions.observeDetail(id).first())
        assertEquals(Money.ofRupees(1_000), accounts.observeAccounts().first().single { it.id == bank }.balance)

        transactions.restore(removed)
        assertEquals(Money.ofRupees(600), accounts.observeAccounts().first().single { it.id == bank }.balance)
    }

    @Test
    fun editingExpenseIntoTransferReplacesRows() = runTest {
        val bank = bank(1_000)
        val cash = cashId()
        val id = transactions.save(TransactionDraft(kind = EntryKind.EXPENSE, amount = Money.ofRupees(100), accountId = bank, occurredAt = now))
        val draft = transactions.draftFor(id)!!
        val newId = transactions.save(draft.copy(kind = EntryKind.TRANSFER, toAccountId = cash))

        val detail = transactions.observeDetail(newId).first()!!
        assertEquals(EntryKind.TRANSFER, detail.kind)
        assertEquals(cash, detail.toAccountId)
        assertEquals(Money.ofRupees(900), accounts.observeAccounts().first().single { it.id == bank }.balance)
    }

    @Test
    fun searchFindsNoteByPrefix() = runTest {
        val cash = cashId()
        transactions.save(TransactionDraft(kind = EntryKind.EXPENSE, amount = Money.ofRupees(20), accountId = cash, occurredAt = now, note = "Chai at Raju stall"))
        transactions.save(TransactionDraft(kind = EntryKind.EXPENSE, amount = Money.ofRupees(120), accountId = cash, occurredAt = now, note = "Auto"))

        val query = SearchQuery.from("raj")!!
        val source = db.transactionDao().pagingSource(null, null, false, false, query.fts, query.like, query.amountPaise)
        val page = source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf("Chai at Raju stall"), page.data.map { it.note })
    }
}
