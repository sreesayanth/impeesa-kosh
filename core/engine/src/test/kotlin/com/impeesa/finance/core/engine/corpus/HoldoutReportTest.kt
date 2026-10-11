package com.impeesa.finance.core.engine.corpus

import com.impeesa.finance.core.engine.DetectionEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Messages written in phrasings the engine was not tuned on. Accuracy here is reported, not
 * gated, so it stays an honest estimate. Safety is gated: nothing that must be ignored may be captured.
 * When a hold-out message is fixed, move it into /corpus and write a fresh one here.
 */
class HoldoutReportTest {
    @Test
    fun `holdout report`() {
        val engine = DetectionEngine.withBundledData()
        val entries = Corpus.load("/corpus-holdout")
        val report = CorpusReport.build(entries.size, entries.map { it to engine.analyze(it.envelope()) })
        val text = report.render().replace("Impeesa engine corpus report", "Hold-out report (not tuned on)")
        println(text)
        File("build/reports").mkdirs()
        File("build/reports/holdout.txt").writeText(text)
        assertEquals(report.failures("negative captured").joinToString("\n"), 0, report.negativesCaptured)
    }
}
