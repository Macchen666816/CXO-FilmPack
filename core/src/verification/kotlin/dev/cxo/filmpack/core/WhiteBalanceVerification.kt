package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File

/** Native parser factors, independently of integer image scaling. */
fun main(args: Array<String>) {
    require(args.size == 5) { "Expected source-profile CMM resource, factor fixture, base scale catalog, boundary and direct-kernel fixtures" }
    verifyDirectWhiteBalanceKernel(File(args[4]))
    val catalog = File(args[2]).inputStream().use { FilmRgbBaseScaleCatalog.read(it) }
    check(catalog.presets.size == 147 && catalog.presets.count { !it.isIdentity } == 27)
    check(catalog.find("ColorModeArgentiqueKodakEIR")?.let {
        it.red.toRawBits() == 0x3f87ae14 && it.green == 1f && it.blue.toRawBits() == 0x3f8ccccd
    } == true)
    catalog.presets.forEach { check(catalog.findByRenderingType(it.renderingType) === it) }
    check(catalog.find("Unknown") == null && catalog.findByRenderingType(-1) == null)
    var nativeBoundarySamples = 0
    DataInputStream(File(args[3]).inputStream().buffered()).use { data ->
        fun int() = Integer.reverseBytes(data.readInt())
        fun short() = java.lang.Short.reverseBytes(data.readShort()).toInt()
        val magic = ByteArray(8).also { data.readFully(it) }
        check(magic.contentEquals("CXOWBI01".toByteArray(Charsets.US_ASCII)))
        val films = int(); check(films == 27)
        repeat(films) {
            val length = int(); check(length in 1..160)
            val bytes = ByteArray(length).also { data.readFully(it) }
            val id = bytes.toString(Charsets.US_ASCII)
            val base = requireNotNull(catalog.find(id))
            val stage = NativeWhiteBalanceRgb.create(base)
            val rows = int(); check(rows in 1..100000)
            val actual = IntArray(3)
            repeat(rows) { row ->
                val source = IntArray(3) { short() }
                val expected = IntArray(3) { short() }
                stage.applyQ15(source[0], source[1], source[2], actual)
                check(actual.contentEquals(expected)) { "Native integer WB mismatch $id row $row" }
                val carrier = IntArray(3)
                stage.applySrgb16Carrier(source[0] shl 1, source[1] shl 1, source[2] shl 1, carrier)
                check(carrier.indices.all { carrier[it] ushr 1 == expected[it] })
                nativeBoundarySamples++
            }
            val coefficientCopy = stage.integerCoefficients().also { it.fill(-1) }
            check(!coefficientCopy.contentEquals(stage.integerCoefficients()))
        }
        check(data.read() == -1)
    }
    val transform = LcmsOptimizedQ15Transform.read(File(args[0]).readBytes())
    DataInputStream(File(args[1]).inputStream().buffered()).use { data ->
        fun int() = Integer.reverseBytes(data.readInt())
        fun float() = Float.fromBits(int())
        val magic = ByteArray(8).also { data.readFully(it) }
        check(magic.contentEquals("CXOWBV01".toByteArray(Charsets.US_ASCII)))
        val colorRows = int(); val temperatureRows = int()
        check(colorRows == 5516 && temperatureRows == 270)
        repeat(colorRows) { row ->
            val input = FloatArray(3) { float() }
            val expected = FloatArray(3) { float() }
            val actual = WhiteBalanceRgb.colorFactors(input) { transform.convert(it) }
            repeat(3) { channel ->
                check(actual[channel].toRawBits() == expected[channel].toRawBits()) {
                    "Native color factor mismatch row $row channel $channel: ${actual[channel]} != ${expected[channel]}"
                }
            }
        }
        repeat(temperatureRows) { row ->
            val temperature = float()
            val expected = FloatArray(3) { float() }
            val actual = WhiteBalanceRgb.temperatureFactors(temperature)
            repeat(3) { channel ->
                check(actual[channel].toRawBits() == expected[channel].toRawBits()) {
                    "Native temperature factor mismatch row $row channel $channel"
                }
            }
        }
        check(data.read() == -1)
        println("{\"native_color_factor_rows\":$colorRows,\"native_temperature_factor_rows\":$temperatureRows," +
            "\"unequal_float_components\":0,\"native_factor_components\":${(colorRows + temperatureRows) * 3}," +
            "\"base_scale_presets\":147,\"nonidentity_base_scale_presets\":27,\"native_integer_boundary_samples\":$nativeBoundarySamples,\"integer_image_scaling_verified\":true,\"android_execution\":false}")
    }
}
