package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File

/** Python-reference raw-bit checks, without native FilmPack or Android execution. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Expected Python-generated curve fixture" }
    val input = DataInputStream(File(args[0]).inputStream().buffered())
    fun intLE() = Integer.reverseBytes(input.readInt())
    fun shortLE() = java.lang.Short.reverseBytes(input.readShort())
    fun name(): String {
        val bytes = ByteArray(intLE().also { check(it in 1..1024) })
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
    var comparedFloatBits = 0L
    var comparedShorts = 0L
    var inverseInputs = 0
    var decreasingRejected = 0
    fun equalBits(actual: FloatArray, expected: FloatArray, label: String) {
        check(actual.size == expected.size)
        for (i in actual.indices) {
            check(actual[i].toRawBits() == expected[i].toRawBits()) {
                label + " at " + i + ": " + actual[i] + " vs " + expected[i]
            }
        }
        comparedFloatBits += actual.size
    }
    fun rejects(block: () -> Unit) {
        check(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }
    val magic = ByteArray(8); input.readFully(magic)
    check(magic.contentEquals("FPCRV001".toByteArray(Charsets.US_ASCII)))
    val actualCount = intLE(); val syntheticCount = intLE(); val compositions = intLE()
    check(actualCount == 184 && syntheticCount == 4 && compositions == 185)
    repeat(actualCount + syntheticCount) { index ->
        val label = name(); val n = intLE(); val increasing = intLE() == 1
        check(n == NativeCurves.SAMPLE_COUNT)
        val values = ShortArray(n) { shortLE() }
        val original = values.copyOf()
        val converted = FloatArray(n + 1) { Float.fromBits(intLE()) }
        equalBits(NativeCurves.convertLutToFloatAndExtend(values), converted, label + " convert")
        equalBits(NativeCurves.extendLut(converted.copyOf(n)), converted, label + " extend")
        check(NativeCurves.isLutIncreasing(values) == increasing)
        check(NativeCurves.isLutIncreasing(converted) == increasing)
        val guarded = values.copyOf(n + 1).also { it[n] = values.last() }
        if (increasing) {
            val inverse = FloatArray(n + 1) { Float.fromBits(intLE()) }
            equalBits(NativeCurves.inverseInt16LutAndExtend(values), inverse, label + " inverse")
            equalBits(NativeCurves.inverseInt16LutAndExtend(guarded), inverse, label + " guard inverse")
            inverseInputs += 2
            check(inverse[n].toRawBits() == inverse[n - 1].toRawBits())
            check(inverse[n].toRawBits() == inverse[n - 2].toRawBits())
        } else {
            rejects { NativeCurves.inverseInt16LutAndExtend(values) }
            rejects { NativeCurves.inverseInt16LutAndExtend(guarded) }
            if (index < actualCount) decreasingRejected++
        }
        check(values.contentEquals(original))
        check(guarded.copyOf(n).contentEquals(original))
    }
    repeat(compositions) {
        val label = name(); val n = intLE(); check(n in 1..32768)
        val first = ShortArray(n) { shortLE() }; val second = ShortArray(n) { shortLE() }
        val expected = ShortArray(n) { shortLE() }
        val firstCopy = first.copyOf(); val secondCopy = second.copyOf()
        check(NativeCurves.composeInt16(first, second).contentEquals(expected)) { label }
        check(first.contentEquals(firstCopy) && second.contentEquals(secondCopy))
        comparedShorts += n
    }
    check(input.read() == -1); input.close()
    check(decreasingRejected == 7)
    val signed = shortArrayOf(-32768, -1, 0, 32767)
    equalBits(NativeCurves.convertLutToFloatAndExtend(signed),
        floatArrayOf(-32768f, -1f, 0f, 32767f, 32767f), "signed storage")
    val special = floatArrayOf(Float.fromBits(0x7fc01234), -0.0f, Float.NEGATIVE_INFINITY)
    val specialCopy = special.copyOf()
    equalBits(NativeCurves.extendLut(special), floatArrayOf(special[0], special[1], special[2], special[2]), "raw float copy")
    check(special.indices.all { special[it].toRawBits() == specialCopy[it].toRawBits() })
    check(!NativeCurves.isLutIncreasing(floatArrayOf(0f, Float.NaN)))
    check(!NativeCurves.isLutIncreasing(floatArrayOf(Float.NaN, 0f)))
    check(NativeCurves.isLutIncreasing(floatArrayOf(Float.NaN))) // no adjacent pair
    check(NativeCurves.isLutIncreasing(shortArrayOf(1, 1, 2)))
    check(!NativeCurves.isLutIncreasing(shortArrayOf(1, 0)))
    rejects { NativeCurves.convertLutToFloatAndExtend(shortArrayOf()) }
    rejects { NativeCurves.extendLut(floatArrayOf()) }
    rejects { NativeCurves.isLutIncreasing(shortArrayOf()) }
    rejects { NativeCurves.isLutIncreasing(floatArrayOf()) }
    rejects { NativeCurves.composeInt16(shortArrayOf(0), shortArrayOf()) }
    rejects { NativeCurves.composeInt16(shortArrayOf(-1), shortArrayOf(0)) }
    rejects { NativeCurves.composeInt16(shortArrayOf(0), shortArrayOf(-1)) }
    rejects { NativeCurves.composeInt16(shortArrayOf(1), shortArrayOf(0)) }
    rejects { NativeCurves.composeInt16(shortArrayOf(0), shortArrayOf(1)) }
    check(NativeCurves.composeInt16(shortArrayOf(), shortArrayOf()).isEmpty())
    val identity = ShortArray(32768) { it.toShort() }
    for (badSize in intArrayOf(0, 1, 32767, 32770)) {
        rejects { NativeCurves.inverseInt16LutAndExtend(ShortArray(badSize)) }
    }
    rejects { NativeCurves.inverseInt16LutAndExtend(ShortArray(32768) { -1 }) }
    val badGuard = identity.copyOf(32769).also { it[32768] = 32766 }
    rejects { NativeCurves.inverseInt16LutAndExtend(badGuard) }
    val raisedGuard = ShortArray(32769).also { it[32768] = 1 }
    equalBits(NativeCurves.inverseInt16LutAndExtend(raisedGuard),
        NativeCurves.inverseInt16LutAndExtend(ShortArray(32768)), "guard ignored after validation")
    val result = "{\"actual_curves\":" + actualCount + ",\"synthetic_curves\":" + syntheticCount +
        ",\"inverse_inputs\":" + inverseInputs + ",\"decreasing_actual_rejected\":" + decreasingRejected +
        ",\"compositions\":" + compositions + ",\"float_raw_bits_compared\":" + comparedFloatBits +
        ",\"short_samples_compared\":" + comparedShorts +
        ",\"boundary_checks\":true,\"native_execution\":false,\"android_execution\":false}"
    println(result)
}
