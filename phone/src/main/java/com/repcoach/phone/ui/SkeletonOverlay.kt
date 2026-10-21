package com.repcoach.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.repcoach.core.PoseFrame
import com.repcoach.core.Skeleton
import kotlin.math.min

private const val MIN_VISIBILITY = 0.5f

// draws the skeleton scaled the same way as the camera preview
@Composable
fun SkeletonOverlay(
    frame: PoseFrame?,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.8f),
) {
    Canvas(modifier) {
        if (frame == null || imageWidth == 0 || imageHeight == 0) return@Canvas
        val scale = min(size.width / imageWidth, size.height / imageHeight)
        val left = (size.width - imageWidth * scale) / 2
        val top = (size.height - imageHeight * scale) / 2
        val points = frame.image
        fun at(i: Int) = Offset(left + points[i].x * imageWidth * scale, top + points[i].y * imageHeight * scale)
        fun visible(i: Int) = points[i].visibility > MIN_VISIBILITY

        Skeleton.BONES.forEach { (a, b) ->
            if (visible(a) && visible(b)) {
                drawLine(color, at(a), at(b), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Skeleton.BONES.flatMap { listOf(it.first, it.second) }.distinct().forEach { i ->
            if (visible(i)) drawCircle(color, radius = 5.dp.toPx(), center = at(i))
        }
    }
}
