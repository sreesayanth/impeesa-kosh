package com.impeesa.finance.backup

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.impeesa.finance.AppContainer
import com.impeesa.finance.ImpeesaApp
import com.impeesa.finance.core.data.OpenedBackup
import com.impeesa.finance.core.data.capture.CaptureRepository
import com.impeesa.finance.core.model.Currencies
import com.impeesa.finance.core.model.DigitGrouping
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.model.MoneySettings
import com.impeesa.finance.core.model.alerts.AlertKind
import com.impeesa.finance.core.model.analytics.Flows
import com.impeesa.finance.core.model.analytics.Period
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Backups, restore and exports (IMPLEMENTATION_PLAN.md §9.8). Files go through the Storage Access Framework,
 * so the user can pick Google Drive, a USB stick or the phone; any upload is done by that app, and Impeesa
 * still needs no internet permission.
 */
class BackupService(private val context: Context, private val container: AppContainer) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    // ------------------------------------------------------------ settings carried in backups

    private fun settingsSnapshot(): Map<String, String> {
        val p = container.prefs
        val profile = p.profile.value
        return mapOf(
            "profile_name" to profile.name,
            "profile_color" to profile.colorIndex.toString(),
            "month_start_day" to p.monthStartDay.value.toString(),
            "alert_kinds" to p.enabledAlerts.value.joinToString(",") { it.name },
            "message_retention_days" to container.captureRepository.messageRetentionDays.toString(),
            "currency" to p.money.value.currency.code,
            "grouping" to p.money.value.grouping.name,
        )
    }

    private fun applySettings(settings: Map<String, String>) {
        val p = container.prefs
        p.saveProfile(settings["profile_name"].orEmpty(), settings["profile_color"]?.toIntOrNull() ?: 0)
        settings["month_start_day"]?.toIntOrNull()?.let(p::setMonthStartDay)
        settings["alert_kinds"]?.let { csv ->
            val on = csv.split(',').filter { it.isNotBlank() }.toSet()
            AlertKind.entries.forEach { p.setAlertEnabled(it, it.name in on) }
        }
        settings["message_retention_days"]?.toIntOrNull()?.let { container.captureRepository.messageRetentionDays = it }
        settings["currency"]?.let { code ->
            val currency = Currencies.byCode(code)
            val grouping = settings["grouping"]?.let { g -> DigitGrouping.entries.firstOrNull { it.name == g } } ?: currency.defaultGrouping
            p.setMoney(MoneySettings(currency, grouping))
            container.captureRepository.homeCurrency = currency.code
        }
    }

    // ------------------------------------------------------------ manual backup and restore

    fun suggestedName(): String = "impeesa-backup-${LocalDate.now(container.clock)}.impk"

    suspend fun createTo(uri: Uri, passphrase: CharArray, includeMessages: Boolean) = withContext(Dispatchers.IO) {
        val bytes = container.backupRepository.create(passphrase, includeMessages, settingsSnapshot())
        resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        prefs.edit().putLong(KEY_LAST, container.clock.millis()).apply()
    }

    suspend fun open(uri: Uri, passphrase: CharArray): OpenedBackup = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
        container.backupRepository.open(bytes, passphrase)
    }

    /** Replaces all data with the backup, then its settings. */
    suspend fun restore(backup: OpenedBackup) = withContext(Dispatchers.IO) {
        container.backupRepository.restore(backup)
        applySettings(backup.settings)
    }

    fun lastBackupAt(): Long? = prefs.getLong(KEY_LAST, 0L).takeIf { it > 0 }

    // ------------------------------------------------------------ weekly backup

    val weeklyFolder: Uri? get() = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)

    /** Turns on weekly backups into [folder]; the passphrase is kept encrypted by the phone's Keystore. */
    suspend fun enableWeekly(folder: Uri, passphrase: CharArray, includeMessages: Boolean) {
        resolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        container.passphraseVault.store(passphrase)
        prefs.edit().putString(KEY_FOLDER, folder.toString()).putBoolean(KEY_INCLUDE_MESSAGES, includeMessages).apply()
        runWeekly(force = true)
    }

    fun disableWeekly() {
        weeklyFolder?.let { uri -> runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        container.passphraseVault.clear()
        prefs.edit().remove(KEY_FOLDER).remove(KEY_INCLUDE_MESSAGES).apply()
    }

    /** Writes a backup into the weekly folder if a week has passed, keeping the newest [KEEP] files. */
    suspend fun runWeekly(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val folder = weeklyFolder ?: return@withContext false
        val last = lastBackupAt() ?: 0L
        if (!force && container.clock.millis() - last < Duration.ofDays(7).toMillis()) return@withContext false
        val passphrase = container.passphraseVault.read() ?: return@withContext false
        val tree = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val name = suggestedName()
        val target = DocumentsContract.createDocument(resolver, tree, MIME_BACKUP, name) ?: return@withContext false
        createTo(target, passphrase, prefs.getBoolean(KEY_INCLUDE_MESSAGES, false))
        passphrase.fill(' ')
        pruneOld(folder)
        true
    }

    private fun pruneOld(folder: Uri) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val backups = mutableListOf<Pair<String, String>>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                if (name.startsWith("impeesa-backup-") && name.endsWith(".impk")) backups += c.getString(0) to name
            }
        }
        backups.sortedByDescending { it.second }.drop(KEEP).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(folder, id)) }
        }
    }

    // ------------------------------------------------------------ exports (unencrypted; the UI warns)

    suspend fun exportCsv(uri: Uri, period: Period) = withContext(Dispatchers.IO) {
        val csv = container.analyticsRepository.exportCsv(period)
        resolver.openOutputStream(uri, "wt")!!.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
    }

    /** A one-page monthly report: summary, categories and top merchants, drawn with the platform PDF API. */
    suspend fun exportMonthlyPdf(uri: Uri, period: Period, title: String) = withContext(Dispatchers.IO) {
        val analytics = container.analyticsRepository
        val days = analytics.observeDaily(period.start, period.end).first()
        val flow = Flows.flow(days, period)
        val categories = analytics.categoryTotals(period)
        val merchants = analytics.observeTopMerchants(period, 8).first()

        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        val heading = Paint().apply { textSize = 22f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
        val sub = Paint().apply { textSize = 11f; color = 0xFF666B73.toInt(); isAntiAlias = true }
        val body = Paint().apply { textSize = 12f; isAntiAlias = true }
        val bold = Paint(body).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val right = Paint(body).apply { textAlign = Paint.Align.RIGHT }
        val line = Paint().apply { color = 0xFFDDDDDD.toInt(); strokeWidth = 1f }

        var y = 64f
        canvas.drawText("Impeesa Kosh · $title", 48f, y, heading)
        y += 20f
        canvas.drawText("${period.start.format(DAY)} – ${period.end.format(DAY)} · made on your phone", 48f, y, sub)
        y += 36f
        listOf("Spent" to MoneyFormat.format(flow.spent), "Income" to MoneyFormat.format(flow.income), "Saved" to MoneyFormat.format(flow.saved))
            .forEachIndexed { i, (label, value) ->
                val x = 48f + i * 170f
                canvas.drawText(label, x, y, sub)
                canvas.drawText(value, x, y + 20f, Paint(heading).apply { textSize = 18f })
            }
        y += 44f
        flow.savingsRate?.let { canvas.drawText("Savings rate ${(it * 100).toInt()}%", 48f, y, sub); y += 10f }

        fun section(name: String) {
            y += 30f
            canvas.drawText(name, 48f, y, bold)
            y += 8f
            canvas.drawLine(48f, y, 547f, y, line)
            y += 18f
        }

        section("Spending by category")
        val total = categories.sumOf { it.total.paise }.coerceAtLeast(1)
        categories.take(14).forEach { c ->
            canvas.drawText(c.name, 48f, y, body)
            canvas.drawText("${c.total.paise * 100 / total}%", 440f, y, right)
            canvas.drawText(MoneyFormat.format(c.total), 547f, y, right)
            y += 18f
        }
        if (categories.isEmpty()) { canvas.drawText("No spending this month.", 48f, y, sub); y += 18f }

        section("Top merchants")
        merchants.forEach { m ->
            canvas.drawText("${m.label} (${m.count})", 48f, y, body)
            canvas.drawText(MoneyFormat.format(m.total), 547f, y, right)
            y += 18f
        }

        canvas.drawText("Generated on this phone by Impeesa Kosh. Impeesa has no internet access; this file was not uploaded.", 48f, 810f, sub)
        doc.finishPage(page)
        resolver.openOutputStream(uri, "wt")!!.use { doc.writeTo(it) }
        doc.close()
    }

    fun monthTitle(period: Period): String =
        period.start.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + period.start.year

    companion object {
        const val MIME_BACKUP = "application/octet-stream"
        private const val KEEP = 4
        private const val KEY_LAST = "last_backup_at"
        private const val KEY_FOLDER = "weekly_folder"
        private const val KEY_INCLUDE_MESSAGES = "weekly_include_messages"
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    }
}

/** Daily housekeeping: retention purge (§9.7) and the weekly backup when it's due. */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ImpeesaApp).container
        runCatching { container.captureRepository.purgeExpired() }
        runCatching { container.backups.runWeekly() }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "maintenance-daily",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MaintenanceWorker>(24, TimeUnit.HOURS).build(),
            )
        }
    }
}

/** Retention choices as the settings screen shows them. */
fun retentionLabel(days: Int): String = when (days) {
    CaptureRepository.KEEP_FOREVER -> "Forever"
    CaptureRepository.KEEP_NEVER -> "Don't keep"
    else -> "$days days"
}
