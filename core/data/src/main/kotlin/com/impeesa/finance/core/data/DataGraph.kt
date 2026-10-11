package com.impeesa.finance.core.data

import android.content.Context
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.data.security.DatabaseKeyManager

/**
 * Everything the app needs from the data layer. Room and SQLCipher stay private to this module.
 * The database opens lazily on first use; touch it off the main thread.
 */
class DataGraph(context: Context) {
    private val appContext = context.applicationContext

    private val database: ImpeesaDatabase by lazy {
        ImpeesaDatabase.create(appContext, DatabaseKeyManager(appContext))
    }

    val categoryRepository: CategoryRepository by lazy { CategoryRepository(database.categoryDao()) }
    val transactionRepository: TransactionRepository by lazy { TransactionRepository(database.transactionDao()) }
}
