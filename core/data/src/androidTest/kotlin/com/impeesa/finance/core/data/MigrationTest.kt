package com.impeesa.finance.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every released schema must migrate without losing data (IMPLEMENTATION_PLAN.md §9.10). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ImpeesaDatabase::class.java)

    @Test
    fun version1To2KeepsTransactions() {
        helper.createDatabase(DB, 1).apply {
            execSQL(
                "INSERT INTO accounts (id, type, institution, name, mask, color, opening_balance_paise, include_in_net, archived, created_at, updated_at) " +
                    "VALUES (1, 'CASH', NULL, 'Cash', NULL, 0, 0, 1, 0, 0, 0)",
            )
            execSQL(
                "INSERT INTO transactions (id, account_id, amount_paise, currency, direction, type, category_id, counterparty, occurred_at, local_date, channel, status, source, confidence, excluded_from_spending, user_edited_fields, created_at, updated_at) " +
                    "VALUES (1, 1, 12000, 'INR', 'DEBIT', 'EXPENSE', NULL, NULL, 0, 20261011, 'OTHER', 'CONFIRMED', 'MANUAL', 1.0, 0, 0, 0, 0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 2, true)
        db.query("SELECT amount_paise, raw_message_id FROM transactions WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            assertEquals(12000L, cursor.getLong(0))
            assertEquals(true, cursor.isNull(1))
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
