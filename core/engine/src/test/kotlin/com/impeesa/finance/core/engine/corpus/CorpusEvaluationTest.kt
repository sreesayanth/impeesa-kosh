package com.impeesa.finance.core.engine.corpus

import com.impeesa.finance.core.engine.Analysis
import com.impeesa.finance.core.engine.DetectionEngine
import com.impeesa.finance.core.engine.Route
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Runs the golden corpus and enforces the accuracy gates from IMPLEMENTATION_PLAN.md §6.12.
 * `./gradlew :core:engine:evaluate` prints the full report.
 */
class CorpusEvaluationTest {

    companion object {
        private lateinit var report: CorpusReport

        @BeforeClass
        @JvmStatic
        fun run() {
            val engine = DetectionEngine.withBundledData()
            val seeds = Corpus.load()
            val all = seeds + Corpus.variants(seeds, perEntry = 5)
            report = CorpusReport.build(seeds.size, all.map { it to engine.analyze(it.envelope()) })
            println(report.render())
            File("build/reports").mkdirs()
            File("build/reports/corpus.txt").writeText(report.render())
        }
    }

    @Test
    fun `corpus is big enough`() = assertTrue("corpus has ${report.total} messages", report.total >= 600)

    @Test
    fun `no OTP, promo, collect request or failed payment is ever captured`() =
        assertTrue(report.failures("negative captured").joinToString("\n"), report.negativesCaptured == 0)

    @Test
    fun `auto-log precision is at least 99_5 percent`() =
        assertTrue("precision ${report.autoLogPrecision}\n" + report.failures("auto-log wrong").joinToString("\n"), report.autoLogPrecision >= 0.995)

    @Test
    fun `transaction recall is at least 95 percent`() =
        assertTrue("recall ${report.recall}\n" + report.failures("missed").joinToString("\n"), report.recall >= 0.95)

    @Test
    fun `every captured amount is exact`() =
        assertTrue(report.failures("wrong amount").joinToString("\n"), report.wrongAmounts == 0)

    @Test
    fun `items that need a human go to review`() =
        assertTrue(report.failures("should review").joinToString("\n"), report.reviewMisses == 0)
}

/** Accuracy numbers for one corpus run. */
class CorpusReport private constructor(
    val seeds: Int,
    val total: Int,
    val negativesCaptured: Int,
    val autoLogPrecision: Double,
    val recall: Double,
    val autoLogRate: Double,
    val wrongAmounts: Int,
    val reviewMisses: Int,
    private val fieldAccuracy: Map<String, Pair<Int, Int>>,
    private val typeConfusion: Map<Pair<String, String>, Int>,
    private val problems: List<Pair<String, String>>,
) {
    fun failures(kind: String) = problems.filter { it.first == kind }.map { it.second }.take(25)

    fun render(): String = buildString {
        appendLine("=== Kosh engine corpus report ===")
        appendLine("Messages: $total ($seeds hand-written seeds + ${total - seeds} value variants)")
        appendLine("Auto-log precision: ${pct(autoLogPrecision)}   Recall (auto + review): ${pct(recall)}   Auto-log rate: ${pct(autoLogRate)}")
        appendLine("Negatives captured: $negativesCaptured   Wrong amounts: $wrongAmounts   Review misses: $reviewMisses")
        appendLine("Field accuracy (where expected):")
        fieldAccuracy.forEach { (field, counts) ->
            appendLine("  %-10s %s  (%d/%d)".format(field, pct(counts.first.toDouble() / maxOf(1, counts.second)), counts.first, counts.second))
        }
        appendLine("Type confusion (expected -> got: count), mismatches only:")
        typeConfusion.filter { it.key.first != it.key.second }.forEach { (k, v) -> appendLine("  ${k.first} -> ${k.second}: $v") }
        if (problems.isNotEmpty()) {
            appendLine("Problems (first 40):")
            problems.take(40).forEach { appendLine("  [${it.first}] ${it.second}") }
        }
    }

    companion object {
        private val negativeTypes = setOf("OTP", "PROMOTIONAL", "COLLECT_REQUEST", "FAILED", "UNKNOWN", "STATEMENT", "UPCOMING", "BALANCE")

        private fun pct(v: Double) = "%.2f%%".format(v * 100)

        fun build(seeds: Int, results: List<Pair<CorpusEntry, Analysis>>): CorpusReport {
            val problems = mutableListOf<Pair<String, String>>()
            val fields = linkedMapOf<String, Pair<Int, Int>>()
            fun field(name: String, ok: Boolean) {
                val (hit, n) = fields[name] ?: (0 to 0)
                fields[name] = (hit + if (ok) 1 else 0) to (n + 1)
            }
            val confusion = HashMap<Pair<String, String>, Int>()
            var negativesCaptured = 0
            var autoLogged = 0
            var autoCorrect = 0
            var expectedTxns = 0
            var captured = 0
            var wrongAmounts = 0
            var reviewMisses = 0

            for ((entry, a) in results) {
                val e = entry.expect
                val key = e.type to a.type.name
                confusion[key] = (confusion[key] ?: 0) + 1
                field("type", e.type == a.type.name)
                val label = "${entry.id}: ${entry.body.take(90).replace('\n', ' ')} => ${a.type} ${a.route} amt=${a.amount?.paise} dir=${a.direction} conf=${"%.2f".format(a.confidence)}"

                if (e.type in negativeTypes && a.isTransaction) {
                    negativesCaptured++
                    problems += "negative captured" to label
                }
                if (a.route == Route.AUTO_LOG) {
                    autoLogged++
                    val correct = entry.isTransaction && a.amount?.paise == e.amountPaise && a.direction?.name == e.direction
                    if (correct) autoCorrect++ else problems += "auto-log wrong" to label
                }
                if (e.type == "FAILED_BUT_DEBITED" && a.route != Route.REVIEW) {
                    reviewMisses++
                    problems += "should review" to label
                }
                if (entry.isTransaction) {
                    expectedTxns++
                    if (a.isTransaction) captured++ else problems += "missed" to label
                    if (e.review && a.route != Route.REVIEW) {
                        reviewMisses++
                        problems += "should review" to label
                    }
                    if (a.isTransaction && e.amountPaise != null) {
                        val ok = a.amount?.paise == e.amountPaise
                        field("amount", ok)
                        if (!ok) {
                            wrongAmounts++
                            problems += "wrong amount" to label
                        }
                        field("direction", a.direction?.name == e.direction)
                        e.currency?.let { field("currency", it == a.currency) }
                    }
                    e.mask?.let { field("mask", it == a.mask).also { _ -> if (it != a.mask) problems += "mask" to "$label mask=${a.mask}" } }
                    e.reference?.let { field("reference", it == a.reference) }
                    e.merchant?.let {
                        val ok = it.equals(a.merchant?.displayName, ignoreCase = true)
                        field("merchant", ok)
                        if (!ok) problems += "merchant" to "$label merchant=${a.merchant?.displayName} (raw=${a.counterparty})"
                    }
                    e.balancePaise?.let { field("balance", it == a.balance?.paise) }
                    e.limitPaise?.let { field("limit", it == a.availableLimit?.paise) }
                }
            }
            return CorpusReport(
                seeds = seeds,
                total = results.size,
                negativesCaptured = negativesCaptured,
                autoLogPrecision = if (autoLogged == 0) 1.0 else autoCorrect.toDouble() / autoLogged,
                recall = if (expectedTxns == 0) 1.0 else captured.toDouble() / expectedTxns,
                autoLogRate = if (expectedTxns == 0) 0.0 else autoLogged.toDouble() / expectedTxns,
                wrongAmounts = wrongAmounts,
                reviewMisses = reviewMisses,
                fieldAccuracy = fields,
                typeConfusion = confusion,
                problems = problems,
            )
        }
    }
}
