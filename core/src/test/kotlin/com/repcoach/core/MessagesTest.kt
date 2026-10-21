package com.repcoach.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class MessagesTest {
    companion object {
        @JvmStatic
        fun allMessages(): List<RcMessage> = listOf(
            RcMessage.Pose(1234L, List(33) { i -> listOf(i / 100f, 0.5f, 0.9f) }),
            RcMessage.Status(setupOk = false, message = "Step back"),
            RcMessage.Rep(number = 3, clean = false, faults = listOf(FaultKind.KNEES_CAVE, FaultKind.CHEST_DOWN)),
            RcMessage.Fault(FaultKind.SHALLOW),
            RcMessage.Summary(
                reps = 10,
                cleanReps = 8,
                faultCounts = mapOf(FaultKind.KNEES_CAVE to 2),
                durationSec = 42,
                aiText = "Good depth.",
            ),
            RcMessage.Control(ControlAction.END_SET),
            RcMessage.Hello("P1"),
            RcMessage.RoomFull("Two players are already connected"),
            RcMessage.RepStart(5_000L, playerId = "P2"),
            RcMessage.Ghost(listOf(GhostFrame(0, listOf(listOf(0.1f, 0.2f, 0.9f))), GhostFrame(33, listOf(listOf(0.1f, 0.25f, 0.9f))))),
            RcMessage.Control(ControlAction.SET_EXERCISE, exercise = Exercise.PUSHUP, autoEnd = false),
        )
    }

    @ParameterizedTest
    @MethodSource("allMessages")
    fun `every message survives a JSON round trip`(message: RcMessage) {
        assertEquals(message, RcJson.decode(RcJson.encode(message)))
    }

    @Test
    fun `type discriminator names match the protocol`() {
        val expected = listOf("pose", "status", "rep", "fault", "summary", "control", "hello", "roomFull", "repStart", "ghost", "control")
        allMessages().zip(expected).forEach { (message, type) ->
            assertTrue(RcJson.encode(message).contains("\"type\":\"$type\""), "missing type $type")
        }
    }

    @Test
    fun `unknown fields are ignored`() {
        val text = """{"type":"fault","kind":"KNEES_CAVE","playerId":"P1","future":42}"""
        assertEquals(RcMessage.Fault(FaultKind.KNEES_CAVE, playerId = "P1"), RcJson.decode(text))
    }

    @Test
    fun `withPlayer tags phone messages and leaves TV messages alone`() {
        assertEquals("P2", RcMessage.Fault(FaultKind.SHALLOW).withPlayer("P2").playerId)
        assertEquals("P1", RcMessage.Hello("P1").withPlayer("P2").playerId)
        allMessages().filterNot { it is RcMessage.Hello || it is RcMessage.RoomFull }.forEach {
            assertEquals("P2", it.withPlayer("P2").playerId, it.toString())
        }
    }

    @Test
    fun `older messages without new fields still decode`() {
        val old = """{"type":"summary","reps":3,"cleanReps":2,"faultCounts":{"SHALLOW":1},"durationSec":20,"aiText":"ok"}"""
        val summary = RcJson.decode(old) as RcMessage.Summary
        assertEquals(Exercise.SQUAT, summary.exercise)
        assertEquals(null, summary.playerId)
    }

    @Test
    fun `round3 keeps three decimals`() {
        assertEquals(0.123f, round3(0.123456f))
        assertEquals(0.124f, round3(0.1236f))
        assertEquals(-0.5f, round3(-0.5f))
    }

    @Test
    fun `rounded pose rounds every value and keeps the timestamp`() {
        val pose = RcMessage.Pose(99L, listOf(listOf(0.123456f, 0.98765f, 0.5f)))
        assertEquals(RcMessage.Pose(99L, listOf(listOf(0.123f, 0.988f, 0.5f))), pose.rounded())
    }
}
