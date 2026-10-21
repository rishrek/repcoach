package com.repcoach.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Exercise { SQUAT, PUSHUP }

enum class FaultKind { KNEES_CAVE, CHEST_DOWN, SHALLOW, HIPS_SAG, HIPS_PIKE }

enum class ControlAction { START_SET, END_SET, RESET, SET_EXERCISE }

// one frame of the ghost rep, t is ms since the rep started
@Serializable
data class GhostFrame(val t: Long, val points: List<List<Float>>)

// Everything the phone and TV send each other. The type field in the JSON picks
// the class so please don't rename the SerialName values, both apps rely on them.
@Serializable
sealed class RcMessage {
    abstract val playerId: String?

    // phone to TV, up to 15 a second, 33 points of x y and visibility
    @Serializable
    @SerialName("pose")
    data class Pose(val timestamp: Long, val points: List<List<Float>>, override val playerId: String? = null) : RcMessage()

    // phone to TV whenever the person comes into view or leaves it
    @Serializable
    @SerialName("status")
    data class Status(
        val setupOk: Boolean,
        val message: String,
        val exercise: Exercise = Exercise.SQUAT,
        override val playerId: String? = null,
    ) : RcMessage()

    // phone to TV on every counted rep
    @Serializable
    @SerialName("rep")
    data class Rep(
        val number: Int,
        val clean: Boolean,
        val faults: List<FaultKind> = emptyList(),
        override val playerId: String? = null,
    ) : RcMessage()

    // phone to TV right when a fault happens
    @Serializable
    @SerialName("fault")
    data class Fault(val kind: FaultKind, override val playerId: String? = null) : RcMessage()

    // phone to TV when the set is over
    @Serializable
    @SerialName("summary")
    data class Summary(
        val reps: Int,
        val cleanReps: Int,
        val faultCounts: Map<FaultKind, Int>,
        val durationSec: Int,
        val aiText: String,
        val exercise: Exercise = Exercise.SQUAT,
        override val playerId: String? = null,
    ) : RcMessage()

    // Mostly TV to phone. The phone also sends SET_EXERCISE when someone picks the exercise
    // on the phone. In a duel autoEnd is off so the phone keeps counting until END_SET.
    @Serializable
    @SerialName("control")
    data class Control(
        val action: ControlAction,
        val exercise: Exercise? = null,
        val autoEnd: Boolean = true,
        override val playerId: String? = null,
    ) : RcMessage()

    // TV to phone right after it connects, tells the phone if it's P1 or P2
    @Serializable
    @SerialName("hello")
    data class Hello(override val playerId: String) : RcMessage()

    // sent to a third phone before the TV hangs up on it
    @Serializable
    @SerialName("roomFull")
    data class RoomFull(val message: String, override val playerId: String? = null) : RcMessage()

    // phone to TV when a rep starts, used for the ghost
    @Serializable
    @SerialName("repStart")
    data class RepStart(val timestamp: Long, override val playerId: String? = null) : RcMessage()

    // phone to TV, the deepest clean rep so far, max 60 frames
    @Serializable
    @SerialName("ghost")
    data class Ghost(val frames: List<GhostFrame>, override val playerId: String? = null) : RcMessage()
}

// tags a message with the player id, TV only messages stay as they are
fun RcMessage.withPlayer(id: String?): RcMessage = when (this) {
    is RcMessage.Pose -> copy(playerId = id)
    is RcMessage.Status -> copy(playerId = id)
    is RcMessage.Rep -> copy(playerId = id)
    is RcMessage.Fault -> copy(playerId = id)
    is RcMessage.Summary -> copy(playerId = id)
    is RcMessage.Control -> copy(playerId = id)
    is RcMessage.RepStart -> copy(playerId = id)
    is RcMessage.Ghost -> copy(playerId = id)
    is RcMessage.Hello, is RcMessage.RoomFull -> this
}

object RcJson {
    val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: RcMessage): String = json.encodeToString(RcMessage.serializer(), message)

    fun decode(text: String): RcMessage = json.decodeFromString(RcMessage.serializer(), text)
}

// we only round what goes over the network, the phone keeps full precision for the maths
fun round3(v: Float): Float = kotlin.math.round(v * 1000f) / 1000f

// rounds every point to 3 decimals before sending
fun RcMessage.Pose.rounded(): RcMessage.Pose =
    copy(points = points.map { point -> point.map(::round3) })
