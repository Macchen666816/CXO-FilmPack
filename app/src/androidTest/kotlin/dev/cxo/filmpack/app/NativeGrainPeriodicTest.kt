package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.NativeGrain8Periodic
import java.io.DataInputStream

class NativeGrainPeriodicTest : InstrumentationTestCase() {
    fun testNativePeriodicEdgeGolden() {
        var cases = 0
        DataInputStream(instrumentation.context.assets.open("periodic-edge-v2.golden").buffered()).use { input ->
            fun bytes(count: Int) = ByteArray(count).also(input::readFully)
            fun i32() = Integer.reverseBytes(input.readInt())
            fun f32() = Float.fromBits(i32())
            assertEquals("CXEDGE02", String(bytes(8), Charsets.US_ASCII))
            repeat(i32()) { index ->
                val size = i32(); assertEquals(size, i32()); val scale = f32()
                val margin = i32(); val period = i32(); val inputSize = i32(); val outputSize = i32()
                val source = bytes(inputSize); val expected = bytes(outputSize)
                val actual = NativeGrain8Periodic.apply(source, size, scale)
                assertEquals("period at $index", period, actual.period)
                assertTrue("periodic mismatch at $index", actual.bytes.contentEquals(expected))
                assertEquals(margin, (scale * 3f + 0.5f).toInt())
                cases++
            }
            assertEquals(-1, input.read())
        }
        assertEquals(7, cases)
    }
}
