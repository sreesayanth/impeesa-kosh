package com.impeesa.finance.core.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.impeesa.finance.core.data.security.DatabaseKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        TransactionFts::class,
        RawMessageEntity::class,
        CaptureStatEntity::class,
        AccountSuggestionEntity::class,
        AccountIdentifierEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
internal abstract class ImpeesaDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun captureDao(): CaptureDao

    companion object {
        private const val NAME = "impeesa.db"

        /** Opens the encrypted database. Call off the main thread: it touches the Keystore. */
        fun create(context: Context, keyManager: DatabaseKeyManager): ImpeesaDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(keyManager.passphrase())
            return Room.databaseBuilder(context.applicationContext, ImpeesaDatabase::class.java, NAME)
                .openHelperFactory(factory)
                .build()
        }
    }
}
