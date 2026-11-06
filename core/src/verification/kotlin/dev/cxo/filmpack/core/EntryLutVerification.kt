package dev.cxo.filmpack.core

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Verifies fixed ICC entry application and entry -> injected static HSV on JVM. */
fun main(args: Array<String>) {
    require(args.size in 3..4) { "Expected entry asset, HSV asset, golden, optional raw output" }
    val asset = File(args[0]).readBytes()
    val entry = FixedSrgbEntryLutIO.read(ByteArrayInputStream(asset))
    val film = HsvFilmLutIO.read(File(args[1]).inputStream())
    val pipeline = FixedSrgbFilmPipeline(entry, film)
    val input = DataInputStream(File(args[2]).inputStream().buffered())
    val magic = ByteArray(8); input.readFully(magic)
    check(magic.contentEquals("CXOENG01".toByteArray(Charsets.US_ASCII)))
    val n = Integer.reverseBytes(input.readInt())
    val nativeN = Integer.reverseBytes(input.readInt())
    val nativeUniqueN = Integer.reverseBytes(input.readInt())
    check(n in 10000..100000 && nativeN == 4096 && nativeUniqueN == 3840)
    fun float() = Float.fromBits(Integer.reverseBytes(input.readInt()))
    val source = ShortArray(n * 3)
    val expectedEntry = FloatArray(n * 3); val expectedFilm = FloatArray(n * 3)
    val observedFilm = FloatArray(n * 3); val observedNoFilm = FloatArray(n * 3)
    repeat(n) { i ->
        repeat(3) { c ->
            val value = float(); check(value in 0f..65535f && value == value.toInt().toFloat())
            source[i * 3 + c] = value.toInt().toShort()
        }
        repeat(3) { expectedEntry[i * 3 + it] = float() }
        repeat(3) { expectedFilm[i * 3 + it] = float() }
        repeat(3) { observedFilm[i * 3 + it] = float() }
        repeat(3) { observedNoFilm[i * 3 + it] = float() }
    }
    check(input.read() == -1); input.close()
    val unique = HashSet<List<Int>>()
    repeat(nativeN) { i -> unique.add(List(3) { source[i * 3 + it].toInt() and 65535 }) }
    check(unique.size == nativeUniqueN)
    val entryOut = FloatArray(source.size); val filmOut = FloatArray(source.size)
    entry.renderRgb16(source, entryOut); pipeline.renderRgb16(source, filmOut)
    var unequalEntry = 0; var unequalFilm = 0
    var filmSq = 0.0; var filmMax = 0.0; var noFilmSq = 0.0; var noFilmMax = 0.0
    for (i in entryOut.indices) {
        check(entryOut[i].isFinite() && entryOut[i] in 0f..1f)
        check(filmOut[i].isFinite() && filmOut[i] in 0f..1f)
        if (entryOut[i].toRawBits() != expectedEntry[i].toRawBits()) unequalEntry++
        if (filmOut[i].toRawBits() != expectedFilm[i].toRawBits()) unequalFilm++
        check(entryOut[i].toRawBits() == expectedEntry[i].toRawBits()) {
            "Entry bit mismatch component$i: ${entryOut[i]} vs ${expectedEntry[i]}"
        }
        check(filmOut[i].toRawBits() == expectedFilm[i].toRawBits()) {
            "Pipeline bit mismatch component$i: ${filmOut[i]} vs ${expectedFilm[i]}"
        }
        if (i < nativeN * 3) {
            val fe = (filmOut[i].toDouble() - observedFilm[i].toDouble()) * 255.0
            filmSq += fe * fe; filmMax = maxOf(filmMax, abs(fe))
            val ne = (entryOut[i].toDouble() - observedNoFilm[i].toDouble()) * 255.0
            noFilmSq += ne * ne; noFilmMax = maxOf(noFilmMax, abs(ne))
        }
    }
    val padded = FloatArray(5) { -7f }
    for (i in intArrayOf(0, n / 2, n - 1)) {
        val r = source[i * 3].toInt() and 65535
        val g = source[i * 3 + 1].toInt() and 65535
        val b = source[i * 3 + 2].toInt() and 65535
        entry.sampleSrgb16(r, g, b, padded, 1)
        repeat(3) { check(padded[it + 1].toRawBits() == entryOut[i * 3 + it].toRawBits()) }
        pipeline.sampleSrgb16(r, g, b, padded, 1)
        repeat(3) { check(padded[it + 1].toRawBits() == filmOut[i * 3 + it].toRawBits()) }
        check(padded[0] == -7f && padded[4] == -7f)
    }
    for (bad in intArrayOf(-1, 65536, Int.MIN_VALUE, Int.MAX_VALUE)) {
        for (channel in 0..2) {
            val codes = intArrayOf(3, 32768, 65535); codes[channel] = bad
            val sentinel = FloatArray(5) { -7f }
            check(runCatching { entry.sampleSrgb16(codes[0], codes[1], codes[2], sentinel, 1) }.isFailure)
            check(sentinel.all { it == -7f })
            check(runCatching { pipeline.sampleSrgb16(codes[0], codes[1], codes[2], sentinel, 1) }.isFailure)
            check(sentinel.all { it == -7f })
        }
    }
    for (offset in intArrayOf(-1, 3, Int.MAX_VALUE)) {
        check(runCatching { entry.sampleSrgb16(0, 0, 0, FloatArray(5), offset) }.isFailure)
        check(runCatching { pipeline.sampleSrgb16(0, 0, 0, FloatArray(5), offset) }.isFailure)
    }
    check(runCatching { entry.renderRgb16(ShortArray(4), FloatArray(4)) }.isFailure)
    check(runCatching { pipeline.renderRgb16(ShortArray(3), FloatArray(6)) }.isFailure)

    // The optional white-balance prefix is a per-image uint16 stage. Identity
    // must preserve both the packed and single-sample pipeline exactly.
    val identityWb = WhiteBalanceRgb(1f, 1f, 1f)
    val identityFilm = FloatArray(source.size)
    pipeline.renderRgb16(source, identityFilm, identityWb)
    check(identityFilm.contentEquals(filmOut))
    val identityPixel = FloatArray(5) { -7f }
    pipeline.sampleSrgb16(source[0].toInt() and 65535, source[1].toInt() and 65535,
        source[2].toInt() and 65535, identityPixel, 1, identityWb)
    repeat(3) { check(identityPixel[it + 1].toRawBits() == filmOut[it].toRawBits()) }
    check(identityPixel[0] == -7f && identityPixel[4] == -7f)
    val wbOut = IntArray(3)
    WhiteBalanceRgb(2f, .5f, 0f).apply(40000, 3, 65535, wbOut)
    check(wbOut.contentEquals(intArrayOf(65535, 2, 0)))
    check(runCatching { WhiteBalanceRgb(Float.NaN, 1f, 1f) }.isFailure)
    check(runCatching { WhiteBalanceRgb(-1f, 1f, 1f) }.isFailure)
    val factors5200 = WhiteBalanceRgb.temperatureFactors(5200f)
    check(factors5200.contentEquals(floatArrayOf(1f, 1f, 1f)))
    val factors3200 = WhiteBalanceRgb.temperatureFactors(3200f)
    check(kotlin.math.abs(factors3200[0] - 0.80846786f) < 1e-6f)
    check(kotlin.math.abs(factors3200[1] - 1.14901245f) < 1e-6f)
    check(kotlin.math.abs(factors3200[2] - 1.79201531f) < 1e-6f)
    val temperatureWb = WhiteBalanceRgb.fromTemperature(3200f, 1.06f, 1f, 1.1f)
    check(kotlin.math.abs(temperatureWb.redGain - 0.8569759f) < 1e-5f)
    check(runCatching { WhiteBalanceRgb.temperatureFactors(1999f) }.isFailure)
    check(runCatching { WhiteBalanceRgb.temperatureFactors(10001f) }.isFailure)

    // WhiteBalanceRGBColor uses an explicit AdobeRGB -> target-space
    // converter. These fixtures mirror the native red/blue probe values and
    // check the explicitly experimental capped model for reproducibility.
    // A [0,2] gain clamp is NOT established as native ScaleImage behavior.
    val redTarget = floatArrayOf(1f, 0.00018311106f, 0.00018311106f)
    val redFactors = WhiteBalanceRgb.colorFactors(floatArrayOf(1f, 0f, 0f)) {
        redTarget.copyOf()
    }
    check(kotlin.math.abs(redFactors[0] - 0.299128f) < 2e-4f)
    check(redFactors[1] > 1000f && redFactors[2] > 1000f)
    val redColorWb = WhiteBalanceRgb.experimentalFromColorRatios(
        floatArrayOf(1f, 0f, 0f),
        { redTarget.copyOf() },
        1.06f, 1f, 1.1f,
    )
    check(kotlin.math.abs(redColorWb.redGain - 0.31694f) < 2e-4f)
    check(redColorWb.greenGain == 2f && redColorWb.blueGain == 2f)
    val blueTarget = floatArrayOf(0f, 0.00039674062f, 1f)
    val blueColorWb = WhiteBalanceRgb.experimentalFromColorRatios(
        floatArrayOf(0f, 0f, 1f),
        { blueTarget.copyOf() },
        1.06f, 1f, 1.1f,
    )
    check(kotlin.math.abs(blueColorWb.redGain - 1.06f) < 1e-6f && blueColorWb.greenGain == 2f)
    check(kotlin.math.abs(blueColorWb.blueGain - 0.12558f) < 2e-4f)
    check(runCatching {
        WhiteBalanceRgb.experimentalFromColorRatios(floatArrayOf(1f, 0f, 0f), { FloatArray(2) })
    }.isFailure)

    val raw = DataInputStream(ByteArrayInputStream(asset)); check(raw.skipBytes(104) == 104)
    val nodes = FloatArray(FixedSrgbEntryLut.GRID_FLOATS) { Float.fromBits(Integer.reverseBytes(raw.readInt())) }
    fun construct(values: FloatArray) = FixedSrgbEntryLut(values, entry.sourceIccSha256,
        entry.targetIccSha256, entry.generationLcmsVersion, entry.generationFlags, entry.renderingIntent)
    val owned = construct(nodes); nodes.fill(Float.NaN)
    val ownedOut = FloatArray(3)
    owned.sampleSrgb16(source[0].toInt() and 65535, source[1].toInt() and 65535,
        source[2].toInt() and 65535, ownedOut)
    repeat(3) { check(ownedOut[it].toRawBits() == entryOut[it].toRawBits()) }
    check(runCatching { construct(nodes) }.isFailure)
    nodes.fill(0f)
    // Constant half-integers isolate nearest-ties-even from LUT interpolation.
    for (i in nodes.indices step 4) { nodes[i] = .5f; nodes[i + 1] = 1.5f; nodes[i + 2] = 2.5f }
    construct(nodes).sampleSrgb16(0, 0, 0, ownedOut)
    check(ownedOut.contentEquals(floatArrayOf(0f, 2f / 32767f, 2f / 32767f)))
    nodes[3] = 1f; check(runCatching { construct(nodes) }.isFailure); nodes[3] = 0f
    nodes[0] = 32768f; check(runCatching { construct(nodes) }.isFailure)
    check(runCatching { construct(FloatArray(4)) }.isFailure)
    // Reader guards: magic, dimensions, sampling, version, flags, intent, both profiles.
    for (byteOffset in intArrayOf(0, 8, 24, 28, 32, 36, 40, 72)) {
        val invalid = asset.copyOf(); invalid[byteOffset] = (invalid[byteOffset].toInt() xor 1).toByte()
        if (byteOffset == 28) repeat(4) { invalid[28 + it] = 0 }
        check(runCatching { FixedSrgbEntryLutIO.read(ByteArrayInputStream(invalid)) }.isFailure)
    }
    check(runCatching { FixedSrgbEntryLutIO.read(ByteArrayInputStream(asset.copyOf(asset.size - 1))) }.isFailure)
    check(runCatching { FixedSrgbEntryLutIO.read(ByteArrayInputStream(asset + byteArrayOf(0))) }.isFailure)
    if (args.size == 4) FileOutputStream(args[3]).use { out ->
        repeat(n) { i ->
            for (array in arrayOf(entryOut, filmOut)) repeat(3) { c ->
                val bits = array[i * 3 + c].toRawBits()
                repeat(4) { out.write(bits ushr (it * 8) and 255) }
            }
        }
    }
    println("""{"samples":$n,"entry_components":${entryOut.size},"pipeline_components":${filmOut.size},"entry_unequal_float_components":$unequalEntry,"pipeline_unequal_float_components":$unequalFilm,"native_observed_patches":$nativeN,"native_unique_input_colors":$nativeUniqueN,"native_portra_rmse_255":${sqrt(filmSq / (nativeN * 3))},"native_portra_max_abs_255":$filmMax,"native_no_film_rmse_255":${sqrt(noFilmSq / (nativeN * 3))},"native_no_film_max_abs_255":$noFilmMax,"ownership_unsigned_buffer_rounding_format_checks":"passed","boundary":"JVM fixed ICC entry LUT and injected static HSV application; no generic CMM, native execution or Android"}""")
}
