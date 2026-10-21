package com.repcoach.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReplayTest {
    private fun squatCsv(reps: Int, cavedRep: Int? = null): List<String> {
        val frames = Synth.standing(30).map { Synth.frame(it) } +
            (1..reps).flatMap { n -> Synth.repAngles().map { Synth.frame(it, kneeRatio = if (n == cavedRep) 0.6f else 1f) } } +
            Synth.standing(10).map { Synth.frame(it) }
        return listOf(PoseCsv.HEADER) + frames.mapIndexed { i, f -> PoseCsv.toRow(i * Synth.FRAME_MS, f) }
    }

    @Test
    fun `labels parse with optional bad reps and exercise`() {
        val labels = Replay.parseLabels(
            listOf(
                "file,true_reps,bad_reps,exercise",
                "a.csv,10,3;7,squat",
                "b.csv, 8 ,,push-ups",
                "c.csv,5",
                "",
            ),
        )
        assertEquals(
            listOf(
                ReplayLabel("a.csv", 10, setOf(3, 7), Exercise.SQUAT),
                ReplayLabel("b.csv", 8, emptySet(), Exercise.PUSHUP),
                ReplayLabel("c.csv", 5, emptySet(), Exercise.SQUAT),
            ),
            labels,
        )
    }

    @Test
    fun `accuracy is one minus total error over total truth`() {
        val results = listOf(
            ReplayResult(ReplayLabel("a", 10, emptySet()), 10, emptySet()),
            ReplayResult(ReplayLabel("b", 10, emptySet()), 9, emptySet()),
        )
        assertEquals(0.95, Replay.accuracy(results)!!, 1e-9)
        assertNull(Replay.accuracy(emptyList()))
    }

    @Test
    fun `replaying a recording counts reps and finds the bad one`() {
        val (counted, flagged) = Replay.run(squatCsv(3, cavedRep = 2).asSequence(), SquatAnalyzer())
        assertEquals(3, counted)
        assertEquals(setOf(2), flagged)
        val result = ReplayResult(ReplayLabel("x", 3, setOf(2)), counted, flagged)
        assertEquals(1, result.badCaught)
        assertEquals(0, result.cleanWronglyFlagged)
    }

    @Test
    fun `directory replay prints a report`(@TempDir dir: File) {
        File(dir, "squat_1.csv").writeText(squatCsv(4, cavedRep = 1).joinToString("\n"))
        File(dir, "labels.csv").writeText("file,true_reps,bad_reps\nsquat_1.csv,4,1\nmissing.csv,3,\n")
        val report = Replay.runDirectory(dir)
        assertTrue("100.0%" in report, report)
        assertTrue("Bad reps caught: 1/1" in report, report)
        assertTrue("Missing files: missing.csv" in report, report)
    }

    @Test
    fun `directory without labels explains what to do`(@TempDir dir: File) {
        assertTrue(Replay.runDirectory(dir).startsWith("No labelled recordings"))
    }

    // prints the report for our real recordings, never fails
    @Test
    fun `report for data folder`() {
        val data = listOf(File("../data"), File("data")).firstOrNull { it.isDirectory } ?: return
        println("\n=== Replay accuracy (data/) ===\n" + Replay.runDirectory(data))
    }
}
