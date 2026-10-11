package com.impeesa.finance.core.engine.tools

import java.io.File
import kotlin.random.Random

/**
 * Turns real messages into safe corpus material (IMPLEMENTATION_PLAN.md §13.2).
 *
 * Input: one message per line, `sender<TAB>body` (newlines in the body written as \n).
 * Output: JSONL skeletons with digits, account masks, phone numbers, UPI ids and names replaced.
 * Fill in each line's "expect" by hand before adding it to src/test/resources/corpus.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: anonymize <input.tsv> <output.jsonl>" }
    val random = Random(42)
    val out = File(args[1]).bufferedWriter()
    File(args[0]).readLines().filter { it.isNotBlank() }.forEachIndexed { index, line ->
        val sender = line.substringBefore('\t')
        val body = Anonymizer.scrub(line.substringAfter('\t').replace("\\n", "\n"), random)
        val escaped = body.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        out.appendLine("""{"id":"new-${index + 1}","source":"SMS","sender":"$sender","body":"$escaped","receivedAt":"2026-01-01T12:00:00+05:30","expect":{"type":"TODO"}}""")
    }
    out.close()
    println("Wrote ${args[1]}")
}

object Anonymizer {
    private val longDigits = Regex("\\d{6,}")
    private val mask = Regex("(?i)([x*]{1,}\\s?)(\\d{3,6})\\b")
    private val vpa = Regex("([A-Za-z0-9.\\-_]+)@([A-Za-z]+)")
    private val nameAfter = Regex("\\b(to|from|by|To|From|By)\\s+(Mr\\.?\\s+|Ms\\.?\\s+)?([A-Z][A-Za-z]+(?:\\s+[A-Z][A-Za-z]*\\.?){0,2})")
    private val keepWords = setOf("UPI", "VPA", "NEFT", "IMPS", "RTGS", "ATM", "HDFC", "ICICI", "SBI", "AXIS", "KOTAK", "BANK", "YOUR", "A")
    private val fakeNames = listOf("ASHA RAO", "VIKRAM S", "MEERA IYER", "KARAN MEHTA", "NISHA P", "ROHIT DAS")

    fun scrub(body: String, random: Random): String {
        var text = longDigits.replace(body) { m -> randomDigits(m.value.length, random) }
        text = mask.replace(text) { m -> m.groupValues[1] + randomDigits(m.groupValues[2].length, random) }
        text = vpa.replace(text) { m ->
            val local = m.groupValues[1]
            val looksBusiness = local.length >= 4 && local.all { it.isLetter() } && local.lowercase() == local && local.length <= 12
            if (looksBusiness) m.value else "user${randomDigits(4, random)}@${m.groupValues[2]}"
        }
        text = nameAfter.replace(text) { m ->
            val name = m.groupValues[3]
            if (name.split(' ').any { it.uppercase() in keepWords } || name.length < 3) m.value
            else m.groupValues[1] + " " + m.groupValues[2] + fakeNames[random.nextInt(fakeNames.size)]
        }
        return text
    }

    private fun randomDigits(length: Int, random: Random) =
        buildString { repeat(length) { append(if (it == 0) random.nextInt(1, 10) else random.nextInt(10)) } }
}
