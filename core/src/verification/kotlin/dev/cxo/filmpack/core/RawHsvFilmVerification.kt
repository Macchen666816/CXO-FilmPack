package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

private data class RawFilmFixture(
    val input: ShortArray, val sensor: ShortArray, val expected: ShortArray,
    val grid: FloatArray, val post: FloatArray, val matrix: FloatArray, val ev: Float, val highlight: Float,
)

private fun readRawFilmFixture(file: File): RawFilmFixture {
    val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val magic = ByteArray(8).also { b.get(it) }.toString(Charsets.US_ASCII)
    require(magic == "CXORAW01")
    val count = b.int
    val gridSize = b.int
    val postSize = b.int
    require(b.int == 3 && count in 1..100000 && gridSize == 17 * 91 * 12 * 3 && postSize == 32769)
    val matrix = FloatArray(9) { b.float }
    val ev = b.float; val highlight = b.float
    val grid = FloatArray(gridSize) { b.float }
    val post = FloatArray(postSize) { b.float }
    val input = ShortArray(count * 3) { b.short }
    val sensor = ShortArray(count * 3) { b.short }
    val expected = ShortArray(count * 3) { b.short }
    require(!b.hasRemaining())
    return RawFilmFixture(input, sensor, expected, grid, post, matrix, ev, highlight)
}

private fun rawMetric(name: String, actual: ShortArray, expected: ShortArray, tolerance: Int) {
    var squares = 0.0; var max = 0; var equal = 0
    for (i in actual.indices) {
        val d = actual[i].toInt() - expected[i]
        squares += d.toDouble() * d
        max = maxOf(max, abs(d))
        if (d == 0) equal++
    }
    val rmse = sqrt(squares / actual.size) * 255 / 32767
    println("$name components=${actual.size} equal=$equal maxQ15=$max rmse255=$rmse")
    check(max <= tolerance) { "$name exceeded $tolerance Q15: $max" }
}

private fun expectRawFailure(block: () -> Unit) {
    try { block() } catch (_: IllegalArgumentException) { return }
    error("Expected rejected RAW argument")
}

fun main(args: Array<String>) {
    val directory = File(args.single())
    val fixtures = directory.listFiles { file -> file.extension == "golden" }!!.sortedBy { it.name }
    check(fixtures.size == 4)
    for (file in fixtures) {
        val f = readRawFilmFixture(file)
        val grid = f.grid.copyOf()
        val post = f.post.copyOf()
        val renderer = RawHsvFilmRenderer(grid, post)
        // Mutation after construction must not alter rendering parameters.
        grid.fill(Float.NaN); post.fill(Float.NaN)
        val rendered = ShortArray(f.sensor.size)
        renderer.renderQ15(f.sensor, rendered)
        rawMetric(file.name + " renderOnly", rendered, f.expected, 2)
        val inPlace = f.sensor.copyOf()
        renderer.renderQ15(inPlace, inPlace)
        check(inPlace.contentEquals(rendered))
        val working = ShortArray(f.input.size)
        RawSensorToWorking(f.matrix, f.ev, f.highlight).renderQ15(f.input, working)
        rawMetric(file.name + " sensorOnly", working, f.sensor, 1)
        renderer.renderQ15(working, working)
        // Cross-context exposure and gamut rounding may propagate a few Q15
        // codes; six is below .05 of an 8-bit output code.
        rawMetric(file.name + " full", working, f.expected, 6)
        expectRawFailure { renderer.renderQ15(shortArrayOf(-1, 0, 0), ShortArray(3)) }
        expectRawFailure { renderer.renderQ15(ShortArray(2), ShortArray(2)) }
        expectRawFailure { renderer.renderQ15(ShortArray(3), ShortArray(6)) }
        expectRawFailure { RawHsvFilmRenderer(FloatArray(1), f.post) }
        expectRawFailure { RawHsvFilmRenderer(f.grid, FloatArray(1)) }
        expectRawFailure { RawHsvFilmRenderer(f.grid.copyOf().also { it[0] = Float.NaN }, f.post) }
        expectRawFailure { RawHsvFilmRenderer(f.grid.copyOf().also { it[1] = -1f }, f.post) }
        expectRawFailure { RawHsvFilmRenderer(f.grid, f.post.copyOf().also { it[100] = -1f }) }
        renderer.renderQ15(ShortArray(0), ShortArray(0))

        // The three-curve RAW branch applies each table to its reconstructed RGB
        // lane independently, rather than remapping only the shared extrema.
        val channelCurves = Array(3) { channel ->
            FloatArray(32769) { index ->
                (index * if (channel == 1) .8f else 1f).coerceAtMost(32767f)
            }
        }
        val three = RawHsvFilmRenderer(f.grid, f.post, channelPostCurves = channelCurves)
        val threeOutput = ShortArray(f.sensor.size)
        three.renderQ15(f.sensor, threeOutput)
        check(threeOutput.contentEquals(f.sensor).not()) { "Three-curve RAW path was not applied" }
        expectRawFailure { RawHsvFilmRenderer(f.grid, f.post, channelPostCurves = arrayOf(f.post)) }
        expectRawFailure {
            RawHsvFilmRenderer(f.grid, f.post, channelPostCurves = Array(3) { FloatArray(32769) }
                .also { it[2][100] = Float.NaN })
        }
    }
    println("RAW HSV verification passed; captured Elite parameters are verification-only, arbitrary film baking is pending.")
}
