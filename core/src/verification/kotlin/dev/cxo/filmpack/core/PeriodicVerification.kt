package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File

private class PeriodicLittleEndian(stream: java.io.InputStream) : AutoCloseable {
    private val input = DataInputStream(stream.buffered())
    fun bytes(n: Int) = ByteArray(n).also(input::readFully)
    fun i32() = Integer.reverseBytes(input.readInt())
    fun float32() = Float.fromBits(i32())
    fun eof() = input.read() == -1
    override fun close() = input.close()
}

/** Exact checks against native-golden-v2.bin for all seven margin cases. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Expected periodic-edge golden path" }
    var cases = 0
    PeriodicLittleEndian(File(args[0]).inputStream()).use { input ->
        check(String(input.bytes(8), Charsets.US_ASCII) == "CXEDGE02")
        repeat(input.i32()) { index ->
            val size = input.i32(); val height = input.i32(); val scale = input.float32()
            val expectedMargin = input.i32(); val expectedPeriod = input.i32()
            check(size == height)
            val inputLength = input.i32(); val outputLength = input.i32()
            val source = input.bytes(inputLength); val expected = input.bytes(outputLength)
            check(inputLength == size * size * 3 && outputLength == inputLength)
            val sourceBefore = source.copyOf()
            val actual = NativeGrain8Periodic.apply(source, size, scale)
            check(actual.period == expectedPeriod) { "period mismatch at case $index" }
            check(actual.bytes.contentEquals(expected)) { "periodic output mismatch at case $index" }
            val margin = (scale * 3f + 0.5f).toInt()
            check(margin == expectedMargin)
            check(source.contentEquals(sourceBefore))
            cases++
        }
        check(input.eof())
    }
    val bad = ByteArray(12)
    check(runCatching { NativeGrain8Periodic.apply(bad, 2, -1f) }.isFailure)
    check(runCatching { NativeGrain8Periodic.apply(bad, 2, Float.NaN) }.isFailure)
    check(runCatching { NativeGrain8Periodic.apply(bad, 2, Float.POSITIVE_INFINITY) }.isFailure)
    check(runCatching { NativeGrain8Periodic.apply(ByteArray(3), 2, 0f) }.isFailure)
    check(runCatching { NativeGrain8Periodic.apply(ByteArray(3), 1, 0f) }.isFailure)
    check(runCatching { NativeGrain8Periodic.apply(ByteArray(12), 2, 1f) }.isFailure)
    println("NativeGrain8Periodic: $cases native RGB8 golden cases exact")
}
