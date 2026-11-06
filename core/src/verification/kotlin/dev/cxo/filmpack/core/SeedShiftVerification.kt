package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File

private class SeedShiftInput(stream: java.io.InputStream) : AutoCloseable {
    private val input = DataInputStream(stream.buffered())
    fun bytes(count: Int) = ByteArray(count).also(input::readFully)
    fun i32() = Integer.reverseBytes(input.readInt())
    fun i64() = java.lang.Long.reverseBytes(input.readLong())
    fun u64() = java.lang.Long.reverseBytes(input.readLong())
    fun eof() = input.read() == -1
    override fun close() = input.close()
}

fun main(args: Array<String>) {
    require(args.size == 2) { "Expected seed and shift golden paths" }
    var seedCases = 0
    SeedShiftInput(File(args[0]).inputStream()).use { input ->
        check(String(input.bytes(8), Charsets.US_ASCII) == "CXOGSDS1")
        check(input.i32() == 1)
        repeat(input.i32()) {
            val seed = input.u64()
            val lower = input.i32(); val upper = input.i32(); val count = input.i32()
            val sampler = NativeGrain8Seed.BoundedSampler(seed)
            repeat(count) { check(sampler.nextInt(lower, upper) == input.i32()) }
            seedCases++
        }
        check(input.eof())
    }
    var shiftCases = 0
    SeedShiftInput(File(args[1]).inputStream()).use { input ->
        check(String(input.bytes(8), Charsets.US_ASCII) == "CXOGSHF1")
        check(input.i32() == 1)
        repeat(input.i32()) {
            val width = input.i32(); val height = input.i32()
            val offsetX = input.i64(); val offsetY = input.i64()
            val sourceSize = input.i32(); val expectedSize = input.i32()
            val source = input.bytes(sourceSize); val expected = input.bytes(expectedSize)
            val before = source.copyOf()
            check(NativeGrain8Shift.apply(source, width, height, offsetX, offsetY).contentEquals(expected))
            check(source.contentEquals(before))
            shiftCases++
        }
        check(input.eof())
    }
    check(NativeGrain8Seed.offsets(0, 31, 23) == NativeGrain8Seed.OffsetXY(0, 0))
    val invalid = listOf<() -> Unit>(
        { NativeGrain8Seed.offsets(-1, 1, 1) },
        { NativeGrain8Seed.offsets(0x1_0000_0000L, 1, 1) },
        { NativeGrain8Seed.offsets(1, 0, 1) },
        { NativeGrain8Seed.BoundedSampler(1).nextInt(-1, 2) },
        { NativeGrain8Shift.apply(ByteArray(3), 1, 1, -1, 0) },
        { NativeGrain8Shift.apply(ByteArray(2), 1, 1, 0, 0) },
        { NativeGrain8Shift.apply(ByteArray(3), 0, 1, 0, 0) },
    )
    invalid.forEach { check(runCatching(it).isFailure) }
    println("NativeGrain8Seed/Shift: $seedCases seed records and $shiftCases shift records exact")
}
