package com.repcoach.core

// Recording format with one row per frame. Timestamp first, then the 33 world landmarks
// with x y z and visibility, then the 33 image landmarks with x y and visibility.
object PoseCsv {
    const val COLUMNS = 1 + Lm.COUNT * 4 + Lm.COUNT * 3

    val HEADER: String = buildList {
        add("timestamp_ms")
        repeat(Lm.COUNT) { i -> addAll(listOf("w${i}_x", "w${i}_y", "w${i}_z", "w${i}_v")) }
        repeat(Lm.COUNT) { i -> addAll(listOf("i${i}_x", "i${i}_y", "i${i}_v")) }
    }.joinToString(",")

    fun toRow(timestampMs: Long, frame: PoseFrame): String = buildString {
        append(timestampMs)
        frame.world.forEach { append(',').append(it.x).append(',').append(it.y).append(',').append(it.z).append(',').append(it.visibility) }
        frame.image.forEach { append(',').append(it.x).append(',').append(it.y).append(',').append(it.visibility) }
    }

    // header and empty lines give null, a broken row throws
    fun parseRow(line: String): Pair<Long, PoseFrame>? {
        if (line.isBlank() || line.startsWith("timestamp")) return null
        val cells = line.split(',')
        require(cells.size == COLUMNS) { "expected $COLUMNS columns, got ${cells.size}" }
        val v = cells.drop(1).map { it.trim().toFloat() }
        val world = List(Lm.COUNT) { i -> Landmark(v[i * 4], v[i * 4 + 1], v[i * 4 + 2], v[i * 4 + 3]) }
        val offset = Lm.COUNT * 4
        val image = List(Lm.COUNT) { i ->
            Landmark(v[offset + i * 3], v[offset + i * 3 + 1], 0f, v[offset + i * 3 + 2])
        }
        return cells[0].trim().toLong() to PoseFrame(world, image)
    }
}
