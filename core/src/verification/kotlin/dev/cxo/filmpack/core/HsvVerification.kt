package dev.cxo.filmpack.core

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Static model application/JVM verification; separate from original native export fidelity. */
fun main(args: Array<String>) {
    require(args.size in 2..3) { "Expected static HSV asset and Python golden; optional output Float32" }
    val asset = File(args[0]).readBytes()
    val model = HsvFilmLutIO.read(ByteArrayInputStream(asset))
    val input = DataInputStream(File(args[1]).inputStream().buffered())
    val magic = ByteArray(8); input.readFully(magic)
    check(magic.contentEquals("CXOHVG02".toByteArray(Charsets.US_ASCII)))
    val n = Integer.reverseBytes(input.readInt()); val nativeN = Integer.reverseBytes(input.readInt())
    val controlledN = Integer.reverseBytes(input.readInt())
    check(n in 10000..30000 && nativeN == 4096 && controlledN == 4096)
    fun float() = Float.fromBits(Integer.reverseBytes(input.readInt()))
    val source = FloatArray(n * 3); val expected = FloatArray(n * 3); val observed = FloatArray(n * 3)
    repeat(n) { i ->
        repeat(3) { source[i * 3 + it] = float() }
        repeat(3) { expected[i * 3 + it] = float() }
        repeat(3) { observed[i * 3 + it] = float() }
    }
    check(input.read() == -1); input.close()
    val observedColorCodes = HashSet<List<Int>>()
    repeat(nativeN) { i -> observedColorCodes.add(List(3) { c -> source[i * 3 + c].toRawBits() }) }
    val uniqueNativeInputs = observedColorCodes.size
    check(uniqueNativeInputs == 3840)
    val output = FloatArray(source.size)
    model.renderRgb(source, output)
    var maxError = 0.0; var nativeSq = 0.0; var nativeMax = 0.0; var unequal = 0
    var controlledSq = 0.0; var controlledMax = 0.0
    for (i in output.indices) {
        check(output[i].isFinite() && output[i] in 0f..1f)
        val error = abs(output[i].toDouble() - expected[i].toDouble())
        maxError = maxOf(maxError, error)
        if (output[i].toRawBits() != expected[i].toRawBits()) unequal++
        check(output[i].toRawBits() == expected[i].toRawBits()) { "Static HSV Float-bit mismatch at component$i: ${output[i]} vs ${expected[i]}" }
        if (i < nativeN * 3) {
            val nativeError = (output[i].toDouble() - observed[i].toDouble()) * 255.0
            nativeSq += nativeError * nativeError; nativeMax = maxOf(nativeMax, abs(nativeError))
        } else if (i < (nativeN + controlledN) * 3) {
            val controlledError = (output[i].toDouble() - observed[i].toDouble()) * 255.0
            controlledSq += controlledError * controlledError; controlledMax = maxOf(controlledMax, abs(controlledError))
        }
    }
    val inPlace = source.copyOf(); model.renderRgb(inPlace, inPlace)
    check(inPlace.contentEquals(output))
    val padded = FloatArray(5) { -7f }
    for (i in intArrayOf(0, n / 2, n - 1)) {
        model.sample(source[i * 3], source[i * 3 + 1], source[i * 3 + 2], padded, 1)
        check(padded[0] == -7f && padded[4] == -7f)
        repeat(3) { check(padded[it + 1].toRawBits() == output[i * 3 + it].toRawBits()) }
    }
    // Constructor ownership: copy all injected arrays before the caller mutates them.
    val raw = DataInputStream(ByteArrayInputStream(asset)); raw.skipBytes(28)
    fun rawFloats(count: Int) = FloatArray(count) { Float.fromBits(Integer.reverseBytes(raw.readInt())) }
    val grid = rawFloats(HsvFilmLut.GRID_FLOATS)
    val inverse = rawFloats(HsvFilmLut.CURVE_SAMPLES)
    val post = rawFloats(HsvFilmLut.CURVE_SAMPLES)
    val forward = rawFloats(HsvFilmLut.CURVE_SAMPLES)
    val owned = HsvFilmLut(grid, inverse, post, forward)
    grid.fill(Float.NaN); inverse.fill(Float.NaN); post.fill(Float.NaN); forward.fill(Float.NaN)
    val ownedResult = FloatArray(3); owned.sample(source[0], source[1], source[2], ownedResult)
    repeat(3) { check(ownedResult[it].toRawBits() == output[it].toRawBits()) }
    for (bad in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -.001f, 1.001f)) {
        val sentinel = FloatArray(5) { -7f }
        check(runCatching { model.sample(bad, .2f, .3f, sentinel, 1) }.isFailure)
        check(sentinel.all { it == -7f })
    }
    for (offset in intArrayOf(-1, 3, Int.MAX_VALUE)) {
        check(runCatching { model.sample(.1f, .2f, .3f, FloatArray(5), offset) }.isFailure)
    }
    val invalidSource = floatArrayOf(.1f, .2f, .3f, .4f, Float.NaN, .6f)
    val untouched = FloatArray(6) { -7f }
    check(runCatching { model.renderRgb(invalidSource, untouched) }.isFailure)
    check(untouched.all { it == -7f })
    check(runCatching { model.renderRgb(FloatArray(4), FloatArray(4)) }.isFailure)
    check(runCatching { model.renderRgb(FloatArray(3), FloatArray(6)) }.isFailure)
    check(runCatching { HsvFilmLut(grid, inverse, post, forward) }.isFailure)
    check(runCatching { HsvFilmLutIO.read(ByteArrayInputStream(asset + byteArrayOf(0))) }.isFailure)
    check(runCatching { HsvFilmLutIO.read(ByteArrayInputStream(asset.copyOf(asset.size - 1))) }.isFailure)
    val wrongDimensions = asset.copyOf(); wrongDimensions[8] = 16
    check(runCatching { HsvFilmLutIO.read(ByteArrayInputStream(wrongDimensions)) }.isFailure)
    if (args.size == 3) FileOutputStream(args[2]).use { out ->
        for (v in output) {
            val bits = v.toRawBits()
            repeat(4) { out.write(bits ushr (it * 8) and 255) }
        }
    }
    println("""{"samples":$n,"native_observed_patches":$nativeN,"native_unique_input_colors":$uniqueNativeInputs,"measured_boundary_proxy_samples":$controlledN,"control_reuses_same_observed_patches":true,"control_unique_input_colors":$uniqueNativeInputs,"max_python_abs":$maxError,"max_python_error_255":${maxError * 255},"unequal_float_components":$unequal,"components":${output.size},"native_rmse_255":${sqrt(nativeSq / (nativeN * 3))},"native_max_abs_255":$nativeMax,"controlled_rmse_255":${sqrt(controlledSq / (controlledN * 3))},"controlled_max_abs_255":$controlledMax,"ownership_inplace_domain_buffer_format_checks":"passed","boundary":"Kotlin JVM static-grid application only; no Kotlin bake, native target execution or Android"}""")
}
