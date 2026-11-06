package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Both original optional intent outputs, including active/absent combinations.
 * Native full-scene fixture contexts independently record the activation flags.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "Expected native directory, intent-pair oracle TSV, report" }
    val native = File(args[0]); val index = File(args[1]); val report = File(args[2])
    val cache = mutableMapOf<String, Pair<String, ShortArray>>()
    var synthetic = 0; var scenes = 0; var renderingPresent = 0; var scalesPresent = 0
    var nonzeroRendering = 0
    val lines = index.readLines()
    check(lines.first() == "kind\tsource\tsensor\tev\tscales\trendering_active\tnative_rendering\tnative_scales\tinput_sha256")
    for ((lineNumber, line) in lines.drop(1).filter(String::isNotBlank).withIndex()) {
        val row = line.split('\t'); require(row.size == 9)
        val (sha, source) = cache.getOrPut(row[1]) {
            val bytes = File(native, row[1]).readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.remaining() % 6 == 0)
            hash to ShortArray(buffer.remaining() / 2) { buffer.short }
        }
        check(sha == row[8]) { "Input hash ${row[1]}" }
        val sensor = row[2].split(',').map(String::toFloat).toFloatArray()
        val ev = row[3].toFloat()
        val scales = row[4].takeIf { it != "-" }?.split(',')?.map(String::toFloat)?.toFloatArray()
        val active = when (row[5]) { "0" -> false; "1" -> true; else -> error("Invalid activation flag") }
        val expectedFirst = row[6].toFloat().takeIf { it != -999f }
        val expectedSecond = row[7].toFloat().takeIf { it != -999f }
        val actual = RawColorIntentEstimator.estimateIntentsSampledQ15(source, sensor, ev, scales, active)
        fun exact(actualValue: Float?, expectedValue: Float?, name: String) {
            check(actualValue?.toRawBits() == expectedValue?.toRawBits()) {
                "Row ${lineNumber + 2} $name ${row[1]}: $actualValue != $expectedValue"
            }
            if (actualValue != null) {
                val nativePercent = (actualValue * 100f).toInt().toFloat() / 100f
                check(RawColorIntentEstimator.throughNativePercent(actualValue).toRawBits() == nativePercent.toRawBits())
            }
        }
        exact(actual.rendering, expectedFirst, "rendering intent")
        exact(actual.scalesAndFilter, expectedSecond, "scales intent")
        if (expectedFirst != null) { renderingPresent++; if (expectedFirst > 0f) nonzeroRendering++ }
        if (expectedSecond != null) {
            scalesPresent++
            exact(RawColorIntentEstimator.estimateSampledQ15(source, sensor, ev, requireNotNull(scales)), expectedSecond, "legacy API")
        }
        if (row[0] == "scene") scenes++ else synthetic++
    }
    check(synthetic == 750 && scenes >= 9)
    var cancelled = false
    try {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        RawColorIntentEstimator.estimateIntentsSampledQ15(ShortArray(3000) { 100 }, identity, 0f,
            estimateRenderingIntent = true, isCancelled = { true })
    } catch (_: CancellationException) { cancelled = true }
    check(cancelled)
    report.parentFile.mkdirs()
    report.writeText("{\"success\":true,\"synthetic_cases\":$synthetic,\"scene_cases\":$scenes," +
        "\"rendering_optional_present\":$renderingPresent,\"rendering_optional_nonzero\":$nonzeroRendering," +
        "\"scales_optional_present\":$scalesPresent,\"exact_native_float_matches\":${synthetic + scenes}," +
        "\"legacy_scales_api_exact\":true,\"cancellation_check\":true}\n")
    println("RAW paired intents: $synthetic synthetic + $scenes scenes exactly match original, optional flags and legacy API verified")
}
