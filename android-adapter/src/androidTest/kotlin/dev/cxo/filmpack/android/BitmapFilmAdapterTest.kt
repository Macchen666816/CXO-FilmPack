package dev.cxo.filmpack.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.test.AndroidTestCase
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

/** Device tests: Bitmap pixel semantics cannot be established by JVM stubs. */
@Suppress("DEPRECATION")
class BitmapFilmAdapterTest : AndroidTestCase() {
    private val identity = RgbTileTransform { source, destination, count ->
        source.copyInto(destination, endIndex = count * 3)
    }

    fun testAllOrientationsAcrossTileEdges() {
        val source = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(
            Color.rgb(10, 0, 0), Color.rgb(20, 0, 0), Color.rgb(30, 0, 0),
            Color.rgb(40, 0, 0), Color.rgb(50, 0, 0), Color.rgb(60, 0, 0),
        )
        source.setPixels(colors, 0, 3, 0, 0, 3, 2)
        val expected = arrayOf(
            intArrayOf(10, 20, 30, 40, 50, 60),
            intArrayOf(30, 20, 10, 60, 50, 40),
            intArrayOf(60, 50, 40, 30, 20, 10),
            intArrayOf(40, 50, 60, 10, 20, 30),
            intArrayOf(10, 40, 20, 50, 30, 60),
            intArrayOf(40, 10, 50, 20, 60, 30),
            intArrayOf(60, 30, 50, 20, 40, 10),
            intArrayOf(30, 60, 20, 50, 10, 40),
        )
        val adapter = BitmapFilmAdapter(2, 1)
        for (orientation in 1..8) {
            val output = adapter.render(source, orientation, identity)
            assertEquals(if (orientation >= 5) 2 else 3, output.width)
            assertEquals(if (orientation >= 5) 3 else 2, output.height)
            val actual = IntArray(output.width * output.height)
            output.getPixels(actual, 0, output.width, 0, 0, output.width, output.height)
            assertEquals(expected[orientation - 1].toList(), actual.map(Color::red))
            output.recycle()
        }
        source.recycle()
    }

    fun testAlphaAndIdentityWithoutDoublePremultiplication() {
        val source = Bitmap.createBitmap(3, 1, Bitmap.Config.ARGB_8888)
        source.setPixels(intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(128, 200, 100, 50), Color.rgb(3, 4, 5)), 0, 3, 0, 0, 3, 1)
        val output = BitmapFilmAdapter(2, 1).render(source, transform = identity)
        for (x in 0..2) {
            val before = source.getPixel(x, 0)
            val after = output.getPixel(x, 0)
            assertEquals(Color.alpha(before), Color.alpha(after))
            assertTrue(kotlin.math.abs(Color.red(before) - Color.red(after)) <= 1)
            assertTrue(kotlin.math.abs(Color.green(before) - Color.green(after)) <= 1)
            assertTrue(kotlin.math.abs(Color.blue(before) - Color.blue(after)) <= 1)
        }
        source.recycle()
        output.recycle()
    }

    fun testWideGamutInputUsesAndroidSrgbColorInts() {
        val displayP3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.RGBA_F16, true, displayP3)
        source.eraseColor(Color.pack(0.75f, 0.2f, 0.1f, 1f, displayP3))
        val srgb = source.getPixel(0, 0)
        val output = BitmapFilmAdapter(1, 1).render(source, transform = identity)
        assertEquals(Bitmap.Config.ARGB_8888, output.config)
        assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), output.colorSpace)
        assertEquals(srgb, output.getPixel(0, 0))
        source.recycle()
        output.recycle()
    }

    fun testJpegExifAndMalformedInput() {
        for (orientation in 1..8) {
            val app1 = byteArrayOf(
                0x45, 0x78, 0x69, 0x66, 0, 0, 0x49, 0x49, 42, 0,
                8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0,
                orientation.toByte(), 0, 0, 0, 0, 0, 0, 0,
            )
            val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0, (app1.size + 2).toByte()) + app1 + byteArrayOf(0xff.toByte(), 0xd9.toByte())
            assertEquals(orientation, ExifOrientation.readJpeg(ByteArrayInputStream(jpeg)))
            assertEquals(ExifOrientation.NORMAL, ExifOrientation.readJpeg(ByteArrayInputStream(jpeg.copyOf(14))))
        }
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.readJpeg(ByteArrayInputStream(byteArrayOf(0, 1))))
        try {
            BitmapFilmAdapter(2048, 2048)
            fail("oversized tile accepted")
        } catch (_: IllegalArgumentException) { }
    }

    fun testSrgb16TileOriginPreservesFourPixelLanes() {
        val source = Bitmap.createBitmap(7, 2, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(80, 90, 100))
        val output = BitmapFilmAdapter(3, 1).renderSrgb16(source) {
                _, destination, count, originX, _, rowWidth ->
            for (i in 0 until count) {
                val lane = (originX + (i % rowWidth)) and 3
                val value = lane / 3f
                destination[i * 3] = value
                destination[i * 3 + 1] = value
                destination[i * 3 + 2] = value
            }
        }
        val pixels = IntArray(14)
        output.getPixels(pixels, 0, 7, 0, 0, 7, 2)
        val expected = intArrayOf(0, 85, 170, 255, 0, 85, 170, 0, 85, 170, 255, 0, 85, 170)
        assertEquals(expected.toList(), pixels.map(Color::red))
        source.recycle()
        output.recycle()
    }

    fun testSrgb16TiffExportIsLittleEndianAndKeepsAlpha() {
        val source = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        source.setPixels(
            intArrayOf(Color.argb(128, 1, 2, 3), Color.argb(255, 250, 100, 7)),
            0, 2, 0, 0, 2, 1,
        )
        val exposed = source.getPixel(0, 0)
        val output = ByteArrayOutputStream()
        BitmapFilmAdapter(1, 1).writeSrgb16Tiff(source, output) { input, destination, count, _, _, _ ->
            for (i in 0 until count) {
                for (c in 0..2) destination[i * 3 + c] = (input[i * 3 + c].toInt() and 0xffff) / 65535f
            }
        }
        val bytes = output.toByteArray()
        assertEquals('I'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])
        assertEquals(42, u16(bytes, 2))
        val ifd = u32(bytes, 4)
        val entries = u16(bytes, ifd)
        assertEquals(15, entries)
        var stripOffset = 0L
        var stripBytes = 0L
        var samples = 0L
        repeat(entries) { index ->
            val at = ifd + 2 + index * 12
            when (u16(bytes, at)) {
                273 -> stripOffset = u32(bytes, at + 8).toLong()
                279 -> stripBytes = u32(bytes, at + 8).toLong()
                277 -> samples = u16(bytes, at + 8).toLong()
            }
        }
        assertEquals(4L, samples)
        assertEquals(16L, stripBytes)
        assertEquals(16, stripBytes.toInt())
        // Android exposes the device's quantized ARGB_8888 color-int contract
        // through getPixel(); verify that exact carrier and the preserved alpha.
        assertEquals(Color.red(exposed) * 257, u16(bytes, stripOffset.toInt()))
        assertEquals(Color.green(exposed) * 257, u16(bytes, stripOffset.toInt() + 2))
        assertEquals(Color.blue(exposed) * 257, u16(bytes, stripOffset.toInt() + 4))
        assertEquals(128 * 257, u16(bytes, stripOffset.toInt() + 6))
        source.recycle()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        u16(bytes, offset) or (u16(bytes, offset + 2) shl 16)

    fun testRgbaF16UsesFloatColorAndSrgb16Carrier() {
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.RGBA_F16, true, p3)
        source.eraseColor(Color.pack(0.23f, 0.61f, 0.91f, 0.5f, p3))
        val expected = source.getColor(0, 0).convert(srgb)
        val captured = ShortArray(3)
        val output = BitmapFilmAdapter(1, 1).renderSrgb16(source) {
                rgb, destination, _, _, _, _ ->
            captured[0] = rgb[0]
            captured[1] = rgb[1]
            captured[2] = rgb[2]
            destination[0] = (rgb[0].toInt() and 65535) / 65535f
            destination[1] = (rgb[1].toInt() and 65535) / 65535f
            destination[2] = (rgb[2].toInt() and 65535) / 65535f
        }
        fun q16(value: Float) = Math.rint((value * 65535f).toDouble()).toInt().coerceIn(0, 65535)
        assertEquals(q16(expected.red()), captured[0].toInt() and 65535)
        assertEquals(q16(expected.green()), captured[1].toInt() and 65535)
        assertEquals(q16(expected.blue()), captured[2].toInt() and 65535)
        val expectedAlpha = Math.rint((expected.alpha() * 255f).toDouble()).toInt()
        assertEquals(expectedAlpha, Color.alpha(output.getPixel(0, 0)))
        source.recycle()
        output.recycle()
    }

    fun testPhotoDecoderReturnsManagedSrgbF16Bitmap() {
        val input = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        input.setPixels(intArrayOf(Color.rgb(17, 33, 65), Color.rgb(201, 137, 71)), 0, 2, 0, 0, 2, 1)
        val encoded = ByteArrayOutputStream().use { stream ->
            check(input.compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.toByteArray()
        }
        val decoded = BitmapPhotoDecoder.decodeSrgbF16(encoded)
        assertEquals(Bitmap.Config.RGBA_F16, decoded.config)
        assertNotNull(decoded.colorSpace)
        for (x in 0..1) {
            val expected = input.getPixel(x, 0)
            val actual = decoded.getColor(x, 0).convert(ColorSpace.get(ColorSpace.Named.SRGB)).toArgb()
            assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 1)
            assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 1)
            assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 1)
        }
        input.recycle()
        decoded.recycle()
    }
}
