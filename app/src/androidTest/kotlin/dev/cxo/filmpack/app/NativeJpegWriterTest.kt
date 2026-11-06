package dev.cxo.filmpack.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.test.InstrumentationTestCase
import dev.cxo.filmpack.android.ExifOrientation
import dev.cxo.filmpack.core.StreamingJpegWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlin.math.sqrt

/** Native cases intentionally return on an unsupported process ABI. ARM64 runs
 * must report NativeJpegWriter.isSupported=true before claiming native coverage.
 */
@Suppress("DEPRECATION")
class NativeJpegWriterTest : InstrumentationTestCase() {
    fun testExplicitDisableAndOversizedNativeDimensionsUseUnchangedCoreWriter() {
        for (width in intArrayOf(13, 65501)) {
            val expected = OwnedStream()
            StreamingJpegWriter.write(expected, width, 1, 98, 7) { top, count, argb -> fill(width, top, count, argb) }
            val actual = OwnedStream()
            NativeJpegWriter.write(actual, width, 1, 98, 7, nativeEnabled = width > 65500) { top, count, argb ->
                fill(width, top, count, argb)
            }
            assertTrue("fallback width=$width", expected.toByteArray().contentEquals(actual.toByteArray()))
            assertFalse(actual.closed)
            assertTrue(actual.flushes > 0)
        }
    }

    fun testNativeOddWidthsPartialStripsSamplingOrientationAndDecodedQuality() {
        if (!NativeJpegWriter.isSupported) return
        for (width in intArrayOf(1, 7, 8, 9, 13, 257)) {
            for (height in intArrayOf(1, 7, 8, 9, 17)) {
                compareFixture(width, height, 98, (width + height) % 8 + 1)
            }
        }
        for (orientation in 1..8) compareFixture(39, 27, 98, orientation)
        for (quality in intArrayOf(1, 49, 100)) compareFixture(33, 17, quality, 1)
    }

    fun testNativeRetainsCallbackAndCancellationExceptionIdentityAndCanRecover() {
        if (!NativeJpegWriter.isSupported) return
        for (failure in listOf(IllegalStateException("row failure"), CancellationException("cancelled row"))) {
            val output = OwnedStream()
            var calls = 0
            try {
                NativeJpegWriter.write(output, 13, 25) { top, count, argb ->
                    calls++
                    if (calls == 2) throw failure
                    fill(13, top, count, argb)
                }
                fail("row failure must propagate")
            } catch (actual: RuntimeException) {
                assertSame(failure, actual)
            }
            assertEquals(2, calls) // No fallback retry after entering the native writer.
            assertFalse(output.closed)
            assertEquals(0, output.flushes)
            compareFixture(13, 9, 98, 6)
        }
    }

    fun testNativePropagatesDestinationAndFinalFlushFailuresWithoutClosingOrRetry() {
        if (!NativeJpegWriter.isSupported) return
        for (failOnWrite in intArrayOf(1, 2)) {
            val failure = IOException("destination write $failOnWrite")
            var writes = 0
            var closed = false
            var flushes = 0
            val stream = object : OutputStream() {
                override fun write(value: Int) { error("codec must use bulk writes") }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    writes++
                    if (writes == failOnWrite) throw failure
                }
                override fun close() { closed = true }
                override fun flush() { flushes++ }
            }
            try {
                val width = if (failOnWrite == 1) 1 else 257
                val height = if (failOnWrite == 1) 1 else 513
                NativeJpegWriter.write(stream, width, height) { top, count, argb -> fill(width, top, count, argb) }
                fail("destination failure must propagate")
            } catch (actual: IOException) { assertSame(failure, actual) }
            assertEquals(failOnWrite, writes)
            assertFalse(closed)
            assertEquals(0, flushes)
        }
        val failure = IOException("final flush")
        val output = object : OwnedStream() {
            override fun flush() { super.flush(); throw failure }
        }
        try {
            NativeJpegWriter.write(output, 1, 1) { _, _, argb -> argb[0] = 0xff123456.toInt() }
            fail("flush failure must propagate")
        } catch (actual: IOException) { assertSame(failure, actual) }
        assertFalse(output.closed)
        assertEquals(1, output.flushes)
        assertNotNull(BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.size())?.also { it.recycle() })
        compareFixture(13, 9, 98, 1)
    }

    fun testInvalidArgumentsFailBeforeOutputAndRows() {
        val output = OwnedStream()
        var calls = 0
        for (parameters in listOf(
            intArrayOf(0, 1, 98, 1), intArrayOf(1, 65536, 98, 1),
            intArrayOf(1, 1, 0, 1), intArrayOf(1, 1, 101, 1),
            intArrayOf(1, 1, 98, 0), intArrayOf(1, 1, 98, 9),
        )) {
            try {
                NativeJpegWriter.write(output, parameters[0], parameters[1], parameters[2], parameters[3]) { _, _, _ -> calls++ }
                fail("invalid parameters must fail")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, calls)
        assertEquals(0, output.size())
        assertEquals(0, output.flushes)
        assertFalse(output.closed)
    }

    private fun compareFixture(width: Int, height: Int, quality: Int, orientation: Int) {
        val native = OwnedStream()
        var nextTop = 0
        var calls = 0
        var firstRows: IntArray? = null
        NativeJpegWriter.writeNative(native, width, height, quality, orientation) { top, count, argb ->
            assertEquals(nextTop, top)
            assertEquals(minOf(8, height - top), count)
            assertEquals(width * 8, argb.size)
            if (firstRows == null) firstRows = argb else assertSame(firstRows, argb)
            // Unused tail must not become edge pixels of the last partial strip.
            argb.fill(0xff00ff00.toInt())
            fill(width, top, count, argb)
            nextTop += count
            calls++
        }
        assertEquals(height, nextTop)
        assertEquals((height + 7) / 8, calls)
        assertFalse(native.closed)
        assertEquals(1, native.flushes)
        val bytes = native.toByteArray()
        assertEquals(orientation, ExifOrientation.readJpeg(ByteArrayInputStream(bytes)))
        assertBaseline444(bytes)
        val fallback = OwnedStream()
        StreamingJpegWriter.write(fallback, width, height, quality, orientation) { top, count, argb -> fill(width, top, count, argb) }
        val actual = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val coreBytes = fallback.toByteArray()
        val reference = requireNotNull(BitmapFactory.decodeByteArray(coreBytes, 0, coreBytes.size))
        try {
            // BitmapFactory leaves EXIF unapplied. All orientations must retain
            // this exact raw layout, with metadata applied once by normal import.
            assertEquals(width, actual.width)
            assertEquals(height, actual.height)
            val sourceError = rmse(actual, width, height) { x, y -> pixel(x, y) }
            val fallbackError = rmse(reference, width, height) { x, y -> pixel(x, y) }
            val differential = rmse(actual, width, height) { x, y -> reference.getPixel(x, y) }
            val sourceLimit = if (quality >= 98) 5.0 else if (quality >= 49) 65.0 else 120.0
            assertTrue("${width}x$height q$quality/o$orientation source RMSE=$sourceError", sourceError < sourceLimit)
            assertTrue("native RMSE=$sourceError, core=$fallbackError", sourceError <= fallbackError + 3.0)
            assertTrue("native/core RMSE=$differential", differential < if (quality >= 98) 4.0 else 25.0)
        } finally { actual.recycle(); reference.recycle() }
    }

    private fun assertBaseline444(bytes: ByteArray) {
        var cursor = 2
        var baseline = false
        while (cursor + 4 <= bytes.size) {
            assertEquals(0xff, bytes[cursor].toInt() and 255)
            val marker = bytes[cursor + 1].toInt() and 255
            if (marker == 0xda) break
            val length = ((bytes[cursor + 2].toInt() and 255) shl 8) or (bytes[cursor + 3].toInt() and 255)
            assertTrue(length >= 2 && cursor + 2 + length <= bytes.size)
            if (marker == 0xc0) {
                baseline = true
                assertEquals(8, bytes[cursor + 4].toInt() and 255)
                assertEquals(3, bytes[cursor + 9].toInt() and 255)
                for (component in 0..2) assertEquals(0x11, bytes[cursor + 11 + component * 3].toInt() and 255)
            }
            assertFalse("progressive JPEG is not allowed", marker == 0xc2)
            cursor += length + 2
        }
        assertTrue("baseline SOF marker missing", baseline)
        assertEquals(0xff, bytes[bytes.size - 2].toInt() and 255)
        assertEquals(0xd9, bytes.last().toInt() and 255)
    }

    private fun rmse(image: Bitmap, width: Int, height: Int, expected: (Int, Int) -> Int): Double {
        var squared = 0.0
        for (y in 0 until height) for (x in 0 until width) {
            val a = image.getPixel(x, y)
            val b = expected(x, y)
            for (shift in intArrayOf(0, 8, 16)) {
                val delta = ((a ushr shift) and 255) - ((b ushr shift) and 255)
                squared += delta.toDouble() * delta
            }
        }
        return sqrt(squared / (width.toLong() * height * 3))
    }

    private fun fill(width: Int, top: Int, count: Int, argb: IntArray) {
        for (row in 0 until count) for (x in 0 until width) argb[row * width + x] = pixel(x, top + row)
    }

    private fun pixel(x: Int, y: Int): Int = 0xff000000.toInt() or
        (((x * 19 + y * 7) and 255) shl 16) or
        (((x * 3 + y * 31) and 255) shl 8) or ((x * 33 + y * 11) and 255)

    private open class OwnedStream : ByteArrayOutputStream() {
        var closed = false
        var flushes = 0
        override fun close() { closed = true; super.close() }
        override fun flush() { flushes++; super.flush() }
    }
}
