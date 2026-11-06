package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File

/** Native ARM64 boundary plus independent LCMS integer outputs; no Kotlin CMM claim. */
fun main(args: Array<String>) {
    check(ColorManagementQ15.toInt16(16383.5f) == 16383.toShort()) // generic truncation only
    check(ColorManagementQ15.toInt16(-16383.5f) == (-16383).toShort())
    check(ColorManagementQ15.quantizeNormalized(.5f) == 16384.toShort())
    check(ColorManagementQ15.quantizeNormalized(-.5f) == (-16384).toShort())
    check(ColorManagementQ15.quantizeNormalized(2f) == 32767.toShort())
    check(ColorManagementQ15.quantizeNormalized(-2f) == (-32768).toShort())
    check(runCatching { ColorManagementQ15.quantizeNormalized(Float.NaN) }.isFailure)
    check(runCatching { ColorManagementQ15.convert(floatArrayOf(0f, 1f)) { it } }.isFailure)
    val identity = ColorManagementQ15.convert(floatArrayOf(.5f, .2f, 1.2f)) { it }
    check(identity.contentEquals(floatArrayOf(16384f, 6553f, 32767f).map { it * Float.fromBits(0x38000100) }.toFloatArray()))

    require(args.size == 1)
    DataInputStream(File(args[0]).inputStream().buffered()).use { input ->
        val magic = ByteArray(8); input.readFully(magic)
        check(magic.contentEquals("CXOCOL01".toByteArray(Charsets.US_ASCII)))
        val n = Integer.reverseBytes(input.readInt()); check(n == 5516)
        fun float() = Float.fromBits(Integer.reverseBytes(input.readInt()))
        fun short() = java.lang.Short.reverseBytes(input.readShort())
        repeat(n) { row ->
            val rgb = FloatArray(3) { float() }
            val encoded = ShortArray(3) { short() }
            val transformed = ShortArray(3) { short() }
            val observed = FloatArray(3) { float() }
            val result = ColorManagementQ15.convert(rgb) { actual ->
                check(actual.contentEquals(encoded)) { "Input quantization mismatch at $row" }
                transformed
            }
            repeat(3) { c ->
                check(result[c].toRawBits() == observed[c].toRawBits()) { "Native float decode mismatch at $row/$c" }
            }
        }
        check(input.read() == -1)
        println("{\"color_management_q15\":\"passed\",\"native_boundary_samples\":$n,\"native_float_components\":${n*3},\"unequal\":0,\"kotlin_cmm_implemented\":false}")
    }
}
