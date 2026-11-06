package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

private fun s9Number(json: String, key: String): Float =
    Regex("\"$key\"\\s*:\\s*([-+0-9.eE]+)").find(json)!!.groupValues[1].toFloat()

internal fun s9Matrix(json: String, key: String): FloatArray {
    val text = Regex("\"$key\"\\s*:\\s*(\\[\\s*\\[[\\s\\S]*?]\\s*])").find(json)!!.groupValues[1]
    return Regex("[-+]?\\d+(?:\\.\\d*)?(?:[eE][-+]?\\d+)?").findAll(text)
        .map { it.value.toFloat() }.toList().toFloatArray().also { require(it.size == 9) }
}

internal fun s9Profile(file: File): Pair<RawFidelityProfile, Float> {
    val json = file.readText()
    return RawFidelityProfile(s9Matrix(json, "fidelityTungsten"), s9Matrix(json, "fidelityDaylight"),
        s9Number(json, "tempLow"), s9Number(json, "tempHigh")) to s9Number(json, "exposureBiasEv")
}

fun main(args: Array<String>) {
    require(args.size == 4) { "Expected local-data, native fidelity TSV, native WB TSV, report" }
    val local = File(args[0])
    val json = File(local, "raw-profile/s9-fidelity.json").readText()
    val (camera, bias) = s9Profile(File(local, "raw-profile/s9-fidelity.json"))
    check(bias.toRawBits() == .55f.toRawBits())
    val fidelity = RawTargetFidelityProfile(s9Matrix(json, "fidelityTungsten"), s9Matrix(json, "fidelityDaylight"),
        s9Number(json, "tempLow"), s9Number(json, "tempHigh"))
    var fidelityCases = 0
    for (line in File(args[1]).readLines().drop(1).filter(String::isNotBlank)) {
        val row = line.split('\t'); require(row.size == 2)
        val actual = fidelity.matrixAt(row[0].toFloat())
        val expected = row[1].split(',').map(String::toFloat)
        check(actual.indices.all { actual[it].toRawBits() == expected[it].toRawBits() })
        fidelityCases++
    }
    check(fidelityCases == 24)
    val buffer = ByteBuffer.wrap(File(local, "raw-wb/s9-grid.cxowb").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    check(ByteArray(8).also(buffer::get).contentEquals("CXOWBG01".toByteArray()))
    val nt = buffer.int; val ni = buffer.int; val ns = buffer.int
    check(nt == 10 && ni == 5 && ns == nt * ni * 3)
    val whiteBalance = RawWhiteBalance(FloatArray(nt) { buffer.float }, FloatArray(ni) { buffer.float }, FloatArray(ns) { buffer.float })
    check(!buffer.hasRemaining())
    var wbCases = 0; var maxTintError = 0f
    for (line in File(args[2]).readLines().drop(1).filter(String::isNotBlank)) {
        val row = line.split('\t'); require(row.size == 3)
        val scales = row[0].split(',').map(String::toFloat).toFloatArray()
        val actual = whiteBalance.estimate(scales)
        check(actual.temperatureKelvin == row[1].toFloat()) { "S9 WB temperature: $actual / $line" }
        val error = abs(actual.tint - row[2].toFloat())
        check(error <= .001f) { "S9 WB tint: $actual / $line" }
        maxTintError = maxOf(maxTintError, error)
        wbCases++
    }
    check(wbCases == 57)
    // Original default RAW interfaces carry quantized feature5, not the camera
    // body's similarly named auxiliary calibration matrix.
    val expectedExtra = floatArrayOf(.830657958984375f, .1269683837890625f, .0423736572265625f,
        .096527099609375f, .8910980224609375f, .0123748779296875f,
        .0219879150390625f, .03271484375f, .9452972412109375f)
    check(camera.engineExtraMatrix().contentEquals(expectedExtra))
    val report = File(args[3]); report.parentFile.mkdirs()
    report.writeText("{\"success\":true,\"fidelity_native_cases\":$fidelityCases,\"wb_inverse_native_cases\":$wbCases," +
        "\"wb_temperature_exact\":true,\"max_wb_tint_error\":$maxTintError,\"camera_bias_ev\":$bias,\"engine_extra_exact\":true}\n")
    println("S9 profile: $fidelityCases fidelity, $wbCases WB inverse; max tint error $maxTintError; bias $bias")
}
