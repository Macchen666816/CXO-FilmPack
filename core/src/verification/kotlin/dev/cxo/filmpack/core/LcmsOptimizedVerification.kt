package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File

/** Stored DxO observations are validation only, never resource generation input. */
fun main(args: Array<String>) {
    require(args.isNotEmpty() && args.size % 2 == 0) { "Expected pairs of optimized CLUT and validation fixture" }
    for (pair in args.toList().chunked(2)) verifyPair(pair[0], pair[1])
}

private fun verifyPair(resource: String, fixture: String) {
    val transform = LcmsOptimizedQ15Transform.read(File(resource).readBytes())
    DataInputStream(File(fixture).inputStream().buffered()).use { data ->
        val magic = ByteArray(8); data.readFully(magic)
        check(magic.contentEquals("CXOLCMV1".toByteArray(Charsets.US_ASCII)))
        fun int() = Integer.reverseBytes(data.readInt())
        fun short() = java.lang.Short.reverseBytes(data.readShort())
        fun float() = Float.fromBits(int())
        val nativeRows = int(); check(nativeRows == 5516)
        val independentRows = int(); check(independentRows >= 100000)
        var nativeUnequal = 0; var q15Unequal = 0; var uint16Unequal = 0
        repeat(nativeRows) { row ->
            val input = FloatArray(3) { float() }
            val expected = FloatArray(3) { float() }
            val inputQ15 = ShortArray(3) { short() }
            val expectedQ15 = ShortArray(3) { short() }
            val actualQ15 = transform.transform(inputQ15)
            val actual = transform.convert(input)
            for (c in 0..2) {
                if (actualQ15[c] != expectedQ15[c]) q15Unequal++
                if (actual[c].toRawBits() != expected[c].toRawBits()) nativeUnequal++
            }
            check(actualQ15.contentEquals(expectedQ15)) { "Q15 mismatch row $row" }
            check(actual.indices.all { actual[it].toRawBits() == expected[it].toRawBits() }) { "Native Float mismatch row $row" }
        }
        repeat(independentRows) { row ->
            val input = IntArray(3) { short().toInt() and 65535 }
            val expected = IntArray(3) { short().toInt() and 65535 }
            val actual = transform.transformUnsigned16(input)
            repeat(3) { if (actual[it] != expected[it]) uint16Unequal++ }
            check(actual.contentEquals(expected)) { "Independent RGB16 mismatch row $row" }
        }
        check(data.read() == -1)
        println("{\"native_stored_samples\":$nativeRows,\"native_unequal_float_components\":$nativeUnequal," +
            "\"q15_unequal_components\":$q15Unequal,\"independent_lcms_rgb16_samples\":$independentRows," +
            "\"independent_lcms_unequal_components\":$uint16Unequal,\"native_pixels_used_for_model\":false," +
            "\"android_execution\":false,\"pure_kotlin_execution\":true}")
    }
}
