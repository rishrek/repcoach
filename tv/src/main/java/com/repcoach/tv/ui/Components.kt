package com.repcoach.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.repcoach.core.Exercise
import com.repcoach.core.ExerciseText
import com.repcoach.core.Protocol
import com.repcoach.core.Skeleton
import com.repcoach.tv.GhostPlay
import com.repcoach.tv.TvState
import kotlin.math.min

// colours from the design spec
object TvColors {
    val Background = Color(0xFF0B0F14)
    val Card = Color(0xFF151B23)
    val PrimaryText = Color(0xFFF5F7FA)
    val SecondaryText = Color(0xFF9AA4B2)
    val Clean = Color(0xFF22C55E)
    val Fault = Color(0xFFEF4444)
    val Setup = Color(0xFFF59E0B)
    val PlayerOne = Color(0xFF3B82F6)
    val PlayerTwo = Color(0xFFF97316)
    val Bone = Color.White.copy(alpha = 0.8f)
    val Ghost = Color.White.copy(alpha = 0.25f)

    fun player(slot: Int) = if (slot == 2) PlayerTwo else PlayerOne
}

// TV safe margins
val SafeArea = Modifier.padding(horizontal = 48.dp, vertical = 27.dp)

// remote friendly button, gets a white outline and grows a little when focused
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "focus")
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .scale(scale)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(4.dp, Color.White, shape) else Modifier)
            .background(if (selected) TvColors.Clean.copy(alpha = 0.2f) else TvColors.Card, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) TvColors.Clean else TvColors.PrimaryText, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    }
}

// gives focus to something right away so the remote works
@Composable
fun InitialFocus(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) { runCatching { requester.requestFocus() } }
}

@Composable
fun ExercisePicker(current: Exercise, onPick: (Exercise) -> Unit, firstFocus: FocusRequester? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Exercise.entries.forEachIndexed { i, exercise ->
            TvButton(
                text = ExerciseText.label(exercise),
                onClick = { onPick(exercise) },
                selected = exercise == current,
                focusRequester = if (i == 0) firstFocus else null,
            )
        }
    }
}

// debug text with messages per second and phone ips
@Composable
fun DebugLine(state: TvState, modifier: Modifier = Modifier) {
    val phones = state.players.values.sortedBy { it.slot }.joinToString("  ") { "P${it.slot} ${it.address}" }
    Text(
        "%.0f msg/s | %s".format(state.messagesPerSecond, phones.ifEmpty { "no phone" }),
        color = TvColors.SecondaryText,
        fontSize = 24.sp,
        modifier = modifier,
    )
}

// wide banner for fault cues and setup hints
@Composable
fun Banner(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(color, RoundedCornerShape(12.dp)).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
    }
}

// Draws the skeleton flipped so the TV feels like a mirror. If there's a ghost it goes
// next to the live body.
@Composable
fun SkeletonCanvas(
    points: List<List<Float>>?,
    modifier: Modifier = Modifier,
    color: Color = TvColors.Bone,
    legColor: Color = color,
    ghost: List<List<Float>>? = null,
) {
    Canvas(modifier) {
        val height = min(size.height, size.width / Protocol.POSE_ASPECT)
        val width = height * Protocol.POSE_ASPECT
        val top = (size.height - height) / 2
        if (ghost != null) {
            drawBody(ghost, size.width / 2 - width * 0.05f, top, width, height, TvColors.Ghost, TvColors.Ghost)
            points?.let { drawBody(it, size.width / 2 - width * 0.95f, top, width, height, color, legColor) }
        } else {
            points?.let { drawBody(it, (size.width - width) / 2, top, width, height, color, legColor) }
        }
    }
}

private const val MIN_VISIBILITY = 0.5f

private fun DrawScope.drawBody(points: List<List<Float>>, left: Float, top: Float, width: Float, height: Float, color: Color, legColor: Color) {
    fun visible(i: Int) = points.getOrNull(i)?.let { it.size >= 3 && it[2] > MIN_VISIBILITY } == true
    fun at(i: Int) = Offset(left + (1f - points[i][0]) * width, top + points[i][1] * height)
    Skeleton.BONES.forEach { bone ->
        val (a, b) = bone
        if (visible(a) && visible(b)) {
            val boneColor = if (bone in Skeleton.LEG_BONES) legColor else color
            drawLine(boneColor, at(a), at(b), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        }
    }
    Skeleton.BONES.flatMap { listOf(it.first, it.second) }.distinct().forEach { i ->
        if (visible(i)) drawCircle(color, radius = 5.dp.toPx(), center = at(i))
    }
}

// plays the ghost back at the speed it was recorded, null once it's done
@Composable
fun rememberGhostPoints(ghost: GhostPlay?): List<List<Float>>? {
    var points by remember(ghost?.serial) { mutableStateOf<List<List<Float>>?>(null) }
    LaunchedEffect(ghost?.serial) {
        val play = ghost ?: return@LaunchedEffect
        val end = play.frames.last().t
        val start = withFrameMillis { it }
        while (true) {
            val elapsed = withFrameMillis { it } - start
            if (elapsed > end) {
                points = null
                break
            }
            points = play.frames.lastOrNull { it.t <= elapsed }?.points
        }
    }
    return points
}
