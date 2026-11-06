package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private fun readFloatArray(input: DataInputStream, count: Int) = FloatArray(count) {
    Float.fromBits(Integer.reverseBytes(input.readInt()))
}

/** JVM verification for the independent RAW bake and its native repair transcription. */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Expected one or more CXORBAKE1 fixtures" }
    args.forEach(::verifyFixture)
}

private fun verifyFixture(path: String) {
    val label = File(path).nameWithoutExtension
    DataInputStream(File(path).inputStream().buffered()).use { input ->
        val magic = ByteArray(9).also(input::readFully)
        check(magic.contentEquals("CXORBAKE1".toByteArray(Charsets.US_ASCII)))
        check(Integer.reverseBytes(input.readInt()) == 1)
        val ev = Float.fromBits(Integer.reverseBytes(input.readInt()))
        val renderingEv = Float.fromBits(Integer.reverseBytes(input.readInt()))
        val matrices = readFloatArray(input, 27)
        val film = readFloatArray(input, RawHsvBakeParameters.CURVE_SAMPLES)
        val inverse = readFloatArray(input, RawHsvBakeParameters.CURVE_SAMPLES)
        val expectedPre = readFloatArray(input, RawHsvGridBaker.GRID_FLOATS)
        val expectedRepaired = readFloatArray(input, RawHsvGridBaker.GRID_FLOATS)
        check(input.read() == -1)
        val p = RawHsvBakeParameters(
            ev,
            renderingEv,
            matrices.copyOfRange(0, 9),
            matrices.copyOfRange(9, 18),
            matrices.copyOfRange(18, 27),
            arrayOf(film),
            arrayOf(inverse),
        )
        val beforePre = p.preMatrix.copyOf()
        val beforePost = p.postMatrix.copyOf()
        val beforeExtra = p.extraMatrix.copyOf()
        val beforeFilm = film.copyOf()
        val beforeInverse = inverse.copyOf()
        val baked = RawHsvGridBaker.bake(p)
        fun metrics(actual: FloatArray, expected: FloatArray): Pair<Double, Float> {
            var sum = 0.0; var peak = 0f
            for (i in actual.indices) { val d = abs(actual[i] - expected[i]); if (d > peak) peak = d; sum += d.toDouble() * d }
            return sqrt(sum / actual.size) to peak
        }
        val pre = metrics(baked.grid, expectedPre)
        for (i in expectedPre.indices step 3) check((baked.grid[i] == -1000f) == (expectedPre[i] == -1000f)) { "invalid mask mismatch at node ${i / 3}" }
        check(pre.second < .01f) { "pre-repair max error ${pre.second}" }
        val repaired = RawHsvGridBaker.repair(baked.grid)
        val post = metrics(repaired, expectedRepaired)
        check(post.second < .002f) { "repaired max error ${post.second}" }
        check(p.preMatrix.contentEquals(beforePre) && p.postMatrix.contentEquals(beforePost) &&
            p.extraMatrix.contentEquals(beforeExtra) && film.contentEquals(beforeFilm) &&
            inverse.contentEquals(beforeInverse)) { "bake mutated input parameters" }
        // Exercise the three-channel path without introducing a second fitted fixture.
        val three = p.copy(
            filmCurves = arrayOf(film, film.copyOf(), film.copyOf()),
            composedInverseCurves = arrayOf(inverse, inverse.copyOf(), inverse.copyOf()),
        )
        val threeResult = RawHsvGridBaker.bake(three)
        check(threeResult.grid.size == RawHsvGridBaker.GRID_FLOATS && threeResult.grid.all { it.isFinite() }) { "three-curve path produced nonfinite output" }
        println("{\"fixture\":\"$label\",\"pre_rmse\":${pre.first},\"pre_max\":${pre.second}," +
            "\"repaired_rmse\":${post.first},\"repaired_max\":${post.second}," +
            "\"invalid_nodes\":${baked.invalidNodes},\"clamp_invalid_nodes\":${baked.clampInvalidNodes}," +
            "\"shared_three_curve_path\":true}")
    }
}
