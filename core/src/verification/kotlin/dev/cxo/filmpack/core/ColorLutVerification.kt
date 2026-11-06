package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File
import java.util.Random

private class ColorLutInput(stream: java.io.InputStream) : AutoCloseable {
    private val input = DataInputStream(stream.buffered())
    fun bytes(count: Int): ByteArray = ByteArray(count).also(input::readFully)
    fun i32(): Int = Integer.reverseBytes(input.readInt())
    fun eof(): Boolean = input.read() == -1
    override fun close() = input.close()
}

fun main(args: Array<String>) {
    require(args.size == 2) { "Expected color-LUT and finite-FMA golden paths" }
    var pixels = 0L
    ColorLutInput(File(args[0]).inputStream()).use { input ->
        check(String(input.bytes(8), Charsets.US_ASCII) == "CXOGCLT1")
        check(input.i32() == 1)
        val count = input.i32(); check(count == 8)
        val grid = FloatArray(input.i32().also { check(it == NativeGrain8ColorLut.GRID_FLOATS) }) {
            Float.fromBits(input.i32())
        }
        val expectedGrid = grid.copyOf()
        val lut = NativeGrain8ColorLut(grid)
        grid.fill(0f)
        check(lut.copyGrid().contentEquals(expectedGrid)) { "LUT constructor did not copy grid" }
        lut.copyGrid().fill(255f)
        check(lut.copyGrid().contentEquals(expectedGrid)) { "copyGrid exposed internal storage" }
        repeat(count) {
            val width = input.i32(); val height = input.i32()
            val source = input.bytes(width * height * 3)
            val unchanged = source.copyOf()
            val expected = input.bytes(source.size)
            val actual = lut.apply(source, width, height)
            check(source.contentEquals(unchanged)) { "LUT mutated source width=$width" }
            check(actual.contentEquals(expected)) {
                val at = actual.indices.first { actual[it] != expected[it] }
                "LUT mismatch width=$width component=$at got=${actual[at].toInt() and 255} expected=${expected[at].toInt() and 255}"
            }
            pixels += width.toLong() * height
        }
        check(input.eof())
        listOf(
            { lut.apply(ByteArray(0), 0, 1) },
            { lut.apply(ByteArray(3), Int.MAX_VALUE, Int.MAX_VALUE) },
            { lut.apply(ByteArray(2), 1, 1) },
            { NativeGrain8ColorLut(FloatArray(4)) },
            { NativeGrain8ColorLut(expectedGrid.copyOf().also { it[0] = Float.NaN }) },
            { NativeGrain8ColorLut(expectedGrid.copyOf().also { it[0] = -1f }) },
        ).forEach { check(runCatching { it() }.isFailure) }
    }

    var fmaCases = 0
    ColorLutInput(File(args[1]).inputStream()).use { input ->
        check(String(input.bytes(8), Charsets.US_ASCII) == "CXOFFMA1")
        check(input.i32() == 1)
        repeat(input.i32()) {
            val a = Float.fromBits(input.i32()); val b = Float.fromBits(input.i32()); val c = Float.fromBits(input.i32())
            val expected = input.i32()
            check(FiniteFloatFma.fma(a, b, c).toRawBits() == expected) { "Rational FMA mismatch case=$it" }
            check(Math.fma(a, b, c).toRawBits() == expected) { "JVM FMA disagrees with rational oracle case=$it" }
            fmaCases++
        }
        check(input.eof())
    }
    val random = Random(0x43584f464d41L)
    fun finite(): Float {
        while (true) {
            val value = Float.fromBits(random.nextInt())
            if (value.isFinite()) return value
        }
    }
    repeat(1_000_000) {
        val a = finite(); val b = finite(); val c = finite()
        check(FiniteFloatFma.fma(a, b, c).toRawBits() == Math.fma(a, b, c).toRawBits()) {
            "Raw-bit FMA mismatch a=${a.toRawBits()} b=${b.toRawBits()} c=${c.toRawBits()}"
        }
        if (it % 4 == 0) {
            val cancellation = -(a.toDouble() * b.toDouble()).toFloat()
            if (cancellation.isFinite()) {
                check(FiniteFloatFma.fma(a, b, cancellation).toRawBits() == Math.fma(a, b, cancellation).toRawBits())
                fmaCases++
            }
        }
        fmaCases++
    }
    listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
        check(runCatching { FiniteFloatFma.fma(it, 1f, 0f) }.isFailure)
        check(runCatching { FiniteFloatFma.fma(1f, it, 0f) }.isFailure)
        check(runCatching { FiniteFloatFma.fma(1f, 1f, it) }.isFailure)
    }
    println("NativeGrain8ColorLut: $pixels native pixels exact at 8 widths; FiniteFloatFma: $fmaCases exact cases")
}
