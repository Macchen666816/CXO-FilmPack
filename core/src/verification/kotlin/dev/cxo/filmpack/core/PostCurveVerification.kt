package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File

/** Independent Python static-reference goldens; no native FilmPack or Android execution. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Expected postcurve golden fixture" }
    val input = DataInputStream(File(args[0]).inputStream().buffered())
    fun intLE() = Integer.reverseBytes(input.readInt())
    fun shortLE() = java.lang.Short.reverseBytes(input.readShort())
    fun floats(n: Int) = FloatArray(n) { Float.fromBits(intLE()) }
    fun label(): String {
        val bytes = ByteArray(intLE().also { check(it in 1..1024) })
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
    var floatBitsCompared = 0L
    var shortSamplesCompared = 0L
    var inverseAccepted = 0
    var inverseRejected = 0
    fun bits(actual: FloatArray, expected: FloatArray, name: String) {
        check(actual.size == expected.size)
        for (i in actual.indices) check(actual[i].toRawBits() == expected[i].toRawBits()) {
            "$name at $i: ${actual[i]} vs ${expected[i]}"
        }
        floatBitsCompared += actual.size
    }
    fun shorts(actual: ShortArray, expected: ShortArray, name: String) {
        check(actual.contentEquals(expected)) { name }
        shortSamplesCompared += actual.size
    }
    fun rejects(block: () -> Unit) {
        check(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }
    val magic = ByteArray(8); input.readFully(magic)
    check(magic.contentEquals("FPPOST01".toByteArray(Charsets.US_ASCII)))
    val actualCount = intLE(); val syntheticCount = intLE(); val selectorCount = intLE()
    check(actualCount == 184 && syntheticCount >= 8 && selectorCount >= 263)
    repeat(actualCount + syntheticCount) {
        val name = label(); val n = intLE(); val inverseAllowed = intLE() == 1
        check(n in 32768..32770)
        val values = floats(n); val original = values.copyOf()
        val copied = floats(n)
        val quantized = ShortArray(32768) { shortLE() }
        val increasing = ShortArray(32768) { shortLE() }
        val tail = NativePostCurves.copyPenultimateToLast(values)
        bits(tail, copied, "$name tail")
        shorts(NativePostCurves.quantizeFirst32768HalfUp(tail), quantized, "$name quantize")
        val qCopy = quantized.copyOf()
        shorts(NativePostCurves.makeIncreasing(quantized), increasing, "$name increasing")
        check(quantized.contentEquals(qCopy))
        if (inverseAllowed) {
            val expected = floats(32769)
            bits(NativePostCurves.inverseComposedPostCurve(values), expected, "$name inverse")
            inverseAccepted++
        } else {
            rejects { NativePostCurves.inverseComposedPostCurve(values) }
            inverseRejected++
        }
        check(values.indices.all { i -> values[i].toRawBits() == original[i].toRawBits() })
        tail[0] = 123.75f
        check(values[0].toRawBits() == original[0].toRawBits())
    }
    repeat(selectorCount) {
        val nativeType = intLE(); val expected = intLE()
        check(NativeRenderingSelector.colorModelForRenderingType(nativeType).nativeValue == expected)
    }
    check(input.read() == -1); input.close()
    check(NativeRenderingSelector.colorModelForRenderingType(0x4ef) == NativeRenderingSelector.ColorModel.HSV)
    val raw = floatArrayOf(Float.fromBits(0x7fc01234), -0.0f, Float.POSITIVE_INFINITY)
    bits(NativePostCurves.copyPenultimateToLast(raw), floatArrayOf(raw[0], -0.0f, -0.0f), "tail raw bits")
    rejects { NativePostCurves.copyPenultimateToLast(floatArrayOf()) }
    rejects { NativePostCurves.copyPenultimateToLast(floatArrayOf(1f)) }
    rejects { NativePostCurves.quantizeFirst32768HalfUp(FloatArray(32767)) }
    rejects { NativePostCurves.inverseComposedPostCurve(FloatArray(32767)) }
    rejects { NativePostCurves.makeIncreasing(shortArrayOf()) }
    for (bad in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
        Float.MAX_VALUE, -Float.MAX_VALUE, 32767.5f, -32769.5f)) {
        rejects { NativePostCurves.quantizeFirst32768HalfUp(FloatArray(32768).also { it[7] = bad }) }
    }
    val bounds = FloatArray(32768).also { it[0] = -32769f; it[1] = 32767.499f }
    val bounded = NativePostCurves.quantizeFirst32768HalfUp(bounds)
    check(bounded[0] == Short.MIN_VALUE && bounded[1] == Short.MAX_VALUE)
    val ignoredGuard = FloatArray(32769).also { it[32768] = Float.NaN }
    check(NativePostCurves.quantizeFirst32768HalfUp(ignoredGuard).all { it == 0.toShort() })
    val zeroInverse = NativePostCurves.inverseComposedPostCurve(ignoredGuard)
    check(zeroInverse.size == 32769)
    shorts(NativePostCurves.makeIncreasing(shortArrayOf(-32768, -3, -10, 0, -1, 32767, 32766)),
        shortArrayOf(-32768, -3, -3, 0, 0, 32767, 32767), "signed prefix maximum")
    println("{\"actual_curves\":$actualCount,\"synthetic_curves\":$syntheticCount," +
        "\"inverse_inputs_accepted\":$inverseAccepted,\"inverse_inputs_rejected\":$inverseRejected," +
        "\"selector_inputs\":$selectorCount,\"float_raw_bits_compared\":$floatBitsCompared," +
        "\"short_samples_compared\":$shortSamplesCompared,\"boundary_checks\":true," +
        "\"ownership_checks\":true,\"native_execution\":false,\"android_execution\":false}")
}
