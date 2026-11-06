package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Original standalone native Apply with film LUTs absent, plus its known final
 * rational/sqrt-hue tone fallback. The fallback belongs to this fixture only.
 * Actual product processing keeps the existing selected film post curves.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "Expected native evidence directory, oracle TSV, output report" }
    val native = File(args[0]); val index = File(args[1]); val report = File(args[2])
    fun shorts(file: File): ShortArray {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(b.remaining() % 6 == 0)
        return ShortArray(b.remaining() / 2) { b.short }.also { require(it.all { v -> v >= 0 }) }
    }
    fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val source = shorts(File(native, "negative-stage-oracle-input.q15.bin"))
    val linear = FloatArray(source.size) { source[it] * (1f / 32767f) }
    val rows = index.readLines()
    require(rows.first() == "token\tphoto\tintent\tmatrix\toutput\tsha256")
    var worst = 0
    val results = rows.drop(1).filter(String::isNotBlank).map { row ->
        val f = row.split('\t'); require(f.size == 6)
        val expectedFile = File(native, f[4]); require(sha(expectedFile) == f[5])
        val expected = shorts(expectedFile); require(expected.size == source.size)
        val stage = RawScaleFilterStage(f[3].split(',').map(String::toFloat).toFloatArray(), f[2].toFloat())
        val output = FloatArray(source.size); stage.renderLinear(linear, output)
        val inPlace = linear.copyOf(); stage.renderLinear(inPlace, inPlace); check(inPlace.contentEquals(output))
        val q15 = ShortArray(source.size); stage.renderQ15(source, q15)
        val q15InPlace = source.copyOf(); stage.renderQ15(q15InPlace, q15InPlace); check(q15InPlace.contentEquals(q15))
        var peak = 0; var different = 0
        for (i in output.indices step 3) {
            val lo = minOf(output[i], output[i + 1], output[i + 2])
            val hi = maxOf(output[i], output[i + 1], output[i + 2])
            val flo = (lo * 11f) / (1f + lo * 10f)
            val fhi = (hi * 11f) / (1f + hi * 10f)
            for (c in 0..2) {
                val v = flo + (fhi - flo) * ((sqrt(output[i + c]) - sqrt(lo)) /
                    max(sqrt(hi) - sqrt(lo), 1.1920928955078125e-7f))
                // Native fcvtzs truncates its final nonnegative Q15 carrier.
                val code = (v * 32767f).toInt().coerceIn(0, 32767)
                val d = abs(code - expected[i + c].toInt())
                peak = max(peak, d); if (d != 0) different++
            }
        }
        check(peak <= 1) { "${f[0]} ${f[1]} differs from original stage by $peak Q15" }
        worst = max(worst, peak)
        "{\"token\":\"${f[0]}\",\"photo\":\"${f[1]}\",\"max_q15\":$peak,\"different_components\":$different}"
    }
    check(results.size == 42)
    report.parentFile.mkdirs()
    report.writeText("{\"success\":true,\"contexts\":${results.size},\"samples_per_context\":${source.size / 3},\"max_q15\":$worst,\"rows\":[${results.joinToString(",")}]}\n")
    println("Raw scales/filter: ${results.size} contexts, ${source.size / 3} samples each, max $worst Q15")
}
