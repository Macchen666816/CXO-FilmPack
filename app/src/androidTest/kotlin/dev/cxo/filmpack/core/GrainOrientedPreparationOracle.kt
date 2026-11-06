package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Original full prepared patches + entire Q15 grain nodes at three orientations/seeds. */
fun verifyGrainOrientedPreparation(stream: InputStream, colorLut: NativeGrain8ColorLut): Long {
    var pixels = 0L
    DataInputStream(stream.buffered()).use { input ->
        fun i32() = Integer.reverseBytes(input.readInt())
        fun bytes(n: Int) = ByteArray(n).also(input::readFully)
        fun q15(n: Int): ShortArray {
            val buffer = ByteBuffer.wrap(bytes(n * 2)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return ShortArray(n).also { buffer.get(it) }
        }
        check(String(bytes(8), Charsets.US_ASCII) == "CXOGORP1")
        check(i32() == 1)
        val count = i32(); check(count == 3)
        val sw = i32(); val sh = i32(); check(sw == 1000 && sh == 1000)
        val source = bytes(sw * sh * 3); val unchanged = source.copyOf()
        repeat(count) { record ->
            val orientation = i32(); check(orientation == intArrayOf(5, 7, 2)[record])
            check(i32() == 1263)
            val seed = i32().toLong() and 0xFFFFFFFFL
            val scale = Float.fromBits(i32()); val size = i32(); val period = i32()
            val color = i32(); val neutral = i32(); val width = i32(); val height = i32()
            check(size == 300 && period == 298 && width == 1024 && height == 1024)
            check(seed == if (record == 2) 0L else 17L)
            val expectedPrepared = bytes(size * size * 3)
            val amplitude = IntArray(256) { i32() }
            val nativeInput = q15(width * height * 3)
            val nativeOutput = q15(width * height * 3)
            val actual = NativeGrain8Prepare.prepare(source, scale, colorLut, seed,
                nativeOutputOrientation = orientation)
            check(actual.size == size && actual.period == period)
            check(actual.bytes.contentEquals(expectedPrepared)) {
                val at = actual.bytes.indices.first { actual.bytes[it] != expectedPrepared[it] }
                "Oriented preparation mismatch record=$record orientation=$orientation component=$at"
            }
            check(source.contentEquals(unchanged)) { "Oriented preparation changed source" }
            val patch = NativeGrain8Patch(actual.bytes, size, size, period, amplitude, color == 1, neutral != 0)
            patch.renderRgbQ15(nativeInput, nativeInput, width)
            check(nativeInput.contentEquals(nativeOutput)) { "Oriented full grain node mismatch record=$record" }
            pixels += width.toLong() * height
        }
        check(input.read() == -1)
    }
    return pixels
}
