package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Native boundary pixels are expectations only. Model sources are original
 * PNGs/static film tables and the source-built ICC resources. */
fun verifyGrainPhotoBoundary(
    fixture: InputStream, catalog: NativeGrain8Catalog, grainLut: NativeGrain8ColorLut,
    entry: FixedSrgbEntryLut, openFilm: (String) -> InputStream,
    grainSource: (Int) -> ByteArray,
): List<String> = DataInputStream(fixture.buffered()).use { input ->
    fun bytes(n: Int) = ByteArray(n).also(input::readFully)
    fun i32() = Integer.reverseBytes(input.readInt())
    fun text(): String { val n = i32(); check(n in 1..256); return String(bytes(n), Charsets.UTF_8) }
    fun q15(count: Int): ShortArray = ShortArray(count) { java.lang.Short.reverseBytes(input.readShort()) }
    check(String(bytes(8), Charsets.US_ASCII) == "CXOGPH01")
    check(i32() == 1)
    val records = i32(); check(records == 5)
    val width = i32(); val height = i32(); check(width == 1024 && height == 1024)
    val source = q15(width * height * 3) // unsigned16 bits
    val metrics = mutableListOf<String>()
    repeat(records) {
        val token = text(); val model = text(); val asset = text()
        val intensity = i32(); val grainEnum = i32()
        val gains = FloatArray(3) { Float.fromBits(i32()) }
        val color = i32() != 0; val neutral = i32() != 0
        val expectedInput = q15(source.size); val expectedOutput = q15(source.size)
        val definition = requireNotNull(catalog[token]); check(definition.grainEnum == grainEnum)
        val geometry = NativeGrain8Geometry.resolve(grainEnum, NativeGrain8Geometry.Format.MANUAL, width, height, 1f)
        val prepared = definition.prepare(grainSource(grainEnum), geometry.scale, grainLut, 17)
        val kernel = NativeGrain8Patch(prepared.bytes, prepared.size, prepared.size, prepared.period,
            definition.amplitude(100), color, neutral)
        // Independently verify the node using native input, through normalized
        // bridge and offset tiles; zero code differences are required here.
        val normalized = FloatArray(source.size) { expectedInput[it].toFloat() / 32767f }
        kernel.renderNormalizedRgb(normalized, normalized, width)
        normalized.indices.forEach { i ->
            check(Math.rint((normalized[i] * 32767f).toDouble()).toInt() == expectedOutput[i].toInt()) { "Native normalized bridge $token differs at $i" }
        }
        val t = intensity / 100f
        val wb = NativeWhiteBalanceRgb.fromGains((1f-t)+t*gains[0], (1f-t)+t*gains[1], (1f-t)+t*gains[2])
        val film = openFilm(asset).use { if (model == "rgb") RgbFilmLutIO.read(it) else HsvFilmLutIO.read(it) }
        val actual = FloatArray(source.size)
        when (film) {
            is RgbFilmLut -> FixedSrgbRgbFilmPipeline(entry, film, wb, minOf(t,1f)).renderRgb16ExactSampling0(source, actual, width)
            is HsvFilmLut -> FixedSrgbFilmPipeline(entry, film, wb, minOf(t,1f)).renderRgb16ExactSampling0(source, actual, width)
        }
        fun error(expected: ShortArray): Pair<Double, Double> {
            var sum = 0.0; var maximum = 0.0
            for (i in actual.indices) { val e = abs(actual[i].toDouble()*32767 - expected[i].toInt()); sum += e*e; maximum = maxOf(maximum,e) }
            return sqrt(sum/actual.size) to maximum
        }
        val before = error(expectedInput)
        kernel.renderNormalizedRgb(actual, actual, width)
        val after = error(expectedOutput)
        val line = "{\"token\":\"$token\",\"intensity\":$intensity,\"pixels\":${width*height},\"native_bridge_exact\":true,\"before_rmse_q15\":${before.first},\"before_max_q15\":${before.second},\"after_rmse_q15\":${after.first},\"after_max_q15\":${after.second}}"
        println(line); metrics += line
        // Existing product tolerance: <=1 code255 maximum and <=0.1 RMSE.
        check(before.second <= 32767.0/255 && after.second <= 32767.0/255) { "Photo maximum exceeds one 8-bit code: $token" }
        check(before.first <= 32767.0/255*.1 && after.first <= 32767.0/255*.1) { "Photo RMS exceeds 0.1 8-bit code: $token" }
    }
    check(input.read() == -1)
    metrics
}
