package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.FiniteFloatFma
import dev.cxo.filmpack.core.NativeGrain8ColorLut
import java.io.DataInputStream

/** Native captures and exact-rational FMA oracle; does not call Android Math.fma. */
@Suppress("DEPRECATION")
class NativeGrainColorLutTest : InstrumentationTestCase() {
    fun testNativeRgb8Widths() {
        DataInputStream(instrumentation.context.assets.open("grain-color-lut.golden").buffered()).use { input ->
            assertEquals("CXOGCLT1", String(input.bytes(8), Charsets.US_ASCII))
            assertEquals(1, input.i32())
            val count = input.i32(); assertEquals(8, count)
            val floats = input.i32(); assertEquals(NativeGrain8ColorLut.GRID_FLOATS, floats)
            val grid = FloatArray(floats) { Float.fromBits(input.i32()) }
            val lut = NativeGrain8ColorLut(grid)
            grid.fill(0f)
            val copy = lut.copyGrid()
            assertTrue(copy.any { it != 0f })
            copy.fill(0f)
            assertTrue(lut.copyGrid().any { it != 0f })
            var pixels = 0L
            repeat(count) {
                val width = input.i32(); val height = input.i32()
                val source = input.bytes(width * height * 3)
                val before = source.copyOf()
                val expected = input.bytes(source.size)
                val actual = lut.apply(source, width, height)
                assertTrue("Source changed at width=$width", source.contentEquals(before))
                assertTrue("Native RGB8 LUT mismatch at width=$width", actual.contentEquals(expected))
                pixels += width.toLong() * height
            }
            assertEquals(524325L, pixels)
            assertEquals(-1, input.read())
        }
    }

    fun testFiniteFmaRationalOracle() {
        DataInputStream(instrumentation.context.assets.open("grain-finite-fma.golden").buffered()).use { input ->
            assertEquals("CXOFFMA1", String(input.bytes(8), Charsets.US_ASCII))
            assertEquals(1, input.i32())
            val count = input.i32(); assertEquals(26166, count)
            repeat(count) {
                val a = Float.fromBits(input.i32()); val b = Float.fromBits(input.i32()); val c = Float.fromBits(input.i32())
                val expected = input.i32()
                assertEquals("Finite FMA rational mismatch at case=$it", expected, FiniteFloatFma.fma(a, b, c).toRawBits())
            }
            assertEquals(-1, input.read())
        }
    }

    private fun DataInputStream.i32(): Int = Integer.reverseBytes(readInt())
    private fun DataInputStream.bytes(count: Int): ByteArray = ByteArray(count).also(::readFully)
}
