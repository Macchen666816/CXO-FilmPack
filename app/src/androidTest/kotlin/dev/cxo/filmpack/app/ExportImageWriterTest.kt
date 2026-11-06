package dev.cxo.filmpack.app

import android.graphics.BitmapFactory
import android.test.InstrumentationTestCase
import dev.cxo.filmpack.android.ExifOrientation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min

@Suppress("DEPRECATION")
class ExportImageWriterTest : InstrumentationTestCase() {
    fun testSettingsValidateRoundAndNeverEnlarge() {
        assertEquals(98, ExportSettings().jpegQuality)
        assertEquals(19 to 11, ExportSettings().dimensions(19, 11))
        assertEquals(19 to 11, ExportSettings(maxLongEdge = 100).dimensions(19, 11))
        assertEquals(10 to 6, ExportSettings(maxLongEdge = 10).dimensions(19, 11))
        assertEquals(1 to 10, ExportSettings(maxLongEdge = 10).dimensions(1, 999))
        for (quality in intArrayOf(0, 101)) {
            try { ExportSettings(jpegQuality = quality); fail() } catch (_: IllegalArgumentException) {}
        }
        try { ExportSettings(maxLongEdge = -1); fail() } catch (_: IllegalArgumentException) {}
    }

    fun testOriginalJpegIsIdenticalToExistingWriterAndKeepsOrientation() {
        for (native in listOf(false, true)) for (orientation in 1..8) {
            val expected = ByteArrayOutputStream()
            NativeJpegWriter.write(expected, 19, 17, 98, orientation, native) { top, count, argb ->
                fill(19, top, count, argb)
            }
            val actual = encode(19, 17, ExportSettings(), orientation, native)
            assertTrue(expected.toByteArray().contentEquals(actual))
            assertEquals(orientation, ExifOrientation.readJpeg(ByteArrayInputStream(actual)))
        }
    }

    fun testTiffStoresExactRgb8AndAllOrientationMetadata() {
        for (orientation in 1..8) {
            val tiff = Tiff(encode(19, 17, ExportSettings(ExportFormat.TIFF), orientation))
            assertEquals(19, tiff.value(256))
            assertEquals(17, tiff.value(257))
            assertEquals(orientation, tiff.value(274))
            assertEquals(1, tiff.value(259))
            assertEquals(2, tiff.value(262))
            assertEquals(3, tiff.value(277))
            assertEquals(1, tiff.value(284))
            val bits = tiff.value(258)
            repeat(3) { assertEquals(8, tiff.buffer.getShort(bits + it * 2).toInt()) }
            val icc = tiff.value(34675)
            assertEquals(3144, tiff.count(34675))
            assertEquals("acsp", String(tiff.bytes, icc + 36, 4, Charsets.US_ASCII))
            assertEquals("RGB ", String(tiff.bytes, icc + 16, 4, Charsets.US_ASCII))
            assertEquals(19 * 17 * 3, tiff.value(279))
            assertEquals(tiff.value(273) + tiff.value(279), tiff.bytes.size)
            for (y in 0 until 17) for (x in 0 until 19) assertEquals(pixel(x, y) and 0xffffff, tiff.pixel(x, y))
        }
    }

    fun testFractionalAreaReductionMatchesIndependentReferenceAndReadsSequentialEightRows() {
        for ((width, height, edge) in listOf(Triple(19, 17, 11), Triple(33, 25, 3), Triple(1, 19, 9), Triple(19, 1, 9))) {
            val settings = ExportSettings(ExportFormat.TIFF, maxLongEdge = edge)
            val (ow, oh) = settings.dimensions(width, height)
            val tiff = Tiff(encode(width, height, settings))
            assertEquals(ow, tiff.value(256)); assertEquals(oh, tiff.value(257))
            for (y in 0 until oh) for (x in 0 until ow) {
                val sums = DoubleArray(3)
                val left = x.toDouble() * width / ow; val right = (x + 1.0) * width / ow
                val top = y.toDouble() * height / oh; val bottom = (y + 1.0) * height / oh
                for (sy in 0 until height) for (sx in 0 until width) {
                    val weight = max(0.0, min(right, sx + 1.0) - max(left, sx.toDouble())) *
                        max(0.0, min(bottom, sy + 1.0) - max(top, sy.toDouble()))
                    val p = pixel(sx, sy)
                    sums[0] += ((p ushr 16) and 255) * weight
                    sums[1] += ((p ushr 8) and 255) * weight
                    sums[2] += (p and 255) * weight
                }
                val area = (right - left) * (bottom - top)
                val expected = sums.map { (it / area + 0.500000001).toInt() }
                val actual = tiff.pixel(x, y)
                assertEquals(expected[0], (actual ushr 16) and 255)
                assertEquals(expected[1], (actual ushr 8) and 255)
                assertEquals(expected[2], actual and 255)
            }
        }
    }

    fun testFineGrainAveragesInsteadOfAliasing() {
        val stream = ByteArrayOutputStream()
        ExportImageWriter.write(stream, 32, 32, settings = ExportSettings(ExportFormat.TIFF, maxLongEdge = 4)) { top, count, argb ->
            for (y in 0 until count) for (x in 0 until 32) argb[y * 32 + x] =
                if ((top + y + x) % 2 == 0) -1 else 0xff000000.toInt()
        }
        val tiff = Tiff(stream.toByteArray())
        for (y in 0 until 4) for (x in 0 until 4) assertEquals(0x808080, tiff.pixel(x, y))
    }

    fun testJpegQualityAndResizeDimensions() {
        val low = encode(129, 97, ExportSettings(jpegQuality = 1, maxLongEdge = 63), orientation = 6)
        val high = encode(129, 97, ExportSettings(jpegQuality = 100, maxLongEdge = 63), orientation = 6)
        assertTrue(high.size > low.size)
        assertFalse(high.contentEquals(low))
        assertEquals(6, ExifOrientation.readJpeg(ByteArrayInputStream(high)))
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(high, 0, high.size))
        try { assertEquals(63, bitmap.width); assertEquals(47, bitmap.height) } finally { bitmap.recycle() }
    }

    fun testCancelBeforeOutputAndDuringSourceRowsAndFailuresRetainIdentity() {
        for (format in ExportFormat.entries) {
            val before = ByteArrayOutputStream()
            try {
                ExportImageWriter.write(before, 17, 25, settings = ExportSettings(format), cancelled = { true }) { _, _, _ -> fail() }
                fail()
            } catch (_: CancellationException) {}
            assertEquals(0, before.size())
            for (edge in intArrayOf(0, 3)) {
                var cancel = false; var calls = 0
                try {
                    ExportImageWriter.write(ByteArrayOutputStream(), 17, 25, settings = ExportSettings(format, maxLongEdge = edge),
                        cancelled = { cancel }) { top, count, argb ->
                        calls++; fill(17, top, count, argb); if (calls == 2) cancel = true
                    }
                    fail()
                } catch (_: CancellationException) {}
                assertEquals(2, calls)
            }
            val failure = IOException("rows")
            try {
                ExportImageWriter.write(ByteArrayOutputStream(), 17, 25, settings = ExportSettings(format)) { _, _, _ -> throw failure }
                fail()
            } catch (actual: IOException) { assertSame(failure, actual) }
        }
    }

    fun testInvalidMetadataAndClassicTiffOverflowFailBeforeIo() {
        for ((width, height, orientation, settings) in listOf(
            listOf(0, 10, 1, ExportSettings()),
            listOf(10, 10, 9, ExportSettings()),
            listOf(65536, 1, 1, ExportSettings()),
            listOf(65536, 65536, 1, ExportSettings(ExportFormat.TIFF)),
        )) {
            val output = ByteArrayOutputStream()
            try {
                ExportImageWriter.write(output, width as Int, height as Int, orientation as Int, settings as ExportSettings) { _, _, _ -> fail() }
                fail()
            } catch (_: IllegalArgumentException) {}
            assertEquals(0, output.size())
        }
    }

    fun testDestinationFailureDoesNotCloseFlushOrRetry() {
        for (format in ExportFormat.entries) {
            val failure = IOException("destination")
            var writes = 0; var flushes = 0; var calls = 0
            val output = object : java.io.OutputStream() {
                override fun write(value: Int) { writes++; throw failure }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { writes++; throw failure }
                override fun close() { fail("Caller owns stream") }
                override fun flush() { flushes++ }
            }
            try {
                ExportImageWriter.write(output, 9, 9, settings = ExportSettings(format), nativeJpegEnabled = false) { top, count, argb ->
                    calls++; fill(9, top, count, argb)
                }
                fail()
            } catch (actual: IOException) { assertSame(failure, actual) }
            assertEquals(1, writes); assertEquals(0, flushes)
            assertTrue(calls <= 2)
        }
    }

    private fun encode(width: Int, height: Int, settings: ExportSettings, orientation: Int = 1, native: Boolean = false): ByteArray {
        val output = object : ByteArrayOutputStream() {
            override fun close() { fail("Caller owns stream") }
        }
        var nextTop = 0; var firstBuffer: IntArray? = null
        ExportImageWriter.write(output, width, height, orientation, settings, native) { top, count, argb ->
            assertEquals(nextTop, top); assertEquals(minOf(8, height - top), count)
            assertEquals(width * 8, argb.size)
            if (firstBuffer == null) firstBuffer = argb else assertSame(firstBuffer, argb)
            fill(width, top, count, argb); nextTop += count
        }
        assertEquals(height, nextTop)
        return output.toByteArray()
    }

    private fun pixel(x: Int, y: Int): Int = (255 shl 24) or
        (((x * 71 + y * 113) and 255) shl 16) or (((x * 13 + y * 19) and 255) shl 8) or ((x * 37 + y * 53) and 255)
    private fun fill(width: Int, top: Int, count: Int, argb: IntArray) {
        for (y in 0 until count) for (x in 0 until width) argb[y * width + x] = pixel(x, top + y)
    }

    private class Tiff(val bytes: ByteArray) {
        val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private val entries = HashMap<Int, Int>()
        init {
            check(bytes[0] == 73.toByte() && bytes[1] == 73.toByte() && buffer.getShort(2).toInt() == 42)
            val start = buffer.getInt(4)
            repeat(buffer.getShort(start).toInt() and 65535) {
                val offset = start + 2 + it * 12
                entries[buffer.getShort(offset).toInt() and 65535] = offset
            }
        }
        fun value(tag: Int): Int = buffer.getInt(requireNotNull(entries[tag]) + 8)
        fun count(tag: Int): Int = buffer.getInt(requireNotNull(entries[tag]) + 4)
        fun pixel(x: Int, y: Int): Int {
            val offset = value(273) + (y * value(256) + x) * 3
            return ((bytes[offset].toInt() and 255) shl 16) or ((bytes[offset + 1].toInt() and 255) shl 8) or (bytes[offset + 2].toInt() and 255)
        }
    }
}
