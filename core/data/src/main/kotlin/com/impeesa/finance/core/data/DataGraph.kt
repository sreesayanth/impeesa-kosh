package com.impeesa.finance.core.data

import android.content.Context
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.data.security.DatabaseKeyManager
import java.time.Clock

/**
 * Everything the app needs from the data layer. Room and SQLCipher stay private to this module.
 * The database opens lazily on first use; touch it off the main thread.
 */
class DataGraph(context: Context, clock: Clock) {
    private val appContext = context.applicationContext

    private val database: ImpeesaDatabase by lazy {
        ImpeesaDatabase.create(appContext, DatabaseKeyManager(appContext))
    }

    val categoryRepository: CategoryRepository by lazy { CategoryRepository(database.categoryDao()) }
    val accountRepository: AccountRepository by lazy { AccountRepository(database.accountDao(), clock) }
    val transactionRepository: TransactionRepository by lazy { TransactionRepository(database, clock) }

    /** First-run data: default categories and a cash wallet. */
    suspend fun seed() {
        categoryRepository.ensureSeeded()
        accountRepository.ensureCashWallet()
    }
}
