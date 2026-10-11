package com.impeesa.finance.core.engine.corpus

import com.impeesa.finance.core.engine.MessageEnvelope
import com.impeesa.finance.core.engine.MessageSource
import com.impeesa.finance.core.model.MoneyFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.OffsetDateTime
import kotlin.random.Random

@Serializable
data class Expectation(
    val type: String,
    val direction: String? = null,
    val amountPaise: Long? = null,
    val currency: String? = null,
    val mask: String? = null,
    val merchant: String? = null,
    val reference: String? = null,
    val balancePaise: Long? = null,
    val limitPaise: Long? = null,
    /** True when the message must land in Review rather than be auto-logged. */
    val review: Boolean = false,
)

@Serializable
data class CorpusEntry(
    val id: String,
    val source: String,
    val sender: String,
    val title: String? = null,
    val body: String,
    val receivedAt: String,
    val expect: Expectation,
) {
    fun envelope() = MessageEnvelope(
        source = MessageSource.valueOf(source),
        sender = sender,
        title = title,
        body = body,
        receivedAt = OffsetDateTime.parse(receivedAt).toInstant(),
    )

    val isTransaction: Boolean get() = expect.type == "TRANSACTION" || expect.type == "REFUND"
}

object Corpus {
    private val json = Json { ignoreUnknownKeys = true }

    /** Every *.jsonl file under a test resources folder (default /corpus). */
    fun load(folder: String = "/corpus"): List<CorpusEntry> {
        val dir = File(requireNotNull(Corpus::class.java.getResource(folder)) { "$folder folder missing" }.toURI())
        return dir.listFiles { f -> f.extension == "jsonl" }!!.sortedBy { it.name }.flatMap { file ->
            file.readLines().filter { it.isNotBlank() && !it.startsWith("//") }.map { line ->
                json.decodeFromString(CorpusEntry.serializer(), line)
            }
        }
    }

    private val number = Regex("[0-9][0-9,]*(?:\\.[0-9]{1,2})?")

    /**
     * Makes value variants of each message: same wording, different amount, written in the same
     * style (commas, decimals). Tests robustness to values, not to new formats.
     */
    fun variants(entries: List<CorpusEntry>, perEntry: Int): List<CorpusEntry> = entries.flatMap { entry ->
        val random = Random(entry.id.hashCode())
        val original = findAmountText(entry) ?: return@flatMap emptyList()
        (1..perEntry).map { n ->
            val paise = randomAmount(random)
            val text = render(paise, original.value)
            val body = entry.body.replaceRange(original.range, text)
            val title = entry.title
            val newExpect = if (entry.expect.amountPaise != null) entry.expect.copy(amountPaise = reparse(text)) else entry.expect
            entry.copy(id = "${entry.id}~$n", body = body, title = title, expect = newExpect)
        }
    }

    /** Locates the expected amount's text in the body (or the first amount for negatives). */
    private fun findAmountText(entry: CorpusEntry): MatchResult? {
        val expected = entry.expect.amountPaise
        return number.findAll(entry.body).firstOrNull { m ->
            val value = parse(m.value) ?: return@firstOrNull false
            if (expected != null) value == expected else value >= 100 && hasCurrencyBefore(entry.body, m.range.first)
        }
    }

    private fun hasCurrencyBefore(body: String, index: Int): Boolean {
        val before = body.substring(maxOf(0, index - 5), index).lowercase()
        return "rs" in before || "inr" in before || "₹" in before
    }

    private fun randomAmount(random: Random): Long {
        val rupees = when (random.nextInt(10)) {
            in 0..3 -> random.nextLong(1, 1_000)
            in 4..7 -> random.nextLong(1_000, 1_00_000)
            else -> random.nextLong(1_00_000, 50_00_000)
        }
        return rupees * 100 + if (random.nextBoolean()) random.nextLong(0, 100) else 0
    }

    private fun render(paise: Long, style: String): String {
        val decimals = style.substringAfter('.', "").length
        val whole = paise / 100
        val wholeText = if (',' in style) MoneyFormat.group(whole) else whole.toString()
        return when (decimals) {
            0 -> wholeText
            1 -> "$wholeText.${(paise % 100) / 10}"
            else -> "$wholeText." + (paise % 100).toString().padStart(2, '0')
        }
    }

    private fun parse(text: String): Long? {
        val clean = text.trimEnd(',', '.').replace(",", "")
        val parts = clean.split('.')
        if (parts.size > 2 || parts[0].isEmpty() || parts[0].length > 12) return null
        val fraction = parts.getOrElse(1) { "" }
        if (fraction.length > 2) return null
        return parts[0].toLong() * 100 + fraction.padEnd(2, '0').toLong()
    }

    private fun reparse(text: String): Long = requireNotNull(parse(text))
}
