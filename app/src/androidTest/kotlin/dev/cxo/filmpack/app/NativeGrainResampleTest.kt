package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.NativeGrain8Resample
import java.io.DataInputStream
import java.io.InputStream

class NativeGrainResampleTest : InstrumentationTestCase() {
    fun testNativeResampleGolden() {
        var cases = 0
        DataInputStream(instrumentation.context.assets.open("grain-resample.golden").buffered()).use { input ->
            fun bytes(count: Int) = ByteArray(count).also(input::readFully)
            fun i32() = Integer.reverseBytes(input.readInt())
            fun i64() = java.lang.Long.reverseBytes(input.readLong())
            assertEquals("CXOGRSP1", String(bytes(8), Charsets.US_ASCII))
            assertEquals(1, i32())
            repeat(i32()) { index ->
                val kind = bytes(1)[0].toInt()
                assertEquals(3, bytes(1)[0].toInt())
                assertEquals(0, java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(input.readShort())))
                val srcWidth = i32(); val srcHeight = i32(); val dstWidth = i32(); val dstHeight = i32()
                val inputSize = i64().toInt(); val outputSize = i64().toInt()
                val source = bytes(inputSize); val expected = bytes(outputSize)
                val actual = when (kind) {
                    0 -> NativeGrain8Resample.meanDownsample(source, srcWidth, srcHeight, dstWidth, dstHeight)
                    1 -> NativeGrain8Resample.linearResize(source, srcWidth, srcHeight, dstWidth, dstHeight)
                    else -> error("Unknown fixture kind $kind at $index")
                }
                assertTrue("Native resample mismatch at $index", actual.contentEquals(expected))
                cases++
            }
            assertEquals(-1, input.read())
        }
        assertEquals(16, cases)
    }
}
