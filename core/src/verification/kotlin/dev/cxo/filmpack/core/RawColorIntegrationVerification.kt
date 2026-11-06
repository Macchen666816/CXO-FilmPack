package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Camera carrier and actual metadata -> independent working output vs matching GUI.
 * Elite grid alone remains a captured parameter fixture; Original is independent.
 */
fun main(args: Array<String>) {
    val b = ByteBuffer.wrap(File(args.single()).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    check(ByteArray(8).also { b.get(it) }.toString(Charsets.US_ASCII) == "CXORI001")
    val count = b.int; val maximum = b.int; val bits = b.int
    val nt = b.int; val nv = b.int
    require(count == 512 && nt in 2..100 && nv in 2..100)
    val camMul = FloatArray(3) { b.float }
    val ev = b.float; val highlight = b.float; val low = b.float; val high = b.float
    val temperatures = FloatArray(nt) { b.float }; val tints = FloatArray(nv) { b.float }
    val scales = FloatArray(nt * nv * 3) { b.float }
    val tungsten = FloatArray(9) { b.float }; val daylight = FloatArray(9) { b.float }
    val source = ShortArray(count * 3) { b.short }
    val original = IntArray(count * 3) { b.short.toInt() and 65535 }
    val elite = IntArray(count * 3) { b.short.toInt() and 65535 }
    val grid = FloatArray(17 * 91 * 12 * 3) { b.float }; val post = FloatArray(32769) { b.float }
    check(!b.hasRemaining())
    val wb = RawWhiteBalance(temperatures, tints, scales).estimate(camMul)
    check(wb.temperatureKelvin == 4199f)
    val matrix = RawFidelityProfile(tungsten, daylight, low, high).sensorToWorking(wb.temperatureKelvin)
    val normalized = RawCameraNormalization.render(source, maximum, bits)
    val working = ShortArray(source.size)
    RawSensorToWorking(matrix, ev, highlight).renderQ15(normalized, working)
    val output = ShortArray(source.size)
    fun compare(name: String, reference: IntArray) {
        var squares = 0.0; var max = 0
        for (i in output.indices) {
            val code = Math.rint(output[i].toDouble() * 65535 / 32767).toInt()
            val d = code - reference[i]
            squares += d.toDouble() * d; max = maxOf(max, abs(d))
        }
        val rmse = sqrt(squares / output.size) * 255 / 65535
        println("$name metadata+LibRaw camera->working vs GUI: rmse255=$rmse maxQ16=$max samples=$count temperature=${wb.temperatureKelvin}")
        check(rmse < 1.5) { "RAW integration differs materially from matching GUI" }
    }
    RawOriginalTone.renderQ15(working, output); compare("Original", original)
    RawHsvFilmRenderer(grid, post).renderQ15(working, output); compare("Elite100 captured film grid", elite)
}
