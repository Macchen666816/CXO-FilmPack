package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Standalone original native estimator and sampler oracles; no product captures are fitted. */
fun main(args: Array<String>) {
    require(args.size in 4..5) { "Expected native directory, intent TSV, subsample TSV, report, optional scene TSV" }
    val native = File(args[0])
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun shorts(bytes: ByteArray, offset: Int = 0): ShortArray {
        require((bytes.size - offset) % 2 == 0)
        val buffer = ByteBuffer.wrap(bytes, offset, bytes.size - offset).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(buffer.remaining() / 2) { buffer.short }
    }
    fun npy(bytes: ByteArray): ShortArray {
        require(bytes.take(6).toByteArray().contentEquals(byteArrayOf(0x93.toByte(), 78, 85, 77, 80, 89)))
        require(bytes[6].toInt() == 1) { "Expected numpy v1" }
        val length = (bytes[8].toInt() and 255) or ((bytes[9].toInt() and 255) shl 8)
        val header = String(bytes, 10, length, Charsets.US_ASCII)
        require(header.contains("'<i2'") && header.contains("'fortran_order': False"))
        return shorts(bytes, 10 + length)
    }
    fun matrix(value: String) = value.split(',').map(String::toFloat).toFloatArray()
    val cache = mutableMapOf<String, Pair<String, ShortArray>>()
    var cases = 0
    fun verifyIntents(file: File): Int {
        var count = 0
        for (line in file.readLines().drop(1).filter(String::isNotBlank)) {
            val f = line.split('\t'); require(f.size == 6)
            val (hash, source) = cache.getOrPut(f[0]) {
                val bytes = File(native, f[0]).readBytes(); sha(bytes) to shorts(bytes)
            }
            check(hash == f[5]) { "Input hash mismatch: ${f[0]}" }
            val expected = f[4].toFloat()
            val actual = RawColorIntentEstimator.estimateSampledQ15(source, matrix(f[1]), f[2].toFloat(), matrix(f[3]))
            check(actual.toBits() == expected.toBits()) { "Intent ${f[0]} / $count: $actual != $expected" }
            val nativePercent = (expected * 100f).toInt().toFloat() / 100f
            check(RawColorIntentEstimator.throughNativePercent(actual).toBits() == nativePercent.toBits())
            count++
        }
        return count
    }
    cases = verifyIntents(File(args[1])); check(cases == 660)
    var resizeCases = 0
    var sampledComponents = 0L
    for (line in File(args[2]).readLines().drop(1).filter(String::isNotBlank)) {
        val f = line.split('\t'); require(f.size == 8)
        val width = f[0].toInt(); val height = f[1].toInt(); val ow = f[2].toInt(); val oh = f[3].toInt()
        val inputBytes = File(native, f[4]).readBytes(); val outputBytes = File(native, f[5]).readBytes()
        check(sha(inputBytes) == f[6] && sha(outputBytes) == f[7])
        val input = npy(inputBytes); val expected = npy(outputBytes)
        val full = RawColorIntentEstimator.meanDownsampleQ15(input, width, height)
        var rowCalls = 0
        val streamed = RawColorIntentEstimator.meanDownsampleQ15(width, height, { y, row ->
            rowCalls++; input.copyInto(row, 0, y * width * 3, (y + 1) * width * 3)
        })
        check(full.width == ow && full.height == oh && streamed.width == ow && streamed.height == oh)
        check(full.pixels.contentEquals(expected)) { "Array sampler mismatch $width x $height" }
        check(streamed.pixels.contentEquals(expected)) { "Streaming sampler mismatch $width x $height" }
        check(rowCalls > 0)
        resizeCases++; sampledComponents += expected.size
    }
    check(resizeCases == 6)
    val scenes = if (args.size == 5) verifyIntents(File(args[4])) else 0
    var cancellationChecks = 0
    try {
        var reads = 0
        RawColorIntentEstimator.meanDownsampleQ15(640, 480, { _, row -> reads++; row.fill(100) }, { reads >= 3 })
        error("Streaming cancellation was ignored")
    } catch (_: CancellationException) { cancellationChecks++ }
    try {
        val id = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        RawColorIntentEstimator.estimateSampledQ15(ShortArray(3000) { 100 }, id, 0f, id, { true })
        error("Estimator cancellation was ignored")
    } catch (_: CancellationException) { cancellationChecks++ }
    val report = File(args[3]); report.parentFile.mkdirs()
    report.writeText("{\"success\":true,\"native_intent_cases\":$cases,\"exact_float_matches\":$cases,\"native_sampling_cases\":$resizeCases,\"sampling_implementations\":2,\"sampled_components_per_implementation\":$sampledComponents,\"max_sampling_q15\":0,\"scene_cases\":$scenes,\"cancellation_checks\":$cancellationChecks}\n")
    println("RAW intent: $cases native Float32 matches; $resizeCases samplers, array + streaming exact; $scenes scene cases; cancellation $cancellationChecks")
}
