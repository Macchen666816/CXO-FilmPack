package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File

internal fun verifyDirectWhiteBalanceKernel(fixture: File) {
    var samples = 0
    var unequal = 0
    DataInputStream(fixture.inputStream().buffered()).use { input ->
        fun int() = Integer.reverseBytes(input.readInt())
        fun short() = java.lang.Short.reverseBytes(input.readShort())
        val magic = ByteArray(8).also { input.readFully(it) }
        check(magic.contentEquals("CXOWBI01".toByteArray(Charsets.US_ASCII)))
        val count = int(); check(count == 11)
        repeat(count) {
            val gains = FloatArray(3) { Float.fromBits(int()) }
            val commonShift = int()
            val coefficients = IntArray(3) { int() }
            val length = int()
            val source = ShortArray(length * 3) { short() }
            val expected = ShortArray(length * 3) { short() }
            val wb = NativeWhiteBalanceRgb.fromGains(gains[0], gains[1], gains[2])
            check(wb.commonShift == commonShift)
            check(wb.integerCoefficients().contentEquals(coefficients))
            val actual = ShortArray(source.size)
            wb.applyQ15(source, actual)
            val alias = source.copyOf()
            wb.applyQ15(alias, alias)
            for (i in actual.indices) {
                if (actual[i] != expected[i]) unequal++
                check(actual[i] == expected[i] && alias[i] == expected[i])
            }
            val pixel = IntArray(3)
            for (i in source.indices step 3) {
                wb.applyQ15(source[i].toInt(), source[i + 1].toInt(), source[i + 2].toInt(), pixel)
                check((0..2).all { c -> pixel[c] == expected[i + c].toInt() })
            }
            samples += length
        }
        check(input.read() == -1)
        println("{\"fixture_gain_rows\":$count,\"native_kernel_rgb_samples\":$samples,\"components\":${samples * 3},\"unequal_components\":$unequal,\"bulk_scalar_and_alias_equal\":true,\"pure_kotlin_execution\":true,\"android_execution\":false}")
    }
}
