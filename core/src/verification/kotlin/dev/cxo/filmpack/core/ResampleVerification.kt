package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File

private class LittleEndianInput(stream: java.io.InputStream) : AutoCloseable {
    private val input = DataInputStream(stream.buffered())
    fun bytes(count: Int): ByteArray = ByteArray(count).also(input::readFully)
    fun u16(): Int = java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(input.readShort()))
    fun i32(): Int = Integer.reverseBytes(input.readInt())
    fun i64(): Long = java.lang.Long.reverseBytes(input.readLong())
    fun eof(): Boolean = input.read() == -1
    override fun close() = input.close()
}

/** Exact checks against native raw-buffer NPY outputs packed by build_resample_fixture.py. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Expected RGB8 resample golden path" }
    var cases = 0
    LittleEndianInput(File(args[0]).inputStream()).use { input ->
        check(String(input.bytes(8), Charsets.US_ASCII) == "CXOGRSP1")
        check(input.i32() == 1)
        val count = input.i32()
        check(count == 16)
        repeat(count) {
            val kind = input.bytes(1)[0].toInt()
            val channels = input.bytes(1)[0].toInt()
            check(input.u16() == 0)
            val srcWidth = input.i32(); val srcHeight = input.i32()
            val dstWidth = input.i32(); val dstHeight = input.i32()
            val inputBytes = input.i64().also { check(it in 1..Int.MAX_VALUE) }.toInt()
            val outputBytes = input.i64().also { check(it in 1..Int.MAX_VALUE) }.toInt()
            check(channels == 3)
            val source = input.bytes(inputBytes)
            val expected = input.bytes(outputBytes)
            check(source.size == srcWidth * srcHeight * 3)
            check(expected.size == dstWidth * dstHeight * 3)
            val actual = when (kind) {
                0 -> NativeGrain8Resample.meanDownsample(source, srcWidth, srcHeight, dstWidth, dstHeight)
                1 -> NativeGrain8Resample.linearResize(source, srcWidth, srcHeight, dstWidth, dstHeight)
                else -> error("Unknown resample kind $kind")
            }
            check(actual.contentEquals(expected)) { "Resample mismatch at fixture case $it" }
            cases++
        }
        check(input.eof())
    }
    val distinct = byteArrayOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110)
    val distinctBefore = distinct.copyOf()
    val distinctOutput = NativeGrain8Resample.linearResize(distinct, 2, 2, 3, 3)
    check(distinct.contentEquals(distinctBefore)) { "linearResize mutated source" }
    check(distinctOutput[0] != distinctOutput[1] || distinctOutput[1] != distinctOutput[2]) {
        "linearResize distinct RGB channels collapsed"
    }
    val invalid = listOf(
        { NativeGrain8Resample.linearResize(ByteArray(0), Int.MAX_VALUE, Int.MAX_VALUE, 1, 1) },
        { NativeGrain8Resample.linearResize(ByteArray(3), 1, 1, Int.MAX_VALUE, Int.MAX_VALUE) },
        { NativeGrain8Resample.meanDownsample(ByteArray(3), 1, 1, 1, 1) },
        { NativeGrain8Resample.linearResize(ByteArray(2), 1, 1, 1, 1) },
    )
    invalid.forEach { check(runCatching { it() }.isFailure) { "invalid resample input accepted" } }
    println("NativeGrain8Resample: $cases native RGB8 golden cases exact")
}
