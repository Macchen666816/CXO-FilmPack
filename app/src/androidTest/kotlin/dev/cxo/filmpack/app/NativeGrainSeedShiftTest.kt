package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.NativeGrain8Seed
import dev.cxo.filmpack.core.NativeGrain8Shift
import java.io.DataInputStream

class NativeGrainSeedShiftTest : InstrumentationTestCase() {
    private fun i32(input: DataInputStream) = Integer.reverseBytes(input.readInt())
    private fun i64(input: DataInputStream) = java.lang.Long.reverseBytes(input.readLong())

    fun testSeedGolden() {
        var records = 0
        DataInputStream(instrumentation.context.assets.open("grain-seed.golden").buffered()).use { input ->
            fun bytes(n: Int) = ByteArray(n).also(input::readFully)
            assertEquals("CXOGSDS1", String(bytes(8), Charsets.US_ASCII)); assertEquals(1, i32(input))
            repeat(i32(input)) {
                val seed = i64(input); val lower = i32(input); val upper = i32(input); val count = i32(input)
                val sampler = NativeGrain8Seed.BoundedSampler(seed)
                repeat(count) { assertEquals(sampler.nextInt(lower, upper), i32(input)) }
                records++
            }
            assertEquals(-1, input.read())
        }
        assertEquals(60, records)
    }

    fun testShiftGolden() {
        var records = 0
        DataInputStream(instrumentation.context.assets.open("grain-shift.golden").buffered()).use { input ->
            fun bytes(n: Int) = ByteArray(n).also(input::readFully)
            assertEquals("CXOGSHF1", String(bytes(8), Charsets.US_ASCII)); assertEquals(1, i32(input))
            repeat(i32(input)) {
                val width = i32(input); val height = i32(input); val x = i64(input); val y = i64(input)
                val sourceSize = i32(input); val expectedSize = i32(input)
                val source = bytes(sourceSize); val expected = bytes(expectedSize); val before = source.copyOf()
                assertTrue(NativeGrain8Shift.apply(source, width, height, x, y).contentEquals(expected))
                assertTrue(source.contentEquals(before)); records++
            }
            assertEquals(-1, input.read())
        }
        assertEquals(7, records)
    }
}
