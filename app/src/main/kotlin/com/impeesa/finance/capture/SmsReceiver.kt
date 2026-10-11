package com.impeesa.finance.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.impeesa.finance.ImpeesaApp
import com.impeesa.finance.core.engine.MessageEnvelope
import com.impeesa.finance.core.engine.MessageSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant

/**
 * Real-time SMS capture. Parsing and saving take milliseconds; anything that fails here is
 * picked up later by [InboxReconcileWorker], so no message is lost (IMPLEMENTATION_PLAN.md §6.2).
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return

        // A long SMS arrives as several parts; join them back per sender, in order.
        val receivedAt = Instant.now()
        val envelopes = parts.groupBy { it.originatingAddress.orEmpty() }
            .filterKeys { it.isNotBlank() }
            .map { (sender, pieces) ->
                MessageEnvelope(MessageSource.SMS, sender, pieces.joinToString("") { it.messageBody.orEmpty() }, receivedAt)
            }

        val container = (context.applicationContext as ImpeesaApp).container
        val pending = goAsync()
        container.ioScope.launch {
            try {
                withTimeout(RECEIVER_BUDGET_MS) {
                    envelopes.forEach { envelope ->
                        val result = container.captureRepository.ingest(envelope)
                        container.notifier.onCaptured(result)
                    }
                }
            } catch (_: Exception) {
                // Never crash in a receiver; the inbox catch-up will process this message.
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val RECEIVER_BUDGET_MS = 8_000L
    }
}
