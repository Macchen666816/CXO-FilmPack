package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** Verifies the per-file RGB16 -> Q15 boundary against retained camera samples. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Expected raw-normalization fixture" }
    DataInputStream(File(args[0]).inputStream().buffered()).use { input ->
        val magic = ByteArray(8).also(input::readFully)
        check(magic.contentEquals("CXONRM01".toByteArray(Charsets.US_ASCII)))
        val n = Integer.reverseBytes(input.readInt())
        val maximum = Integer.reverseBytes(input.readInt())
        val rawBps = Integer.reverseBytes(input.readInt())
        check(n in 1..100_000 && maximum in 1..65535 && rawBps in 1..16)
        val source = ShortArray(n * 3) { java.lang.Short.reverseBytes(input.readShort()) }
        val expected = ShortArray(n * 3) { java.lang.Short.reverseBytes(input.readShort()) }
        val nativeBoundary = ShortArray(n * 3) { java.lang.Short.reverseBytes(input.readShort()) }
        val actual = ShortArray(source.size)
        RawCameraNormalization.render(source, actual, maximum, rawBps)
        check(actual.contentEquals(expected)) { "formula mismatch at ${actual.indices.first { actual[it] != expected[it] }}" }
        val inPlace = source.copyOf()
        RawCameraNormalization.render(inPlace, inPlace, maximum, rawBps)
        check(inPlace.contentEquals(actual))
        val delta = actual.indices.map { actual[it].toInt() - nativeBoundary[it].toInt() }
        val rmse = sqrt(delta.sumOf { it.toDouble() * it } / delta.size)
        val maxAbs = delta.maxOf(::abs)
        val nonzero = delta.count { it != 0 }
        check(rmse * 255 / 32767 < .14 && maxAbs < 150) {
            "LibRaw camera boundary exceeds the measured upstream error budget"
        }
        check(input.read() == -1)
        for (bad in listOf(0, -1, 65536)) {
            check(runCatching { RawCameraNormalization.render(source, ShortArray(source.size), bad, rawBps) }.isFailure)
        }
        for (bad in listOf(0, 17)) {
            check(runCatching { RawCameraNormalization.render(source, ShortArray(source.size), maximum, bad) }.isFailure)
        }
        check(runCatching { RawCameraNormalization.render(ShortArray(2), ShortArray(2), maximum, rawBps) }.isFailure)
        val unsigned = shortArrayOf(0, 0x7fff.toShort(), 0xffff.toShort())
        val unsignedOut = RawCameraNormalization.render(unsigned, 65535, 16)
        check(unsignedOut.contentEquals(shortArrayOf(0, 16384, 32767)))
        println("{\"samples\":$n,\"maximumAfterProcess\":$maximum,\"rawBps\":$rawBps,"+
            "\"formula_equal\":true,\"native_boundary_rmse_q15\":$rmse,"+
            "\"native_boundary_max_abs_q15\":$maxAbs,\"native_boundary_nonzero\":$nonzero,"+
            "\"scope\":\"LibRaw output_color=0 linear-gamma RGB16 boundary; not film or display\"}")
    }
}
