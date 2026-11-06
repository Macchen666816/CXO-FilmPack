package dev.cxo.filmpack.core

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

private fun contextBytes(file: File): ByteBuffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
private fun contextFloats(file: File): FloatArray {
    val input = contextBytes(file)
    check(input.remaining() % 4 == 0)
    return FloatArray(input.remaining() / 4) { input.float }
}
private fun contextShorts(file: File): ShortArray {
    val input = contextBytes(file)
    check(input.remaining() >= 4913 * 3 * 2)
    return ShortArray(4913 * 3) { input.short }
}
private fun contextMetric(label: String, actual: ShortArray, expected: ShortArray, limit: Int): Int {
    check(actual.size == expected.size)
    val peak = actual.indices.maxOf { abs(actual[it].toInt() - expected[it]) }
    val rmse = sqrt(actual.indices.sumOf { val d = actual[it].toInt() - expected[it]; d.toDouble() * d } / actual.size) * 255 / 32767
    println("$label maxQ15=$peak rmse255=$rmse")
    check(peak <= limit) { "$label exceeded $limit Q15: $peak" }
    return peak
}
private fun rejectContext(label: String, bytes: ByteArray) {
    val result = runCatching { RawRgbContextIO.read(ByteArrayInputStream(bytes)) }
    check(result.exceptionOrNull() is IllegalArgumentException) { "$label must be rejected as invalid data" }
}

/** Product parameter assets -> independent RGB bake -> real native 17³ oracle. */
fun main(args: Array<String>) {
    require(args.size == 2) { "Expected raw-rgb-contexts directory and native capture directory" }
    val assets = File(args[0]); val native = File(args[1])
    check(assets.listFiles { file -> file.extension == "cxorrgb" }!!.size == 6)
    var contexts = 0; var fullMaximum = 0; var renderMaximum = 0
    for (slug in listOf("eir", "hie", "tealandpink")) {
        for (photo in listOf("p1040758", "p1176257")) {
            val suffix = if (photo == "p1176257") "-landscape" else ""
            val asset = File(assets, "s1rii-$slug$suffix.cxorrgb")
            val context = asset.inputStream().use(RawRgbContextIO::read)
            val parameters = context.bakeParameters()
            val prefix = "small-grid-s1rii-$photo-$slug-raw-grid17-v1"
            fun file(suffix: String) = File(native, prefix + suffix)
            val iface = contextBytes(file(".prepared.actual-interface.bin"))
            val rendering = contextBytes(file(".prepared.rendering-data.bin"))
            val curveCount = if (rendering.getInt(0x78) == 0) 1 else 3
            check(context.curveCount == curveCount && curveCount == if (slug == "tealandpink") 3 else 1)
            check(context.runtimeBw == (iface.get(0x1659).toInt() == 1))
            check(context.runtimeBw == (slug == "hie"))
            check(context.ev.toRawBits() == iface.getFloat(0x1714).toRawBits())
            check(context.renderingEv.toRawBits() == rendering.getFloat(0x28).toRawBits())
            check(asset.length() == RawRgbContextIO.HEADER_BYTES.toLong() + curveCount * RawRgbContextIO.CURVE_SAMPLES * 2L)
            fun capturedMatrix(buffer: ByteBuffer, offset: Int) = FloatArray(9) { buffer.getFloat(offset + it * 4) }
            check(parameters.preMatrix.contentEquals(capturedMatrix(rendering, 4)))
            check(parameters.postMatrix.contentEquals(capturedMatrix(rendering, 0x80)))
            check(parameters.extraMatrix.contentEquals(capturedMatrix(iface, 0x15c8)))
            for (channel in 0 until curveCount) {
                val curve = contextFloats(file(".prepared.rendering-curve-${48 + 24 * channel}.f32le.bin"))
                check(parameters.filmCurves[channel].contentEquals(curve)) { "$prefix changed native curve bits" }
            }
            // Context ownership must survive mutation of a caller's parameter copy.
            val changed = context.bakeParameters()
            changed.preMatrix.fill(Float.NaN); changed.postMatrix.fill(Float.NaN)
            changed.extraMatrix.fill(Float.NaN); changed.filmCurves.forEach { it.fill(Float.NaN) }
            val independent = context.bakeParameters()
            check(independent.preMatrix.contentEquals(parameters.preMatrix))
            check(independent.postMatrix.contentEquals(parameters.postMatrix))
            check(independent.extraMatrix.contentEquals(parameters.extraMatrix))
            check(independent.filmCurves.indices.all { independent.filmCurves[it].contentEquals(parameters.filmCurves[it]) })
            val grid = RawRgbGridBaker.bake(independent)
            val nativeGrid = contextFloats(file(".prepared.interface-storage-grid.bin"))
            val gridPeak = grid.indices.maxOf { abs(grid[it] - nativeGrid[it]) }
            check(gridPeak <= 1E-4f) { "$prefix asset-built grid changed: $gridPeak" }
            val renderer = RawRgbFilmRenderer(grid, context.runtimeBw)
            val sensor = contextShorts(file(".grid-sensor-output.q15.bin"))
            val expected = contextShorts(file(".grid-full-output.q15.bin"))
            val rendered = ShortArray(sensor.size)
            renderer.renderQ15(sensor, rendered)
            renderMaximum = maxOf(renderMaximum, contextMetric("$prefix asset render", rendered, expected, 2))
            val source = contextShorts(file(".grid-input.q15.bin"))
            val working = ShortArray(source.size)
            RawSensorToWorking(capturedMatrix(iface, 0x17b0), context.ev, iface.getFloat(0x1710)).renderQ15(source, working)
            renderer.renderQ15(working, working)
            fullMaximum = maxOf(fullMaximum, contextMetric("$prefix asset full", working, expected, 7))
            if (context.runtimeBw) check((working.indices step 3).all { working[it] == working[it + 1] && working[it] == working[it + 2] })
            contexts++
        }
    }
    val bytes = File(assets, "s1rii-eir.cxorrgb").readBytes()
    fun changedInt(offset: Int, value: Int) = bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value) }
    rejectContext("magic", bytes.copyOf().also { it[0] = 0 })
    rejectContext("version", changedInt(8, 2))
    rejectContext("unknown flags", changedInt(12, 2))
    rejectContext("negative flags", changedInt(12, -1))
    rejectContext("zero curves", changedInt(16, 0))
    rejectContext("huge curves", changedInt(16, Int.MAX_VALUE))
    rejectContext("invalid samples", changedInt(20, 32768))
    rejectContext("huge samples", changedInt(20, Int.MAX_VALUE))
    rejectContext("encoding", changedInt(24, 2))
    rejectContext("nonfinite EV", changedInt(28, Float.NaN.toRawBits()))
    rejectContext("nonfinite rendering EV", changedInt(32, Float.POSITIVE_INFINITY.toRawBits()))
    rejectContext("nonfinite matrix", changedInt(36, Float.NaN.toRawBits()))
    rejectContext("signed-negative curve", bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(144, (-1).toShort()) })
    rejectContext("missing guard", bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(it.size - 2, 0) })
    rejectContext("trailing data", bytes + byteArrayOf(0))
    for (length in listOf(0, 7, 8, 12, 20, 28, 143, 144, bytes.size - 1)) rejectContext("truncated $length", bytes.copyOf(length))
    val stream = object : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
    RawRgbContextIO.read(stream)
    check(!stream.closed) { "Context reader must not close caller's stream" }
    check(contexts == 6)
    println("RAW RGB context assets verified: contexts=$contexts renderMaxQ15=$renderMaximum fullMaxQ15=$fullMaximum reader-boundaries=passed")
}
