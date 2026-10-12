package com.impeesa.finance.core.data

import android.database.Cursor
import androidx.room.withTransaction
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.model.backup.BackupException
import com.impeesa.finance.core.model.backup.BackupFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Clock
import java.util.Base64

/** What a backup holds, shown before restoring. */
data class BackupPreview(
    val exportedAt: Long,
    val schemaVersion: Int,
    val transactions: Int,
    val accounts: Int,
    val budgets: Int,
    val rules: Int,
    val includesMessages: Boolean,
)

/** A decrypted backup ready to restore, plus the app settings it carried. */
class OpenedBackup internal constructor(internal val root: JsonObject, val preview: BackupPreview, val settings: Map<String, String>)

/**
 * Encrypted backup and restore of everything in the database (IMPLEMENTATION_PLAN.md §9.8).
 * Every table is dumped generically, so new columns and tables are included without code changes.
 * Restore replaces the current data; backups from older schema versions restore into the columns they have.
 */
class BackupRepository internal constructor(
    private val db: ImpeesaDatabase,
    private val clock: Clock,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Builds the encrypted file. Original message texts are left out unless [includeMessages]. */
    suspend fun create(passphrase: CharArray, includeMessages: Boolean, settings: Map<String, String>, iterations: Int = BackupFormat.DEFAULT_ITERATIONS): ByteArray {
        val payload = db.withTransaction { dump(includeMessages, settings) }
        return BackupFormat.encrypt(json.encodeToString(JsonObject.serializer(), payload).toByteArray(Charsets.UTF_8), passphrase, iterations)
    }

    /** Decrypts and checks a file; throws [BackupException] for wrong passphrases, other files or newer versions. */
    fun open(file: ByteArray, passphrase: CharArray): OpenedBackup {
        val root = try {
            json.parseToJsonElement(BackupFormat.decrypt(file, passphrase).toString(Charsets.UTF_8)).jsonObject
        } catch (e: BackupException) {
            throw e
        } catch (_: Exception) {
            throw BackupException.NotABackup()
        }
        val schema = root["schemaVersion"]?.jsonPrimitive?.int ?: throw BackupException.NotABackup()
        if (schema > currentSchema()) throw BackupException.UnsupportedVersion(schema)
        val tables = root["tables"]?.jsonObject ?: throw BackupException.NotABackup()
        fun count(name: String) = tables[name]?.jsonObject?.get("rows")?.jsonArray?.size ?: 0
        val preview = BackupPreview(
            exportedAt = root["exportedAt"]?.jsonPrimitive?.long ?: 0L,
            schemaVersion = schema,
            transactions = count("transactions"),
            accounts = count("accounts"),
            budgets = count("budgets"),
            rules = count("rules"),
            includesMessages = root["includesMessages"]?.jsonPrimitive?.content == "true",
        )
        val settings = root["settings"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        return OpenedBackup(root, preview, settings)
    }

    /** Replaces everything with the backup's contents, in one transaction. */
    suspend fun restore(backup: OpenedBackup) = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        val tables = backup.root["tables"]!!.jsonObject
        val existing = userTables()
        existing.forEach { sql.execSQL("DELETE FROM `$it`") }
        val hasSequence = sql.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence'").use { it.moveToFirst() }
        if (hasSequence) sql.execSQL("DELETE FROM sqlite_sequence")
        for (name in existing) {
            val table = tables[name]?.jsonObject ?: continue
            val backupColumns = table["columns"]!!.jsonArray.map { it.jsonPrimitive.content }
            val current = columnsOf(name).toSet()
            // Columns this version no longer has are skipped; new columns take their defaults.
            val keep = backupColumns.withIndex().filter { it.value in current }
            if (keep.isEmpty()) continue
            val statement = sql.compileStatement(
                "INSERT INTO `$name` (" + keep.joinToString(",") { "`${it.value}`" } + ") VALUES (" + keep.joinToString(",") { "?" } + ")",
            )
            table["rows"]!!.jsonArray.forEach { row ->
                val values = row.jsonArray
                statement.clearBindings()
                keep.forEachIndexed { position, column -> bind(statement, position + 1, values[column.index]) }
                statement.executeInsert()
            }
        }
        // Rebuild the search index from the restored rows.
        sql.execSQL("INSERT INTO transactions_fts(transactions_fts) VALUES('rebuild')")
    }

    // ------------------------------------------------------------ dump

    private fun dump(includeMessages: Boolean, settings: Map<String, String>): JsonObject = buildJsonObject {
        put("app", "Kosh")
        put("schemaVersion", currentSchema())
        put("exportedAt", clock.millis())
        put("includesMessages", includeMessages)
        put(
            "settings",
            buildJsonObject { settings.forEach { (k, v) -> put(k, v) } },
        )
        put(
            "tables",
            buildJsonObject {
                userTables().forEach { name -> put(name, dumpTable(name, blankColumn = if (name == "raw_messages" && !includeMessages) "body" else null)) }
            },
        )
    }

    private fun dumpTable(name: String, blankColumn: String?): JsonObject {
        val sql = db.openHelper.readableDatabase
        sql.query("SELECT * FROM `$name`").use { cursor ->
            val columns = cursor.columnNames.toList()
            val blank = blankColumn?.let(columns::indexOf) ?: -1
            val rows = buildJsonArray {
                while (cursor.moveToNext()) {
                    add(buildJsonArray { columns.indices.forEach { i -> add(if (i == blank) JsonNull else valueAt(cursor, i)) } })
                }
            }
            return buildJsonObject {
                put("columns", JsonArray(columns.map(::JsonPrimitive)))
                put("rows", rows)
            }
        }
    }

    private fun valueAt(cursor: Cursor, i: Int): JsonElement = when (cursor.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(i))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(i))
        Cursor.FIELD_TYPE_BLOB -> buildJsonObject { put("b64", Base64.getEncoder().encodeToString(cursor.getBlob(i))) }
        else -> JsonPrimitive(cursor.getString(i))
    }

    private fun bind(statement: androidx.sqlite.db.SupportSQLiteStatement, index: Int, value: JsonElement) {
        when {
            value is JsonNull -> statement.bindNull(index)
            value is JsonObject -> statement.bindBlob(index, Base64.getDecoder().decode(value["b64"]!!.jsonPrimitive.content))
            value is JsonPrimitive && value.isString -> statement.bindString(index, value.content)
            value is JsonPrimitive && value.longOrNull != null -> statement.bindLong(index, value.long)
            value is JsonPrimitive && value.content == "true" -> statement.bindLong(index, 1)
            value is JsonPrimitive && value.content == "false" -> statement.bindLong(index, 0)
            value is JsonPrimitive -> statement.bindDouble(index, value.content.toDouble())
            else -> statement.bindNull(index)
        }
    }

    // ------------------------------------------------------------ schema

    private fun currentSchema(): Int = db.openHelper.readableDatabase.version

    /** App tables: not SQLite's or Room's own, and not the search index (rebuilt on restore). */
    internal fun userTables(): List<String> {
        val names = mutableListOf<String>()
        db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { c ->
            while (c.moveToNext()) names += c.getString(0)
        }
        return names.filterNot { it.startsWith("sqlite_") || it.startsWith("android_") || it == "room_master_table" || it.startsWith("transactions_fts") }
    }

    private fun columnsOf(table: String): List<String> {
        val columns = mutableListOf<String>()
        db.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { c ->
            val nameIndex = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) columns += c.getString(nameIndex)
        }
        return columns
    }

    /** Row counts and a content checksum per table, to prove a restore reproduced the data. */
    suspend fun fingerprint(): Map<String, Pair<Int, Int>> = db.withTransaction {
        userTables().associateWith { name ->
            val table = dumpTable(name, blankColumn = null)
            val rows = table["rows"]!!.jsonArray
            rows.size to rows.toString().hashCode()
        }
    }
}
