package com.repcoach.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PoseCsvTest {
    @Test
    fun `row round trip keeps every value`() {
        val frame = Synth.frame(97.5f, lean = 22f, kneeRatio = 0.75f)
        val (t, parsed) = PoseCsv.parseRow(PoseCsv.toRow(1_234L, frame))!!
        assertEquals(1_234L, t)
        assertEquals(frame, parsed)
    }

    @Test
    fun `header and row have the same column count`() {
        assertEquals(232, PoseCsv.COLUMNS)
        assertEquals(PoseCsv.COLUMNS, PoseCsv.HEADER.split(',').size)
        assertEquals(PoseCsv.COLUMNS, PoseCsv.toRow(0L, Synth.frame(170f)).split(',').size)
    }

    @Test
    fun `header and blank lines parse to null`() {
        assertNull(PoseCsv.parseRow(PoseCsv.HEADER))
        assertNull(PoseCsv.parseRow("  "))
    }

    @Test
    fun `short row is rejected`() {
        assertThrows<IllegalArgumentException> { PoseCsv.parseRow("12,0.1,0.2") }
    }

    @Test
    fun `replaying a recording gives the same events as the live run`() {
        val frames = (Synth.standing(30) + List(3) { Synth.repAngles() }.flatten()).map { Synth.frame(it) }
        val live = SquatAnalyzer()
        val liveEvents = frames.flatMapIndexed { i, f -> live.analyze(f, i * Synth.FRAME_MS) }

        val csv = listOf(PoseCsv.HEADER) + frames.mapIndexed { i, f -> PoseCsv.toRow(i * Synth.FRAME_MS, f) }
        val replay = SquatAnalyzer()
        val replayEvents = csv.mapNotNull(PoseCsv::parseRow).flatMap { (t, f) -> replay.analyze(f, t) }

        assertEquals(liveEvents, replayEvents)
        assertEquals(3, replay.reps)
    }
}
