package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.InputStream

fun verifyMultifilmGrainPreparation(stream: InputStream, colorLut: NativeGrain8ColorLut): Int {
    DataInputStream(stream.buffered()).use { input ->
        fun bytes(n: Int) = ByteArray(n).also(input::readFully)
        fun i32() = Integer.reverseBytes(input.readInt())
        check(String(bytes(8), Charsets.US_ASCII) == "CXOGMFP1"); check(i32() == 1)
        val count = i32(); check(count == 6)
        repeat(count) {
            val nameLength = i32(); check(nameLength in 1..128)
            val film = String(bytes(nameLength), Charsets.UTF_8)
            val scale = Float.fromBits(i32()); val seed = i32().toLong() and 0xFFFFFFFFL
            val size = i32(); val period = i32(); val expectedLength = i32()
            check(size in 2..1000 && expectedLength == size * size * 3)
            val source = bytes(1000 * 1000 * 3); val before = source.copyOf()
            val expected = bytes(expectedLength)
            val actual = NativeGrain8Prepare.prepare(source, scale, colorLut, seed)
            check(actual.size == size && actual.period == period)
            check(actual.bytes.contentEquals(expected)) { "Prepared patch differs for $film" }
            check(source.contentEquals(before))
        }
        check(input.read() == -1)
        return count
    }
}

/** Actual full Grain node captures, with source-only preparation as the input. */
fun verifyGrainEndToEnd(stream: InputStream, colorLut: NativeGrain8ColorLut): Int {
    DataInputStream(stream.buffered()).use { input ->
        fun bytes(n: Int) = ByteArray(n).also(input::readFully)
        fun i32() = Integer.reverseBytes(input.readInt())
        fun shorts(n: Int): ShortArray {
            val packed = bytes(n * 2)
            return ShortArray(n) { i ->
                ((packed[i * 2].toInt() and 255) or (packed[i * 2 + 1].toInt() shl 8)).toShort()
            }
        }
        check(String(bytes(8), Charsets.US_ASCII) == "CXOGE2E1"); check(i32() == 1)
        val count = i32(); val width = i32(); val height = i32()
        check(count == 4 && width == 1024 && height == 1024)
        val patchSource = bytes(1000 * 1000 * 3)
        val source = shorts(width * height * 3); val before = source.copyOf()
        val amplitude = IntArray(256) { i32() }
        repeat(count) { record ->
            val scale = Float.fromBits(i32()); val seed = i32().toLong() and 0xFFFFFFFFL
            val expected = shorts(source.size)
            val prepared = NativeGrain8Prepare.prepare(patchSource, scale, colorLut, seed)
            val overlay = NativeGrain8Patch(prepared.bytes, prepared.size, prepared.size, prepared.period, amplitude)
            val actual = ShortArray(source.size)
            overlay.renderRgbQ15(source, actual, width)
            check(actual.contentEquals(expected)) { "Full grain preparation/overlay differs, record $record" }
            check(source.contentEquals(before))
            // A row tile crosses periods and starts away from the image origin.
            val x = 19; val y = 37; val tileWidth = 503
            val start = (y * width + x) * 3
            val tile = source.copyOfRange(start, start + tileWidth * 3)
            overlay.renderRgbQ15(tile, tile, tileWidth, x, y)
            check(tile.contentEquals(expected.copyOfRange(start, start + tileWidth * 3)))
        }
        check(input.read() == -1)
        return count * width * height
    }
}
