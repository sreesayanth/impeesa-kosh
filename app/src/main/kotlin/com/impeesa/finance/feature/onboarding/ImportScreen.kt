package com.impeesa.finance.feature.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.capture.HistoryImportWorker
import com.impeesa.finance.capture.toProgress
import com.impeesa.finance.core.data.capture.ImportProgress
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.ui.component.PrimaryButton
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.component.SegmentedControl
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import kotlinx.coroutines.flow.map

private val ranges = listOf(3, 6, 12)

/** History import with live progress (design board "Import"). */
@Composable
fun ImportScreen(onDone: () -> Unit, onReview: () -> Unit) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val colors = ImpeesaTheme.colors
    var months by rememberSaveable { mutableIntStateOf(6) }
    var started by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(months) {
        if (started != months) {
            HistoryImportWorker.start(context, months)
            started = months
        }
    }

    val progressFlow = remember(context) {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(HistoryImportWorker.UNIQUE_NAME)
            .map { infos ->
            val info = infos.firstOrNull()
            when {
                info == null -> ImportProgress()
                info.state == WorkInfo.State.SUCCEEDED -> info.outputData.toProgress(done = true)
                info.state.isFinished -> ImportProgress(done = true)
                else -> info.progress.toProgress(done = false)
            }
        }
    }
    val progress by progressFlow.collectAsStateWithLifecycle(ImportProgress())

    fun finish(next: () -> Unit) {
        container.prefs.onboardingDone = true
        next()
    }

    OnboardingScaffold(step = 3) {
        Text(
            if (progress.done) "You're all set" else "Reading your messages",
            style = ImpeesaTheme.type.display,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            if (progress.done) "Your last $months months are in. Everything stayed on this phone."
            else "Looking for bank and card messages. This happens only on your phone.",
            style = ImpeesaTheme.type.body,
            color = colors.textSecondary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Look back")
            SegmentedControl(
                options = listOf("3 months", "6 months", "1 year"),
                selectedIndex = ranges.indexOf(months),
                onSelect = { months = ranges[it] },
            )
        }
        ProgressCard(progress)
        Text(
            "OTPs, offers and personal chats are skipped and never saved.",
            style = ImpeesaTheme.type.caption,
            color = colors.textTertiary,
        )
        PrimaryButton(
            text = if (progress.done) "See my dashboard" else "Keep going in the background",
            onClick = { finish(onDone) },
        )
        if (progress.done && progress.review > 0) {
            Text(
                "Review ${progress.review} we weren't sure about",
                style = ImpeesaTheme.type.bodyStrong,
                color = colors.lime,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { finish(onReview) }
                    .padding(14.dp),
            )
        }
    }
}

@Composable
private fun ProgressCard(progress: ImportProgress) {
    val colors = ImpeesaTheme.colors
    val fraction = when {
        progress.done -> 1f
        progress.total == 0 -> 0f
        else -> progress.read.toFloat() / progress.total
    }
    val animated by animateFloatAsState(fraction, label = "import")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(colors.card).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(Modifier.size(104.dp).semantics { contentDescription = "Import ${(animated * 100).toInt()} percent done" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(104.dp)) {
                val stroke = 10.dp.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(stroke / 2, stroke / 2)
                drawArc(colors.raised, -90f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                drawArc(colors.lime, -90f, 360f * animated, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(if (progress.done) "✓" else "${(animated * 100).toInt()}%", style = ImpeesaTheme.type.amountLarge, color = colors.textPrimary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(MoneyFormat.group(progress.read.toLong()), "messages read")
            Stat(MoneyFormat.group((progress.logged + progress.review).toLong()), "transactions", highlight = true)
            Stat(progress.accounts.toString(), "accounts")
            Stat(MoneyFormat.group(progress.skipped.toLong()), "skipped")
        }
    }
}

@Composable
private fun Stat(value: String, label: String, highlight: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(value, style = ImpeesaTheme.type.title, color = if (highlight) ImpeesaTheme.colors.lime else ImpeesaTheme.colors.textPrimary)
        Text(label, style = ImpeesaTheme.type.caption, color = ImpeesaTheme.colors.textSecondary)
    }
}
