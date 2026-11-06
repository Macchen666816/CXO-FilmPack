package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

private fun expectWbFailure(block: () -> Unit) {
    try { block() } catch (_: IllegalArgumentException) { return }
    error("Expected WB argument rejection")
}

fun main(args: Array<String>) {
    val folder = File(args.single())
    val b = ByteBuffer.wrap(File(folder, "s1rii-grid.golden").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    check(ByteArray(8).also { b.get(it) }.toString(Charsets.US_ASCII) == "CXOWBG01")
    val nt = b.int; val ni = b.int; val ns = b.int
    check(nt in 2..100 && ni in 2..100 && ns == nt * ni * 3)
    val temperatures = FloatArray(nt) { b.float }
    val tints = FloatArray(ni) { b.float }
    val scales = FloatArray(ns) { b.float }
    check(!b.hasRemaining())
    val grid = RawWhiteBalance(temperatures, tints, scales)
    val json = File(folder, "as-shot.json").readText()
    // Fixture shape is deliberately narrow: no production JSON dependency for verification.
    val rowPattern = Regex("\\{\\s*\"name\":\\s*\"([^\"]+)\",\\s*\"cam_mul\":\\s*\\[([^]]+)]\\s*,\\s*\"temperature\":\\s*([0-9.]+),\\s*\"tint\":\\s*([-0-9.Ee+]+)")
    var count = 0
    for (match in rowPattern.findAll(json)) {
        val mul = match.groupValues[2].split(',').map { it.trim().toFloat() }.toFloatArray()
        val result = grid.estimate(mul)
        val expectedTemperature = match.groupValues[3].toFloat()
        val expectedTint = match.groupValues[4].toFloat()
        println("${match.groupValues[1]} temperature=${result.temperatureKelvin} tint=${result.tint} tintError=${abs(result.tint - expectedTint)} scaleErrorL1=${result.scaleErrorL1}")
        check(result.temperatureKelvin == expectedTemperature)
        check(abs(result.tint - expectedTint) <= .001f)
        check(grid.estimate(mul.map { it * 2f }.toFloatArray()) == result)
        count++
    }
    check(count == 2)
    for ((temperatureIndex,tintIndex) in listOf(0 to 0, temperatures.lastIndex to tints.lastIndex, 4 to 2)) {
        val base = (temperatureIndex * ni + tintIndex) * 3
        val result = grid.estimate(scales.copyOfRange(base, base + 3))
        check(abs(result.temperatureKelvin - temperatures[temperatureIndex]) <= 1f)
        check(abs(result.tint - tints[tintIndex]) <= .001f)
        println("resourceNode temperature=${result.temperatureKelvin} tint=${result.tint} error=${result.scaleErrorL1}")
    }
    val unchanged = grid.estimate(floatArrayOf(502f, 256f, 544f))
    temperatures.fill(Float.NaN); tints.fill(Float.NaN); scales.fill(Float.NaN)
    check(grid.estimate(floatArrayOf(502f, 256f, 544f)) == unchanged)
    expectWbFailure { grid.estimate(floatArrayOf(0f, 1f, 1f)) }
    expectWbFailure { grid.estimate(floatArrayOf(Float.NaN, 1f, 1f)) }
    expectWbFailure { grid.estimate(floatArrayOf(1f, 1f)) }
    expectWbFailure { RawWhiteBalance(floatArrayOf(5000f, 2000f), floatArrayOf(-1f, 1f), FloatArray(12) { 1f }) }
    expectWbFailure { RawWhiteBalance(floatArrayOf(2000f, 5000f), floatArrayOf(-1f, 1f), FloatArray(12)) }
    println("RAW WB verification passed:2 independent metadata samples plus3 resource nodes; cross-camera generalization not asserted.")
}
