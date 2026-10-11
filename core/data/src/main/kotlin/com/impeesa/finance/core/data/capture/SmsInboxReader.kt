package com.impeesa.finance.core.data.capture

import android.content.Context
import android.provider.Telephony
import com.impeesa.finance.core.engine.MessageEnvelope
import com.impeesa.finance.core.engine.MessageSource
import java.time.Instant

/**
 * Reads the SMS inbox through the system content provider. Requires READ_SMS.
 * Streams rows from a cursor so a 50,000-message inbox never sits in memory at once.
 */
internal class SmsInboxReader(private val context: Context) {

    fun count(sinceMillis: Long): Int =
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(sinceMillis.toString()),
            null,
        )?.use { it.count } ?: 0

    /** Calls [block] for each message received since [sinceMillis], oldest first. */
    suspend fun forEach(sinceMillis: Long, block: suspend (MessageEnvelope) -> Unit) {
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(sinceMillis.toString()),
            "${Telephony.Sms.DATE} ASC",
        ) ?: return
        cursor.use {
            val address = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (it.moveToNext()) {
                val sender = it.getString(address) ?: continue
                val text = it.getString(body) ?: continue
                block(MessageEnvelope(MessageSource.SMS, sender, text, Instant.ofEpochMilli(it.getLong(date))))
            }
        }
    }
}
