package com.impeesa.finance.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SourceHashTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val body = "Rs.486.00 debited from A/c XX4821 to VPA swiggy@icici on 11-10-26. UPI Ref 628491037215."

    private fun sms(body: String, at: String, sender: String = "VM-HDFCBK") =
        MessageEnvelope(MessageSource.SMS, sender, body, Instant.parse(at))

    @Test
    fun `receiver and inbox timestamps a few seconds apart give the same identity`() {
        val live = sms(body, "2026-10-11T07:54:59Z")
        val inbox = sms(body, "2026-10-11T07:55:03Z")
        assertEquals(SourceHash.of(live, zone), SourceHash.of(inbox, zone))
    }

    @Test
    fun `whitespace and sender case do not matter`() {
        assertEquals(
            SourceHash.of(sms(body, "2026-10-11T07:55:00Z"), zone),
            SourceHash.of(sms(body.replace(" ", "\n "), "2026-10-11T08:10:00Z", sender = "vm-hdfcbk"), zone),
        )
    }

    @Test
    fun `different message, sender or day gives a different identity`() {
        val base = SourceHash.of(sms(body, "2026-10-11T07:55:00Z"), zone)
        assertNotEquals(base, SourceHash.of(sms(body.replace("486", "487"), "2026-10-11T07:55:00Z"), zone))
        assertNotEquals(base, SourceHash.of(sms(body, "2026-10-11T07:55:00Z", sender = "AD-HDFCBK"), zone))
        assertNotEquals(base, SourceHash.of(sms(body, "2026-10-12T07:55:00Z"), zone))
    }
}
