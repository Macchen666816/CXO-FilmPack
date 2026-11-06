package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** Verifies matrix/TRC parsing against the independent Python analytical path. */
fun main(args: Array<String>) {
    require(args.size == 3) { "Expected AdobeRGB ICC, sRGB ICC and analytic golden" }
    val adobe = IccMatrixTrcProfile.read(File(args[0]).readBytes())
    val srgb = IccMatrixTrcProfile.read(File(args[1]).readBytes())
    val transform = AnalyticalIccQ15Transform(adobe, srgb)
    val input = DataInputStream(File(args[2]).inputStream().buffered())
    val magic = ByteArray(8); input.readFully(magic)
    check(magic.contentEquals("CXOICC03".toByteArray(Charsets.US_ASCII)))
    val n = Integer.reverseBytes(input.readInt()); require(n == 5174)
    val nativeN = Integer.reverseBytes(input.readInt()); require(nativeN == 53)
    var maxAnalytical = 0.0; var q15Unequal = 0
    var nativeSum = 0.0; var nativeMax = 0.0
    repeat(n) { row ->
        fun float() = Float.fromBits(Integer.reverseBytes(input.readInt())).toDouble()
        fun double() = Double.fromBits(java.lang.Long.reverseBytes(input.readLong()))
        val r = float(); val g = float(); val b = float()
        val expected = doubleArrayOf(double(), double(), double())
        val qExpected = ShortArray(3) { java.lang.Short.reverseBytes(input.readShort()) }
        val xyz = DoubleArray(3); adobe.toXyz(doubleArrayOf(r, g, b), xyz)
        val analytical = DoubleArray(3); srgb.fromXyz(xyz, analytical)
        repeat(3) { c ->
            val error = abs(analytical[c] - expected[c])
            maxAnalytical = maxOf(maxAnalytical, error)
            check(error < 1e-10) { "Matrix/TRC analytical mismatch at $row/$c: $error" }
        }
        val actual = transform.convert(floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat()))
        for (c in 0..2) {
            val expectedFloat = ColorManagementQ15.fromInt16(qExpected[c])
            if (actual[c].toRawBits() != expectedFloat.toRawBits()) q15Unequal++
            check(abs(actual[c] - expectedFloat) * 32767f <= 1.01f) { "Q15 oracle mismatch at $row/$c" }
        }
        if (row < nativeN) repeat(3) { c ->
            val observed = float()
            val difference = (actual[c] - observed) * 32767.0
            nativeSum += difference * difference
            nativeMax = maxOf(nativeMax, abs(difference))
        }
    }
    check(input.read() == -1)
    println("{\"icc_profiles\":2,\"samples\":$n,\"analytical_max_abs\":$maxAnalytical," +
        "\"q15_unequal_float_components\":$q15Unequal," +
        "\"native_probe_samples\":$nativeN,\"native_rmse_code\":${sqrt(nativeSum / (nativeN * 3))},\"native_max_code\":$nativeMax," +
        "\"matrix_trc_parser\":\"passed\",\"native_cmm_equivalence\":false}")
}
