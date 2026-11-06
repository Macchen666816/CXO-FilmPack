package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.Random

/** Shared JVM/Android entry: expectations come from native ImageOwner/ResizeImage. */
fun verifyGrainCubic(stream: InputStream): Long {
    var pixels = 0L
    DataInputStream(stream.buffered()).use { input ->
        fun i32() = Integer.reverseBytes(input.readInt())
        fun bytes(n: Int) = ByteArray(n).also(input::readFully)
        check(String(bytes(8), Charsets.US_ASCII) == "CXOGCUB1")
        check(i32() == 1)
        val count = i32(); check(count == 13)
        repeat(count) { record ->
            val sw = i32(); val sh = i32(); val dw = i32(); val dh = i32()
            val source = bytes(sw * sh * 3)
            val before = source.copyOf()
            val expected = bytes(dw * dh * 3)
            val actual = NativeGrain8Cubic.resize(source, sw, sh, dw, dh)
            check(source.contentEquals(before)) { "Cubic mutated source at record $record" }
            check(actual !== source) { "Cubic must return owned output" }
            check(actual.contentEquals(expected)) {
                val at = actual.indices.first { actual[it] != expected[it] }
                "Cubic record=$record ${sw}x$sh -> ${dw}x$dh component=$at got=${actual[at].toInt() and 255} expected=${expected[at].toInt() and 255}"
            }
            pixels += dw.toLong() * dh
        }
        check(input.read() == -1)
    }
    listOf(
        { NativeGrain8Cubic.resize(ByteArray(0), 0, 1, 1, 1) },
        { NativeGrain8Cubic.resize(ByteArray(3), 1, 1, Int.MAX_VALUE, Int.MAX_VALUE) },
        { NativeGrain8Cubic.resize(ByteArray(2), 1, 1, 1, 1) },
        { NativeGrain8Cubic.resize(ByteArray(12), 2, 2, 1, 3) },
    ).forEach { check(runCatching(it).exceptionOrNull() is IllegalArgumentException) }
    return pixels
}

fun main(args: Array<String>) {
    require(args.size == 1) { "Expected grain-cubic.golden path" }
    val pixels = File(args[0]).inputStream().use(::verifyGrainCubic)
    // Exercises extreme coordinates too, without allocating enormous images.
    val random = Random(0x43584f435542L)
    repeat(100_000) {
        val sourceSize = 1 + random.nextInt(Int.MAX_VALUE / 3 - 1)
        val destinationSize = sourceSize + random.nextInt(Int.MAX_VALUE / 3 - sourceSize)
        val destination = random.nextInt(destinationSize)
        val scale = 1.0 / (destinationSize.toDouble() / sourceSize.toDouble())
        val expected = Math.fma(destination.toDouble() + 0.5, scale, -0.5).toFloat()
        check(NativeGrain8Cubic.coordinate(destination, scale).toRawBits() == expected.toRawBits()) {
            "Cubic FMADD coordinate mismatch at $destination/$destinationSize from $sourceSize"
        }
    }
    println("NativeGrain8Cubic: 13 native records, $pixels pixels exact; 100000 Double-FMADD coordinate cases exact")
}
