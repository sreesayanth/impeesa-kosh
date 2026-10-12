package com.impeesa.finance.feature.settings

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.backup.BackupService
import com.impeesa.finance.backup.retentionLabel
import com.impeesa.finance.core.data.OpenedBackup
import com.impeesa.finance.core.data.capture.CaptureRepository
import com.impeesa.finance.core.model.backup.BackupException
import com.impeesa.finance.core.model.backup.BackupFormat
import com.impeesa.finance.core.model.analytics.Period
import com.impeesa.finance.core.model.counted
import com.impeesa.finance.core.ui.component.ImpeesaChip
import com.impeesa.finance.core.ui.component.ImpeesaTextField
import com.impeesa.finance.core.ui.component.PrimaryButton
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.security.SystemAuth
import com.impeesa.finance.ui.UndoEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class PassFor { BACKUP, RESTORE, WEEKLY }

/** Backup, restore, export, retention and delete-all (IMPLEMENTATION_PLAN.md §5.12, §9.7, §9.8). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DataScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val backups = container.backups
    val activity = LocalContext.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val colors = ImpeesaTheme.colors
    val today = LocalDate.now(container.clock)

    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var passFor by remember { mutableStateOf<PassFor?>(null) }
    // Held only in memory while the system file picker is open; never saved.
    var pendingPass by remember { mutableStateOf<CharArray?>(null) }
    var pendingIncludeMessages by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var opened by remember { mutableStateOf<OpenedBackup?>(null) }
    var weeklyFolder by remember { mutableStateOf(backups.weeklyFolder) }
    var lastBackup by remember { mutableStateOf(backups.lastBackupAt()) }
    var retention by remember { mutableIntStateOf(container.captureRepository.messageRetentionDays) }
    var csvPeriod by rememberSaveable { mutableIntStateOf(0) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    fun run(label: String, block: suspend () -> Unit) {
        scope.launch {
            busy = label
            error = null
            try {
                block()
            } catch (e: BackupException) {
                error = e.message
            } catch (e: Exception) {
                error = "Couldn't finish: ${e.message ?: "the file couldn't be written"}"
            } finally {
                busy = null
            }
        }
    }

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupService.MIME_BACKUP)) { uri ->
        val pass = pendingPass
        pendingPass = null
        if (uri != null && pass != null) {
            run("Encrypting backup…") {
                backups.createTo(uri, pass, pendingIncludeMessages)
                pass.fill(' ')
                lastBackup = backups.lastBackupAt()
                container.undo.post(UndoEvent("Backup saved"))
            }
        }
    }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            restoreUri = uri
            passFor = PassFor.RESTORE
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val pass = pendingPass
        pendingPass = null
        if (uri != null && pass != null) {
            run("Setting up weekly backup…") {
                backups.enableWeekly(uri, pass, pendingIncludeMessages)
                pass.fill(' ')
                weeklyFolder = backups.weeklyFolder
                lastBackup = backups.lastBackupAt()
                container.undo.post(UndoEvent("Weekly backup is on · first backup saved"))
            }
        }
    }
    val csvPeriods = listOf(
        "This month" to Period(today.withDayOfMonth(1), today),
        "Last 3 months" to Period(today.minusMonths(3).withDayOfMonth(1), today),
        "This year" to Period(today.withDayOfYear(1), today),
        "Everything" to Period(LocalDate.of(2000, 1, 1), today),
    )
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) run("Exporting…") {
            backups.exportCsv(uri, csvPeriods[csvPeriod].second)
            container.undo.post(UndoEvent("Transactions exported"))
        }
    }
    var pdfMonth by remember { mutableStateOf(YearMonth.from(today)) }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) run("Making the report…") {
            val period = Period(pdfMonth.atDay(1), minOf(pdfMonth.atEndOfMonth(), today))
            backups.exportMonthlyPdf(uri, period, backups.monthTitle(period))
            container.undo.post(UndoEvent("Report saved"))
        }
    }

    Column(
        Modifier.fillMaxSize().background(colors.ink).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(title = "Backup & data", onBack = onBack)
        busy?.let { label ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = ImpeesaTheme.type.caption, color = colors.textSecondary)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.lime, trackColor = colors.raised)
            }
        }
        error?.let { Text(it, style = ImpeesaTheme.type.body, color = colors.coral) }

        SectionLabel("Backup")
        Card {
            ActionRow("Back up now", lastBackup?.let { "Last backup ${formatTime(it, container.clock.zone)}" } ?: "Saves an encrypted file wherever you choose, e.g. Google Drive", busy == null) {
                passFor = PassFor.BACKUP
            }
            Divider()
            if (weeklyFolder == null) {
                ActionRow("Weekly backup", "Off · pick a folder once and Kosh saves a backup every week, keeping the last 4", busy == null) { passFor = PassFor.WEEKLY }
            } else {
                ActionRow("Weekly backup is on", "Into ${folderName(weeklyFolder!!)} · tap to back up there now", busy == null) {
                    run("Backing up…") {
                        backups.runWeekly(force = true)
                        lastBackup = backups.lastBackupAt()
                        container.undo.post(UndoEvent("Backup saved"))
                    }
                }
                Divider()
                ActionRow("Turn off weekly backup", "Forgets the folder and the saved passphrase. Existing files stay.", busy == null, danger = true) {
                    backups.disableWeekly()
                    weeklyFolder = null
                }
            }
            Divider()
            ActionRow("Restore from a backup", "Replaces what's on this phone with the backup's contents", busy == null) {
                pickRestore.launch(arrayOf("*/*"))
            }
        }
        Text(
            "Backups are encrypted with your passphrase (AES-256). Without it nobody can open them, not even you, so keep it safe. Saving to Google Drive is done by the Drive app; Kosh itself has no internet access.",
            style = ImpeesaTheme.type.caption,
            color = colors.textTertiary,
        )

        SectionLabel("Export")
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Transactions as CSV", style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    csvPeriods.forEachIndexed { i, (label, _) -> ImpeesaChip(label = label, selected = csvPeriod == i, onClick = { csvPeriod = i }) }
                }
                Pill("Export CSV", enabled = busy == null) { exportCsv.launch("kosh-transactions-$today.csv") }
            }
            Divider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Monthly report (PDF)", style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val thisMonth = YearMonth.from(today)
                    listOf(thisMonth, thisMonth.minusMonths(1), thisMonth.minusMonths(2)).forEach { m ->
                        ImpeesaChip(label = m.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH), selected = pdfMonth == m, onClick = { pdfMonth = m })
                    }
                }
                Pill("Save report", enabled = busy == null) { exportPdf.launch("kosh-report-$pdfMonth.pdf") }
            }
        }
        Text("Exports are not encrypted. Anyone who gets the file can read it, so share it carefully.", style = ImpeesaTheme.type.caption, color = colors.amber)

        SectionLabel("Keep original messages")
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bank SMS and notification texts are kept so you can see where a transaction came from. Amounts and merchants stay either way.", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptureRepository.RETENTION_CHOICES.forEach { days ->
                        ImpeesaChip(label = retentionLabel(days), selected = retention == days, onClick = {
                            retention = days
                            container.captureRepository.messageRetentionDays = days
                            scope.launch { withContext(Dispatchers.IO) { container.captureRepository.purgeExpired() } }
                        })
                    }
                }
            }
        }

        SectionLabel("Danger zone")
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, colors.coral.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                .clickable(role = Role.Button) { confirmDelete = true }.padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Delete all data", style = ImpeesaTheme.type.bodyStrong, color = colors.coral)
                Text("Removes every transaction, account, setting and key from this phone. Backups you saved elsewhere are not touched.", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
            }
        }
    }

    passFor?.let { purpose ->
        ModalBottomSheet(onDismissRequest = { passFor = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
            PassphraseSheet(
                purpose = purpose,
                onDone = { pass, includeMessages ->
                    passFor = null
                    when (purpose) {
                        PassFor.BACKUP -> {
                            pendingPass = pass
                            pendingIncludeMessages = includeMessages
                            createBackup.launch(backups.suggestedName())
                        }
                        PassFor.WEEKLY -> {
                            pendingPass = pass
                            pendingIncludeMessages = includeMessages
                            pickFolder.launch(null)
                        }
                        PassFor.RESTORE -> {
                            val uri = restoreUri ?: return@PassphraseSheet
                            run("Checking the backup…") { opened = backups.open(uri, pass) }
                        }
                    }
                },
            )
        }
    }

    opened?.let { backup ->
        val p = backup.preview
        AlertDialog(
            onDismissRequest = { opened = null },
            title = { Text("Replace all data?") },
            text = {
                Text(
                    "Backup from ${formatTime(p.exportedAt, container.clock.zone)}: " +
                        listOf(counted(p.transactions, "transaction"), counted(p.accounts, "account"), counted(p.budgets, "budget"), counted(p.rules, "rule")).joinToString() +
                        (if (p.includesMessages) ", with message texts." else ".") +
                        "\n\nEverything currently on this phone will be replaced. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    opened = null
                    run("Restoring…") {
                        backups.restore(backup)
                        container.undo.post(UndoEvent("Restored ${counted(p.transactions, "transaction")}"))
                    }
                }) { Text("Replace", color = colors.coral) }
            },
            dismissButton = { TextButton(onClick = { opened = null }) { Text("Cancel") } },
            containerColor = colors.card,
        )
    }

    if (confirmDelete) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete everything?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This removes all transactions, accounts, budgets, rules, settings and keys from this phone, and the app starts fresh. It can't be undone. Type DELETE to confirm.")
                    ImpeesaTextField(value = typed, onValueChange = { typed = it.take(10) }, label = "Type DELETE", placeholder = "DELETE")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = typed.trim() == "DELETE",
                    onClick = {
                        confirmDelete = false
                        val wipe = { container.ioScope.launch { container.wipeEverything() } }
                        if (SystemAuth.deviceCredentialAvailable(activity)) {
                            SystemAuth.authenticate(activity, "Delete all data", "Confirm it's you", allowDeviceCredential = true, onSuccess = { wipe() })
                        } else {
                            wipe()
                        }
                    },
                ) { Text("Delete everything", color = if (typed.trim() == "DELETE") colors.coral else colors.textTertiary) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            containerColor = colors.card,
        )
    }
}

@Composable
private fun PassphraseSheet(purpose: PassFor, onDone: (CharArray, Boolean) -> Unit) {
    val colors = ImpeesaTheme.colors
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var includeMessages by remember { mutableStateOf(false) }
    val restoring = purpose == PassFor.RESTORE
    val longEnough = pass.length >= BackupFormat.MIN_PASSPHRASE
    val ok = if (restoring) pass.isNotEmpty() else longEnough && pass == confirm
    Column(Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (restoring) "Backup passphrase" else "Choose a passphrase", style = ImpeesaTheme.type.title, color = colors.textPrimary)
        ImpeesaTextField(value = pass, onValueChange = { pass = it }, label = "Passphrase", placeholder = "At least ${BackupFormat.MIN_PASSPHRASE} characters", password = true)
        if (!restoring) {
            ImpeesaTextField(value = confirm, onValueChange = { confirm = it }, label = "Type it again", password = true)
            if (confirm.isNotEmpty() && confirm != pass) Text("Passphrases don't match", style = ImpeesaTheme.type.caption, color = colors.coral)
            ToggleRow("Include original messages", "Bank SMS texts. Larger file; off keeps backups leaner and more private.", includeMessages) { includeMessages = it }
            Text(
                if (purpose == PassFor.WEEKLY) "Kosh keeps this passphrase on the phone, encrypted, so weekly backups can run. You'll still need it to restore."
                else "There's no way to recover a forgotten passphrase.",
                style = ImpeesaTheme.type.caption,
                color = colors.textTertiary,
            )
        }
        PrimaryButton(text = if (restoring) "Open backup" else "Continue", enabled = ok, onClick = { onDone(pass.toCharArray(), includeMessages) })
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val colors = ImpeesaTheme.colors
    Column(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = ImpeesaTheme.type.bodyStrong, color = if (danger) colors.coral else colors.textPrimary)
        Text(subtitle, style = ImpeesaTheme.type.caption, color = colors.textSecondary)
    }
}

@Composable
private fun Pill(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = ImpeesaTheme.colors
    Text(
        label,
        style = ImpeesaTheme.type.label,
        color = colors.ink,
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (enabled) colors.lime else colors.line)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private fun formatTime(millis: Long, zone: java.time.ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH))

private fun folderName(uri: Uri): String =
    runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifEmpty { "your folder" } }.getOrDefault("your folder")
