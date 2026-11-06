package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.NativeGrain8Amplitude
import java.io.DataInputStream

class NativeGrainAmplitudeTest : InstrumentationTestCase() {
    fun testNativeAmplitudeGolden() {
        var cases = 0
        DataInputStream(instrumentation.context.assets.open("grain-amplitude.golden").buffered()).use { input ->
            fun bytes(count: Int) = ByteArray(count).also(input::readFully)
            fun i32() = Integer.reverseBytes(input.readInt())
            assertEquals("CXOGAMP1", String(bytes(8), Charsets.US_ASCII))
            assertEquals(1, i32())
            repeat(i32()) { index ->
                val table = bytes(256)
                val coefficient = i32()
                val intensity = i32()
                val expected = IntArray(256) { i32() }
                val argument = table.copyOf()
                assertTrue("Amplitude mismatch at $index", NativeGrain8Amplitude.build(argument, coefficient, intensity).contentEquals(expected))
                assertTrue("Amplitude input mutated at $index", argument.contentEquals(table))
                cases++
            }
            assertEquals(-1, input.read())
        }
        val table = ByteArray(256) { (it xor 0xa5).toByte() }
        assertTrue(NativeGrain8Amplitude.build(table, 60, 0).contentEquals(IntArray(256)))
        assertFails { NativeGrain8Amplitude.build(ByteArray(255), 1, 1) }
        assertFails { NativeGrain8Amplitude.build(table, 61, 1) }
        assertFails { NativeGrain8Amplitude.build(table, 1, 201) }
        assertEquals(14, cases)
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
