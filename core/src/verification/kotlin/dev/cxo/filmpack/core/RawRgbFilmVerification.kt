package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

private data class RawRgbCapture(
    val label: String,
    val parameters: RawRgbBakeParameters,
    val runtimeBw: Boolean,
    val nativeGrid: FloatArray,
    val source: ShortArray,
    val sensor: ShortArray,
    val expected: ShortArray,
    val sensorMatrix: FloatArray,
    val highlight: Float,
)

private fun littleBytes(file: File): ByteBuffer =
    ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)

private fun floats(file: File): FloatArray {
    val input = littleBytes(file)
    require(input.remaining() % 4 == 0)
    return FloatArray(input.remaining() / 4) { input.float }
}

private fun shorts(file: File, count: Int): ShortArray {
    val input = littleBytes(file)
    require(input.remaining() >= count * 2)
    return ShortArray(count) { input.short }
}

private fun readCapture(prefix: String): RawRgbCapture {
    fun file(suffix: String) = File(prefix + suffix)
    val interfaceBytes = file(".prepared.actual-interface.bin").readBytes()
    val renderingBytes = file(".prepared.rendering-data.bin").readBytes()
    require(interfaceBytes.size >= 0x17d4 && renderingBytes.size >= 0xa4)
    val interfaceData = ByteBuffer.wrap(interfaceBytes).order(ByteOrder.LITTLE_ENDIAN)
    val rendering = ByteBuffer.wrap(renderingBytes).order(ByteOrder.LITTLE_ENDIAN)
    val kind = rendering.getInt(0x78)
    require(kind in 0..1)
    val curves = if (kind == 0) {
        arrayOf(floats(file(".prepared.rendering-curve-48.f32le.bin")))
    } else {
        arrayOf(48, 72, 96).map { offset ->
            floats(file(".prepared.rendering-curve-$offset.f32le.bin"))
        }.toTypedArray()
    }
    fun floatArray(bytes: ByteArray, offset: Int, count: Int): FloatArray {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        input.position(offset)
        return FloatArray(count) { input.float }
    }
    val parameters = RawRgbBakeParameters(
        ev = interfaceData.getFloat(0x1714),
        renderingEv = rendering.getFloat(0x28),
        preMatrix = floatArray(renderingBytes, 0x04, 9),
        postMatrix = floatArray(renderingBytes, 0x80, 9),
        extraMatrix = floatArray(interfaceBytes, 0x15c8, 9),
        filmCurves = curves,
    )
    val count = 17 * 17 * 17 * 3
    return RawRgbCapture(
        label = File(prefix).name,
        parameters = parameters,
        runtimeBw = interfaceBytes[0x1659].toInt() == 1,
        nativeGrid = floats(file(".prepared.interface-storage-grid.bin")),
        source = shorts(file(".grid-input.q15.bin"), count),
        sensor = shorts(file(".grid-sensor-output.q15.bin"), count),
        expected = shorts(file(".grid-full-output.q15.bin"), count),
        sensorMatrix = floatArray(interfaceBytes, 0x17b0, 9),
        highlight = interfaceData.getFloat(0x1710),
    )
}

private data class Metric(val rmse: Double, val maximum: Double, val equal: Int)

private fun metric(actual: FloatArray, expected: FloatArray): Metric {
    require(actual.size == expected.size)
    var squares = 0.0
    var maximum = 0.0
    var equal = 0
    for (index in actual.indices) {
        val difference = abs(actual[index].toDouble() - expected[index].toDouble())
        squares += difference * difference
        maximum = maxOf(maximum, difference)
        if (difference == 0.0) equal++
    }
    return Metric(sqrt(squares / actual.size), maximum, equal)
}

private fun metric(actual: ShortArray, expected: ShortArray): Metric {
    require(actual.size == expected.size)
    var squares = 0.0
    var maximum = 0
    var equal = 0
    for (index in actual.indices) {
        val difference = abs(actual[index].toInt() - expected[index].toInt())
        squares += difference.toDouble() * difference
        maximum = maxOf(maximum, difference)
        if (difference == 0) equal++
    }
    return Metric(sqrt(squares / actual.size), maximum.toDouble(), equal)
}

private fun guardFacesEqual(grid: FloatArray): Boolean {
    fun node(blue: Int, green: Int, red: Int, channel: Int) =
        grid[((blue * 65 + green) * 65 + red) * 4 + channel]
    for (a in 0 until 65) for (b in 0 until 65) for (channel in 0..3) {
        if (node(a, b, 64, channel).toRawBits() != node(a, b, 63, channel).toRawBits()) return false
        if (node(a, 64, b, channel).toRawBits() != node(a, 63, b, channel).toRawBits()) return false
        if (node(64, a, b, channel).toRawBits() != node(63, a, b, channel).toRawBits()) return false
    }
    return true
}

private fun reject(block: () -> Unit) {
    check(runCatching(block).isFailure) { "Expected invalid RAW RGB argument to be rejected" }
}

/** Six captured native regressions: EIR/HIE/TealAndPink on two S1RII photos. */
fun main(args: Array<String>) {
    require(args.size == 6) { "Expected six captured RAW RGB0 prefixes" }
    val captures = args.map(::readCapture)
    check(captures.count { it.parameters.filmCurves.size == 3 } == 2)
    for (capture in captures) {
        val pre = capture.parameters.preMatrix.copyOf()
        val post = capture.parameters.postMatrix.copyOf()
        val extra = capture.parameters.extraMatrix.copyOf()
        val curves = capture.parameters.filmCurves.map(FloatArray::copyOf)
        val baked = RawRgbGridBaker.bake(capture.parameters)
        check(baked.size == RawRgbGridBaker.GRID_FLOATS && baked.all(Float::isFinite))
        check(guardFacesEqual(baked))
        var alphaIndex = 3
        while (alphaIndex < baked.size) {
            check(baked[alphaIndex] == 0f)
            alphaIndex += 4
        }
        check(capture.parameters.preMatrix.contentEquals(pre))
        check(capture.parameters.postMatrix.contentEquals(post))
        check(capture.parameters.extraMatrix.contentEquals(extra))
        check(capture.parameters.filmCurves.indices.all { capture.parameters.filmCurves[it].contentEquals(curves[it]) })

        val gridMetric = metric(baked, capture.nativeGrid)
        check(gridMetric.maximum <= 1e-4) { "${capture.label} grid max=${gridMetric.maximum}" }

        val nativeRenderer = RawRgbFilmRenderer(capture.nativeGrid, capture.runtimeBw)
        val nativeRendered = ShortArray(capture.sensor.size)
        nativeRenderer.renderQ15(capture.sensor, nativeRendered)
        val nativeRenderMetric = metric(nativeRendered, capture.expected)
        check(nativeRenderMetric.maximum <= 1.0) { "${capture.label} native-grid render max=${nativeRenderMetric.maximum}" }

        val renderer = RawRgbFilmRenderer(baked, capture.runtimeBw)
        val rendered = ShortArray(capture.sensor.size)
        renderer.renderQ15(capture.sensor, rendered)
        val renderMetric = metric(rendered, capture.expected)
        check(renderMetric.maximum <= 2.0) { "${capture.label} rebuilt render max=${renderMetric.maximum}" }
        val inPlace = capture.sensor.copyOf()
        renderer.renderQ15(inPlace, inPlace)
        check(inPlace.contentEquals(rendered))

        val working = ShortArray(capture.source.size)
        RawSensorToWorking(capture.sensorMatrix, capture.parameters.ev, capture.highlight)
            .renderQ15(capture.source, working)
        val sensorMetric = metric(working, capture.sensor)
        renderer.renderQ15(working, working)
        val fullMetric = metric(working, capture.expected)
        check(sensorMetric.maximum <= 1.0)
        check(fullMetric.maximum <= 7.0) { "${capture.label} full max=${fullMetric.maximum}" }
        if (capture.runtimeBw) {
            check(working.indices.step(3).all { working[it] == working[it + 1] && working[it] == working[it + 2] })
        }

        val untouchedGrid = baked.copyOf()
        val copiedRenderer = RawRgbFilmRenderer(untouchedGrid, capture.runtimeBw)
        untouchedGrid.fill(Float.NaN)
        val copiedOutput = ShortArray(capture.sensor.size)
        copiedRenderer.renderQ15(capture.sensor, copiedOutput)
        check(copiedOutput.contentEquals(rendered))

        println(
            "{\"context\":\"${capture.label}\",\"curves\":${capture.parameters.filmCurves.size}," +
                "\"bw\":${capture.runtimeBw},\"grid_rmse\":${gridMetric.rmse}," +
                "\"grid_max\":${gridMetric.maximum},\"native_render_max_q15\":${nativeRenderMetric.maximum.toInt()}," +
                "\"rebuilt_render_max_q15\":${renderMetric.maximum.toInt()}," +
                "\"sensor_max_q15\":${sensorMetric.maximum.toInt()},\"full_max_q15\":${fullMetric.maximum.toInt()}}"
        )
    }

    val sample = captures.first()
    reject { RawRgbGridBaker.bake(sample.parameters.copy(filmCurves = arrayOf(FloatArray(1)))) }
    reject { RawRgbGridBaker.bake(sample.parameters.copy(preMatrix = FloatArray(1))) }
    reject { RawRgbFilmRenderer(FloatArray(1)) }
    reject { RawRgbFilmRenderer(sample.nativeGrid.copyOf().also { it[3] = 1f }) }
    reject { RawRgbFilmRenderer(sample.nativeGrid).renderQ15(shortArrayOf(-1, 0, 0), ShortArray(3)) }
    reject { RawRgbFilmRenderer(sample.nativeGrid).renderQ15(ShortArray(2), ShortArray(2)) }
    reject { RawRgbFilmRenderer(sample.nativeGrid).renderQ15(ShortArray(3), ShortArray(6)) }
    RawRgbFilmRenderer(sample.nativeGrid).renderQ15(ShortArray(0), ShortArray(0))
    println("RAW RGB0 verification passed: six native contexts, no app/catalog route")
}
