package com.impeesa.finance.core.data

import android.content.Context
import com.impeesa.finance.core.data.capture.CaptureRepository
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.data.security.DatabaseKeyManager
import com.impeesa.finance.core.engine.DetectionEngine
import java.io.File
import java.time.Clock

/**
 * Everything the app needs from the data layer. Room and SQLCipher stay private to this module.
 * The database opens lazily on first use; touch it off the main thread.
 */
class DataGraph(context: Context, clock: Clock) {
    private val appContext = context.applicationContext
    private val keyManager = DatabaseKeyManager(appContext)

    private val databaseDelegate = lazy { ImpeesaDatabase.create(appContext, keyManager) }
    private val database: ImpeesaDatabase by databaseDelegate

    val categoryRepository: CategoryRepository by lazy { CategoryRepository(database, clock) }
    val accountRepository: AccountRepository by lazy {
        AccountRepository(database.accountDao(), clock, database) { captureRepository.replayLedger(it) }
    }
    val transactionRepository: TransactionRepository by lazy { TransactionRepository(database, clock) }
    val reviewRepository: ReviewRepository by lazy { ReviewRepository(database, clock) }
    val categorizationRepository: CategorizationRepository by lazy { CategorizationRepository(database, clock) }
    val analyticsRepository: AnalyticsRepository by lazy { AnalyticsRepository(database, clock) }
    val budgetRepository: BudgetRepository by lazy { BudgetRepository(database, clock) }
    val ledgerRepository: LedgerRepository by lazy { LedgerRepository(database, clock) }
    val backupRepository: BackupRepository by lazy { BackupRepository(database, clock) }
    val captureRepository: CaptureRepository by lazy {
        CaptureRepository(appContext, database, DetectionEngine::withBundledData, clock)
    }

    /** First-run data: default categories and a cash wallet. */
    suspend fun seed() {
        categoryRepository.ensureSeeded()
        accountRepository.ensureCashWallet()
    }

    /**
     * "Delete all data" (IMPLEMENTATION_PLAN.md §5.12): closes the database and removes it, its journal
     * files and its key. The process must restart afterwards; this graph is unusable.
     */
    fun wipe() {
        if (databaseDelegate.isInitialized()) database.close()
        appContext.deleteDatabase(ImpeesaDatabase.NAME)
        keyManager.destroy()
    }

    /** What could be left behind after [wipe], for tests and the delete-all check. */
    fun leftovers(): List<String> = buildList {
        val dbFile = appContext.getDatabasePath(ImpeesaDatabase.NAME)
        listOf("", "-wal", "-shm", "-journal").map { File(dbFile.path + it) }.filter { it.exists() }.forEach { add(it.name) }
        if (keyManager.keyFileExists()) add("database key")
    }
}
