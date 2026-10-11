package com.impeesa.finance.core.engine

import com.impeesa.finance.core.engine.text.TextNormalizer
import java.security.MessageDigest
import java.time.ZoneId

/**
 * Identity of a message, used to make capture idempotent: the live receiver, the history import
 * and the inbox catch-up all see the same SMS, but it is stored once.
 *
 * Uses the local date, not the exact time: the receiver's clock and the SMS provider's "date"
 * column can differ by seconds and straddle a minute. The trade-off: two byte-identical messages
 * from one sender on one day count once. Bank alerts almost always carry a unique reference.
 */
object SourceHash {
    fun of(envelope: MessageEnvelope, zone: ZoneId): String {
        val day = envelope.receivedAt.atZone(zone).toLocalDate()
        val key = listOf(
            envelope.source.name,
            envelope.sender.trim().uppercase(),
            TextNormalizer.normalize(listOfNotNull(envelope.title, envelope.body).joinToString(" ")),
            day.toString(),
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
