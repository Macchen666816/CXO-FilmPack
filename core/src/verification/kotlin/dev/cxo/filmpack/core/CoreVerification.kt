package dev.cxo.filmpack.core

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private class Metrics {
    var sumSquares = 0.0
    var maximum = 0.0
    var count = 0
    fun add(predicted: Double, target: Double) {
        val e = (predicted - target) * 255
        sumSquares += e * e; maximum = max(maximum, abs(e)); count++
    }
    fun json() = "{\"rmse_255\":${sqrt(sumSquares / count)},\"max_abs_255\":$maximum}"
}

private fun readDouble(input: DataInputStream) =
    java.lang.Double.longBitsToDouble(java.lang.Long.reverseBytes(input.readLong()))

fun main(args: Array<String>) {
    require(args.size == 2) { "Expected LUT asset and golden fixture paths" }
    val asset = File(args[0]); val lut = asset.inputStream().use { FilmLutIO.read(it) }
    val input = DataInputStream(File(args[1]).inputStream())
    val magic = ByteArray(8); input.readFully(magic)
    check(magic.contentEquals("FPGOLD01".toByteArray(Charsets.US_ASCII)))
    val samples = Integer.reverseBytes(input.readInt()); check(samples == 4096)
    val packed = FloatArray(samples * 3)
    val records = Array(samples) { DoubleArray(21) { readDouble(input) } }
    check(input.read() == -1); input.close()
    val output = DoubleArray(3)
    val crossNative = Array(2) { Metrics() }; val crossSrgb = Array(2) { Metrics() }
    val nativeObserved = Array(2) { Metrics() }; val srgbObserved = Array(2) { Metrics() }
    for ((methodIndex, method) in Interpolation.entries.withIndex()) {
        // Golden layout stores tetra first and trilinear second.
        val expectedOffset = if (method == Interpolation.TETRAHEDRAL) 3 else 9
        for ((i, record) in records.withIndex()) {
            for (c in 0..2) packed[i * 3 + c] = record[c].toFloat()
            lut.sampleNative(record[0], record[1], record[2], output, interpolation = method)
            for (c in 0..2) {
                crossNative[methodIndex].add(output[c], record[expectedOffset + c])
                nativeObserved[methodIndex].add(output[c], record[15 + c])
            }
            lut.nativeToSrgb(output)
            for (c in 0..2) {
                crossSrgb[methodIndex].add(output[c], record[expectedOffset + 3 + c])
                srgbObserved[methodIndex].add(output[c], record[18 + c])
            }
        }
        check(crossNative[methodIndex].maximum < 0.005)
        check(crossSrgb[methodIndex].maximum < 0.005)
    }
    val packedOutput = FloatArray(packed.size)
    lut.renderRgb(packed, packedOutput)
    val packedVsPython = Metrics()
    for (i in records.indices) for (c in 0..2) packedVsPython.add(packedOutput[i * 3 + c].toDouble(), records[i][6 + c])
    check(packedVsPython.maximum < 0.01)
    val inplace = packed.copyOf(); lut.renderRgb(inplace, inplace)
    check(inplace.contentEquals(packedOutput))
    // Reuse a fixed buffer across full and partial tiles and ensure its unused
    // tail is untouched. This models an Android image's right/bottom edges.
    val tilePixels = 17
    val tileSource = FloatArray(tilePixels * 3)
    val tileOutput = FloatArray(tilePixels * 3)
    val tiled = FloatArray(packed.size)
    var firstPixel = 0
    while (firstPixel < samples) {
        val count = minOf(tilePixels, samples - firstPixel)
        packed.copyInto(tileSource, endIndex = firstPixel * 3 + count * 3, startIndex = firstPixel * 3)
        tileOutput.fill(-1f)
        lut.renderRgb(tileSource, tileOutput, count)
        tileOutput.copyInto(tiled, destinationOffset = firstPixel * 3, endIndex = count * 3)
        check(tileOutput.drop(count * 3).all { it == -1f })
        firstPixel += count
    }
    check(tiled.contentEquals(packedOutput))
    check(runCatching { lut.renderRgb(tileSource, tileOutput, tilePixels + 1) }.isFailure)
    for (v in arrayOf(doubleArrayOf(-0.01, 0.0, 0.0), doubleArrayOf(Double.NaN, 0.0, 0.0), doubleArrayOf(0.0, 1.01, 0.0))) {
        check(runCatching { lut.sampleNative(v[0],v[1],v[2],output) }.isFailure)
    }
    for (v in arrayOf(doubleArrayOf(0.0,0.0,0.0),doubleArrayOf(1.0,1.0,1.0),doubleArrayOf(1.0,0.0,1.0))) {
        lut.sampleNative(v[0],v[1],v[2],output)
        check(output.all { it.isFinite() && it in 0.0..1.0 })
    }
    val corrupt = asset.readBytes(); corrupt[0] = 0
    check(runCatching { FilmLutIO.read(ByteArrayInputStream(corrupt)) }.isFailure)
    check(runCatching { asset.inputStream().use { FilmLutIO.read(it, "0".repeat(64)) } }.isFailure)
    val reports = Interpolation.entries.mapIndexed { i, method ->
        "\"${method.name.lowercase()}\":{\"kotlin_vs_python_native\":${crossNative[i].json()}," +
            "\"kotlin_vs_python_srgb\":${crossSrgb[i].json()},\"native_vs_filmpack\":${nativeObserved[i].json()}," +
            "\"srgb_vs_filmpack\":${srgbObserved[i].json()}}"
    }.joinToString(",")
    println("{\"platform\":\"local JVM; Android device not tested\",\"samples\":$samples," +
        "\"dimension\":${lut.size},\"methods\":{$reports},\"packed_float_vs_python_srgb\":${packedVsPython.json()}," +
        "\"inplace_and_domain_and_asset_checks\":\"passed\"}")
}
