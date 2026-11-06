package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File

/** Native bytes are test expectations only; the LUT is built from the ICC pair. */
fun main(args: Array<String>) {
    require(args.size == 5) { "Expected source CMM, native grid, preparation, full-node and multifilm golden" }
    val colorLut = NativeGrain8ColorLutFactory.fromQ15Transform(
        LcmsOptimizedQ15Transform.read(File(args[0]).readBytes()),
    )
    val grid = colorLut.copyGrid()
    DataInputStream(File(args[1]).inputStream().buffered()).use { input ->
        for (i in grid.indices) {
            val expectedBits = Integer.reverseBytes(input.readInt())
            check(grid[i].toRawBits() == expectedBits) { "Source-built LUT differs at float $i" }
        }
        check(input.read() == -1)
    }
    var cases = 0
    var components = 0L
    DataInputStream(File(args[2]).inputStream().buffered()).use { input ->
        fun bytes(n: Int) = ByteArray(n).also(input::readFully)
        fun i32() = Integer.reverseBytes(input.readInt())
        check(String(bytes(8), Charsets.US_ASCII) == "CXOGPRE1")
        check(i32() == 1)
        val count = i32()
        val source = bytes(1000 * 1000 * 3)
        val before = source.copyOf()
        repeat(count) { record ->
            val scale = Float.fromBits(i32()); val seed = i32().toLong() and 0xFFFFFFFFL
            val size = i32(); val period = i32(); val byteCount = i32()
            val expected = bytes(byteCount)
            val actual = NativeGrain8Prepare.prepare(source, scale, colorLut, seed)
            check(actual.size == size && actual.period == period)
            check(actual.bytes.size == byteCount)
            var unequal = 0
            var first = -1
            actual.bytes.indices.forEach { i ->
                if (actual.bytes[i] != expected[i]) { unequal++; if (first < 0) first = i }
            }
            check(unequal == 0) { "Preparation record $record: $unequal differing bytes, first=$first" }
            check(source.contentEquals(before)) { "Source was changed" }
            components += byteCount
            cases++
        }
        check(input.read() == -1)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f, Float.MAX_VALUE, 0.0001f).forEach { scale ->
            check(runCatching { NativeGrain8Prepare.prepare(source, scale, colorLut) }.isFailure)
        }
        check(runCatching { NativeGrain8Prepare.prepare(source, 0.3f, colorLut, -1) }.isFailure)
        check(runCatching { NativeGrain8Prepare.prepare(ByteArray(3), 0.3f, colorLut) }.isFailure)
    }
    check(cases == 4)
    println("NativeGrain8Prepare: $cases full preparation records, $components RGB8 components exact; ${grid.size} source-built LUT floats bitwise exact")
    val pixels = File(args[3]).inputStream().use { verifyGrainEndToEnd(it, colorLut) }
    println("NativeGrain8Prepare + Patch: $pixels full native-node pixels exact, including offset tiles")
    val films = File(args[4]).inputStream().use { verifyMultifilmGrainPreparation(it, colorLut) }
    println("NativeGrain8Prepare: $films additional film resources exact from original source PNGs")
}
