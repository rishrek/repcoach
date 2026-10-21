package com.repcoach.core

import java.io.File
import java.util.Locale

// one line of labels.csv
data class ReplayLabel(val file: String, val trueReps: Int, val badReps: Set<Int>, val exercise: Exercise = Exercise.SQUAT)

data class ReplayResult(val label: ReplayLabel, val counted: Int, val flagged: Set<Int>) {
    val badCaught: Int get() = label.badReps.count { it in flagged }
    val cleanWronglyFlagged: Int get() = flagged.count { it !in label.badReps }
}

// runs our recordings through the analyzers and checks how well they count
object Replay {
    fun parseLabels(lines: List<String>): List<ReplayLabel> = lines
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("file") && !it.startsWith("#") }
        .map { line ->
            val cells = line.split(',').map { it.trim() }
            require(cells.size >= 2) { "labels.csv row needs at least file,true_reps: $line" }
            ReplayLabel(
                file = cells[0],
                trueReps = cells[1].toInt(),
                badReps = cells.getOrNull(2).orEmpty().split(';').filter { it.isNotBlank() }.map { it.trim().toInt() }.toSet(),
                exercise = cells.getOrNull(3)?.takeIf { it.isNotBlank() }?.let(::parseExercise) ?: Exercise.SQUAT,
            )
        }

    fun parseExercise(text: String): Exercise =
        Exercise.valueOf(text.uppercase(Locale.ROOT).replace("-", "").replace("_", "").replace("PUSHUPS", "PUSHUP").replace("SQUATS", "SQUAT"))

    // gives back how many reps were counted and which ones got flagged
    fun run(lines: Sequence<String>, analyzer: ExerciseAnalyzer): Pair<Int, Set<Int>> {
        val flagged = mutableSetOf<Int>()
        lines.mapNotNull(PoseCsv::parseRow).forEach { (t, frame) ->
            analyzer.analyze(frame, t).forEach { event ->
                if (event is AnalyzerEvent.RepCounted && !event.clean) flagged += event.number
            }
        }
        return analyzer.reps to flagged
    }

    // same formula as in the spec, null when nothing is labelled
    fun accuracy(results: List<ReplayResult>): Double? {
        val truth = results.sumOf { it.label.trueReps }
        if (truth == 0) return null
        return 1.0 - results.sumOf { kotlin.math.abs(it.counted - it.label.trueReps) }.toDouble() / truth
    }

    fun report(results: List<ReplayResult>): String = buildString {
        appendLine("%-40s %8s %8s %11s %14s".format("file", "true", "counted", "bad caught", "clean flagged"))
        results.forEach { r ->
            appendLine(
                "%-40s %8d %8d %11s %14d".format(
                    r.label.file.take(40), r.label.trueReps, r.counted, "${r.badCaught}/${r.label.badReps.size}", r.cleanWronglyFlagged,
                ),
            )
        }
        appendLine()
        Exercise.entries.forEach { exercise ->
            val group = results.filter { it.label.exercise == exercise }
            accuracy(group)?.let { appendLine("%-10s count accuracy %.1f%% over %d files".format(ExerciseText.label(exercise), it * 100, group.size)) }
        }
        accuracy(results)?.let { appendLine("TOTAL      count accuracy %.1f%% (target 95%%)".format(it * 100)) }
        appendLine("Bad reps caught: ${results.sumOf { it.badCaught }}/${results.sumOf { it.label.badReps.size }}")
        append("Clean reps wrongly flagged: ${results.sumOf { it.cleanWronglyFlagged }}")
    }

    // runs every file listed in labels.csv
    fun runDirectory(dir: File): String {
        val labelsFile = File(dir, "labels.csv")
        val labels = if (labelsFile.exists()) parseLabels(labelsFile.readLines()) else emptyList()
        if (labels.isEmpty()) {
            return "No labelled recordings in ${dir.path}. Record sets with the phone's Record CSV switch, " +
                "copy them into data/ and list them in data/labels.csv."
        }
        val missing = labels.filterNot { File(dir, it.file).exists() }.map { it.file }
        val results = labels.filter { File(dir, it.file).exists() }.map { label ->
            val (counted, flagged) = File(dir, label.file).useLines { run(it, ExerciseAnalyzer.create(label.exercise)) }
            ReplayResult(label, counted, flagged)
        }
        return report(results) + if (missing.isEmpty()) "" else "\nMissing files: ${missing.joinToString()}"
    }
}

// run it with the gradle replay task
fun main(args: Array<String>) {
    println(Replay.runDirectory(File(args.firstOrNull() ?: "data")))
}
