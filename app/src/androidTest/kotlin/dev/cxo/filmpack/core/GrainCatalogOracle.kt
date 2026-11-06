package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.InputStream

data class GrainCatalogVerificationResult(val entries: Int, val preparedComponents: Long, val sampledNodePixels: Long)

/** Source catalog/patches build the model; all fixture pixels come from native captures. */
fun verifyGrainCatalog(
    catalog: NativeGrain8Catalog,
    colorLut: NativeGrain8ColorLut,
    golden: InputStream,
    source: (Int) -> ByteArray,
    fullNode: ((String, NativeGrain8Patch) -> Unit)? = null,
): GrainCatalogVerificationResult {
    DataInputStream(golden.buffered()).use { input ->
        fun bytes(n: Int) = ByteArray(n).also(input::readFully)
        fun i32() = Integer.reverseBytes(input.readInt())
        fun shorts(n: Int): ShortArray {
            val raw = bytes(n * 2)
            return ShortArray(n) { i ->
                ((raw[i * 2].toInt() and 255) or (raw[i * 2 + 1].toInt() shl 8)).toShort()
            }
        }
        check(String(bytes(8), Charsets.US_ASCII) == "CXOGCV01"); check(i32() == 1)
        val count = i32(); check(count == 99 && catalog.size == count)
        val byEnum = catalog.definitions().associateBy { it.grainEnum }
        val seen = mutableSetOf<Int>()
        var preparedComponents = 0L; var nodePixels = 0L
        repeat(count) {
            val grainEnum = i32(); check(seen.add(grainEnum))
            val labelLength = i32(); check(labelLength in 1..128)
            val label = String(bytes(labelLength), Charsets.US_ASCII)
            val definition = checkNotNull(byEnum[grainEnum])
            val scale = Float.fromBits(i32()); val seed = i32().toLong() and 0xFFFFFFFFL
            val size = i32(); val period = i32()
            val color = i32(); val neutral = i32()
            check(color in 0..1 && neutral in 0..1 && size in 2..1000)
            val expectedSize = i32(); check(expectedSize == size * size * 3)
            val expected = bytes(expectedSize)
            val expectedAmplitude = IntArray(256) { i32() }
            val raw = source(grainEnum)
            val prepared = definition.prepare(raw, scale, colorLut, seed)
            check(prepared.size == size && prepared.period == period)
            check(prepared.bytes.contentEquals(expected)) { "Catalog preparation differs: ${definition.identifier}" }
            val amplitude = definition.amplitude(100)
            check(amplitude.contentEquals(expectedAmplitude)) { "Catalog amplitude differs: ${definition.identifier}" }
            val kernel = NativeGrain8Patch(prepared.bytes, size, size, period, amplitude, color == 1, neutral != 0)
            val rows = i32(); check(rows == 21)
            repeat(rows) {
                val x = i32(); val y = i32(); val width = i32()
                check(x >= 0 && y in 0..1023 && width in 1..1024 && x + width <= 1024)
                val pixels = shorts(width * 3); val expectedPixels = shorts(width * 3)
                kernel.renderRgbQ15(pixels, pixels, width, x, y)
                check(pixels.contentEquals(expectedPixels)) {
                    "Catalog node row differs: ${definition.identifier} at ($x,$y)"
                }
                nodePixels += width
            }
            fullNode?.invoke(label, kernel)
            preparedComponents += expectedSize
        }
        check(seen == byEnum.keys && input.read() == -1)
        return GrainCatalogVerificationResult(count, preparedComponents, nodePixels)
    }
}
