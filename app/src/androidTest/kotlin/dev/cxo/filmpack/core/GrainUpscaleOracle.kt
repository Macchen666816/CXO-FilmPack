package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.InputStream

data class GrainUpscaleVerificationResult(val preparedComponents: Int, val nodePixels: Int)

/** A full original Manual1 Polachrome preparation and cached Q15 Grain-node output. */
fun verifyGrainUpscale(
    stream: InputStream,
    catalog: NativeGrain8Catalog,
    colorLut: NativeGrain8ColorLut,
    sourceRgb: ByteArray,
): GrainUpscaleVerificationResult = DataInputStream(stream.buffered()).use { input ->
    fun bytes(n: Int) = ByteArray(n).also(input::readFully)
    fun i32() = Integer.reverseBytes(input.readInt())
    fun shorts(n: Int): ShortArray {
        val raw = bytes(n * 2)
        return ShortArray(n) { i ->
            ((raw[i * 2].toInt() and 255) or (raw[i * 2 + 1].toInt() shl 8)).toShort()
        }
    }
    check(String(bytes(8), Charsets.US_ASCII) == "CXOGUP01" && i32() == 1)
    val grainEnum = i32(); val intensity = i32()
    val scale = Float.fromBits(i32()); val seed = i32().toLong() and 0xFFFFFFFFL
    val size = i32(); val period = i32(); val color = i32(); val neutral = i32()
    val width = i32(); val height = i32()
    check(grainEnum == 1023 && intensity == 100 && scale > 1f && seed == 0L)
    check(size == 1047 && period == 1043 && width == 1024 && height == 1024)
    check(color == 1 && neutral == 0)
    val preparedExpected = bytes(size * size * 3)
    val amplitudeExpected = IntArray(256) { i32() }
    val pixels = shorts(width * height * 3); val before = pixels.copyOf()
    val expected = shorts(pixels.size)
    check(input.read() == -1)
    val definition = checkNotNull(catalog["PolaroidPolachrome"])
    check(definition.grainEnum == grainEnum)
    val sourceBefore = sourceRgb.copyOf()
    val prepared = definition.prepare(sourceRgb, scale, colorLut, seed)
    check(sourceRgb.contentEquals(sourceBefore))
    check(prepared.size == size && prepared.period == period)
    check(prepared.bytes.contentEquals(preparedExpected)) { "Polachrome upscale preparation differs" }
    val amplitude = definition.amplitude(intensity)
    check(amplitude.contentEquals(amplitudeExpected))
    val kernel = NativeGrain8Patch(prepared.bytes, size, size, period, amplitude, color == 1, neutral != 0)
    val actual = ShortArray(pixels.size)
    kernel.renderRgbQ15(pixels, actual, width)
    check(actual.contentEquals(expected)) { "Polachrome full native node differs" }
    check(pixels.contentEquals(before))
    // Check global coordinates using a tile near the bottom right.
    val x = 1001; val y = 997; val tileWidth = 23; val tileHeight = 27
    val tile = ShortArray(tileWidth * tileHeight * 3)
    repeat(tileHeight) { row ->
        val start = ((y + row) * width + x) * 3
        pixels.copyInto(tile, row * tileWidth * 3, start, start + tileWidth * 3)
    }
    kernel.renderRgbQ15(tile, tile, tileWidth, x, y)
    repeat(tileHeight) { row ->
        val start = ((y + row) * width + x) * 3
        check(tile.copyOfRange(row * tileWidth * 3, (row + 1) * tileWidth * 3)
            .contentEquals(expected.copyOfRange(start, start + tileWidth * 3)))
    }
    GrainUpscaleVerificationResult(preparedExpected.size, width * height)
}
