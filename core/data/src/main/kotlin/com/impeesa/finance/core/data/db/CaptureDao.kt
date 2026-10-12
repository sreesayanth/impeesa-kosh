package com.impeesa.finance.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureDao {
    @Insert
    suspend fun insertRaw(raw: RawMessageEntity): Long

    @Query("SELECT id FROM raw_messages WHERE source_hash = :hash LIMIT 1")
    suspend fun rawIdByHash(hash: String): Long?

    @Query("UPDATE raw_messages SET txn_id = :txnId WHERE id = :rawId")
    suspend fun linkRaw(rawId: Long, txnId: Long)

    @Query("SELECT * FROM raw_messages WHERE id = :id")
    fun observeRaw(id: Long): Flow<RawMessageEntity?>

    @Query("SELECT * FROM raw_messages WHERE id = :id")
    suspend fun raw(id: Long): RawMessageEntity?

    @Query("UPDATE raw_messages SET txn_id = :txnId, route = :route WHERE id = :rawId")
    suspend fun relinkRaw(rawId: Long, txnId: Long, route: String)

    @Query("UPDATE raw_messages SET txn_id = :toTxn WHERE txn_id = :fromTxn")
    suspend fun moveRaw(fromTxn: Long, toTxn: Long)

    /** Plain UPDATE-then-INSERT so it works on every SQLite version, not only those with UPSERT. */
    @Query("UPDATE capture_stats SET count = count + 1 WHERE month = :month AND source = :source AND outcome = :outcome")
    suspend fun bumpStat(month: Int, source: String, outcome: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStat(stat: CaptureStatEntity)

    @Query("SELECT * FROM capture_stats WHERE month = :month")
    fun observeStats(month: Int): Flow<List<CaptureStatEntity>>

    @Query("SELECT * FROM account_suggestions WHERE sender_code = :sender AND mask = :mask LIMIT 1")
    suspend fun suggestion(sender: String, mask: String): AccountSuggestionEntity?

    @Query("SELECT * FROM account_suggestions WHERE id = :id")
    suspend fun suggestionById(id: Long): AccountSuggestionEntity?

    @Insert
    suspend fun insertSuggestion(suggestion: AccountSuggestionEntity): Long

    @Update
    suspend fun updateSuggestion(suggestion: AccountSuggestionEntity)

    @Query("SELECT * FROM account_suggestions WHERE dismissed = 0 ORDER BY sightings DESC, last_seen DESC")
    fun observeSuggestions(): Flow<List<AccountSuggestionEntity>>

    @Query("DELETE FROM account_suggestions WHERE id = :id")
    suspend fun deleteSuggestion(id: Long)

    @Query("SELECT * FROM account_identifiers")
    suspend fun identifiers(): List<AccountIdentifierEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIdentifier(identifier: AccountIdentifierEntity)

    /** Retention (§9.7): drops message texts older than [before]; the hash stays so they're never re-captured. */
    @Query("UPDATE raw_messages SET body = NULL WHERE body IS NOT NULL AND received_at < :before")
    suspend fun clearBodiesBefore(before: Long): Int

    /** Soft-deleted transactions are kept 30 days for undo, then removed. */
    @Query("DELETE FROM transactions WHERE deleted_at IS NOT NULL AND deleted_at < :before")
    suspend fun purgeDeletedTransactions(before: Long): Int
}
