package dev.cxo.filmpack.app

import android.net.Uri
import android.test.InstrumentationTestCase
import android.util.Log
import dev.cxo.filmpack.core.FixedWideGamutToSrgb
import dev.cxo.filmpack.core.NativeGrain8Geometry
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Product coverage for the staged Sony ILCE-7M4 ARW fixture. */
@Suppress("DEPRECATION")
class SonyArwImportTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext

    private fun arwFixture() = File(context.cacheDir, "raw-fixtures/DSC01452.ARW").also {
        assertTrue("Stage actual DSC01452.ARW fixture via ADB", it.isFile)
    }

    private fun providerUri() = Uri.parse(
        "content://dev.cxo.filmpack.app.test.photos/DSC01452.ARW"
    )

    fun testSonyArwMetadataPreviewCacheReopenAndCancellation() {
        val uri = Uri.fromFile(arwFixture())
        RawPhotoStore(context).use { store ->
            requireNotNull(store.openIfRaw(uri)).use { source ->
                assertEquals("ILCE-7M4", source.model)
                assertEquals(14, source.rawBps)
                assertEquals(7008, source.width)
                assertEquals(4672, source.height)
                assertEquals(8, source.orientation)
                val preview = source.preview(640, 400_000)
                try {
                    assertTrue(preview.width in 1..640)
                    assertTrue(preview.height in 1..640)
                    assertTrue("EXIF8 ARW preview must be portrait", preview.height > preview.width)
                    val pixels = IntArray(preview.width * preview.height)
                    preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
                    assertTrue("ARW preview must contain image variation", pixels.toSet().size > 16)
                    Log.i("CXORaw", "Sony ARW ${source.model} ${source.width}x${source.height} " +
                        "rawBps=${source.rawBps} orientation=${source.orientation}")
                } finally {
                    preview.recycle()
                }
            }
            requireNotNull(store.openIfRaw(uri)).use { reopened ->
                assertEquals(7008, reopened.width)
                assertEquals(4672, reopened.height)
                reopened.readRows(0, 1, ShortArray(reopened.width * 3))
            }
            try {
                store.openIfRaw(uri) { true }
                fail("Cancelled ARW cache hit must stop")
            } catch (_: CancellationException) {
                // Expected: cancellation is checked before serving a cache hit.
            }
        }
    }

    fun testSonyArwContentUriDisplayNameAndFilmPreviewBranches() {
        val uri = providerUri()
        context.contentResolver.openInputStream(uri).use { input ->
            assertNotNull("Sony ARW provider stream must be readable", input)
            assertEquals(0x49, requireNotNull(input).read())
        }
        assertEquals("image/png", context.contentResolver.getType(uri))
        PhotoProcessor(context).use { engine ->
            val preview = engine.decodePreview(uri, 640)
            try {
                assertTrue(preview.width in 1..640)
                assertTrue(preview.height in 1..640)
                assertTrue("EXIF8 content preview must be portrait", preview.height > preview.width)
                for ((token, intensity) in listOf("KodakPortra400" to 100, "KodakEIR" to 50)) {
                    val film = engine.films.single { it.token == token }
                    val rendered = engine.render(preview, film, {}, { false }, intensity, rawUri = uri)
                    try {
                        assertEquals(preview.width, rendered.width)
                        assertEquals(preview.height, rendered.height)
                        val pixels = IntArray(rendered.width * rendered.height)
                        rendered.getPixels(pixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)
                        assertTrue("$token ARW render must contain image variation", pixels.toSet().size > 16)
                    } finally {
                        rendered.recycle()
                    }
                }
            } finally {
                preview.recycle()
            }

            verifyPortraitGrainPreview(engine, uri)
        }
        Log.i("CXORaw", "Sony ARW content URI imported through DISPLAY_NAME despite image/png MIME")
    }

    /** Keep RAW geometry coverage runnable independently of the test provider lifecycle. */
    fun testSonyArwFileUriPortraitGrainPreview() {
        PhotoProcessor(context).use { engine ->
            verifyPortraitGrainPreview(engine, Uri.fromFile(arwFixture()))
        }
    }

    private fun verifyPortraitGrainPreview(engine: PhotoProcessor, uri: Uri) {
        val grainPreview = engine.decodeGrainPreview(uri)
        try {
            assertEquals(8, grainPreview.sourceExifOrientation)
            assertEquals(7008, grainPreview.sourceWidth)
            assertEquals(4672, grainPreview.sourceHeight)
            assertTrue("ARW grain preview must be portrait", grainPreview.bitmap.height > grainPreview.bitmap.width)
            GrainPackStore(context).use { grains ->
                assertEquals("Verified grain pack must be staged before this test", 99, grains.openInstalled().size)
                val film = engine.films.single { it.token == "KodakPortra400" }
                val layer = grains.prepare(engine.grainRequest(
                    grainPreview, film, "KodakPortra400", NativeGrain8Geometry.Format.SMALL, 1f, 100, 17
                ))
                val rendered = engine.render(grainPreview.bitmap, film, {}, { false }, 100, layer, uri)
                try {
                    assertEquals(grainPreview.bitmap.width, rendered.width)
                    assertEquals(grainPreview.bitmap.height, rendered.height)
                    val pixels = IntArray(rendered.width * rendered.height)
                    rendered.getPixels(pixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)
                    assertTrue("ARW grain render must contain image variation", pixels.toSet().size > 16)
                } finally {
                    rendered.recycle()
                }
            }
        } finally {
            grainPreview.bitmap.recycle()
        }
    }

    fun testSonyArwPortra400OriginalExportMatchesCameraLinearReference() {
        val uri = Uri.fromFile(arwFixture())
        PhotoProcessor(context).use { engine ->
            val film = engine.films.single { it.token == "KodakPortra400" }
            val output = File(context.cacheDir, "sony-arw-portra400.jpg")
            val result = engine.export(uri, film, Uri.fromFile(output), {}, { false })
            assertFalse(result.resized)
            assertTrue("Full-size ARW export must create a JPEG", output.isFile && output.length() > 0)

            val cmm = FixedWideGamutToSrgb.read(
                context.assets.open("films/output-cmm/widegamut-to-srgb.lcmsbin").use { it.readBytes() }
            )
            RawPhotoStore(context).use { store ->
                requireNotNull(store.openCameraIfRaw(uri)).use { raw ->
                    assertEquals(raw.displayWidth, result.width)
                    assertEquals(raw.displayHeight, result.height)
                    requireNotNull(RegionSrgbPhoto.open(context, Uri.fromFile(output))).use { jpeg ->
                        assertEquals(raw.width, jpeg.width)
                        assertEquals(raw.height, jpeg.height)
                        assertEquals(raw.orientation, jpeg.orientation)
                        val source = ShortArray(raw.width * 3)
                        val normalized = ShortArray(source.size)
                        val working = ShortArray(source.size)
                        val encoded = ShortArray(source.size)
                        val transformed = FloatArray(source.size)
                        val pixels = IntArray(raw.width)
                        val pipeline = RawRenderPipeline(context)
                        assertTrue("Sony must use its calibrated camera-linear path", pipeline.supports(raw, film))
                        var squared = 0.0
                        var count = 0L
                        var maxError = 0
                        for (y in listOf(raw.height / 8, raw.height / 3, raw.height / 2,
                            raw.height * 2 / 3, raw.height * 7 / 8)) {
                            raw.readRows(y, 1, source)
                            pipeline.renderRows(raw, film, source, normalized, working, encoded, 100) { false }
                            for (i in encoded.indices) transformed[i] = encoded[i].toInt() / 32767f
                            cmm.render(transformed, transformed)
                            jpeg.readRows(y, 1, pixels)
                            for (x in 0 until raw.width step 13) for (channel in 0..2) {
                                val expected = (transformed[x * 3 + channel].coerceIn(0f, 1f) * 255).roundToInt()
                                val actual = (pixels[x] ushr (16 - channel * 8)) and 255
                                val error = abs(actual - expected)
                                squared += error * error
                                count++
                                maxError = maxOf(maxError, error)
                            }
                        }
                        val rmse = sqrt(squared / count)
                        Log.i("CXORaw", "Sony ARW Portra400 ${result.width}x${result.height} " +
                            "pipelineCameraLinearJpegRmse=$rmse max=$maxError")
                        assertTrue("Camera-linear reference differs from exported JPEG: $rmse", rmse < 4.0)
                    }
                }
            }
        }
    }

    fun testRawSignatureRejectsMalformedTiffAndJpegButKeepsRw2() {
        val malformed = File(context.cacheDir, "malformed-tiff.ARW").apply {
            writeBytes(byteArrayOf(0x49, 0x49, 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00))
        }
        val standardTiff = File(context.cacheDir, "standard.tif").apply {
            instrumentation.context.assets.open("rendered-rgb.tiff").use { input ->
                outputStream().use { input.copyTo(it) }
            }
        }
        val jpegNamedArw = File(context.cacheDir, "jpeg-named.ARW").apply {
            writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()))
        }
        RawPhotoStore(context).use { store ->
            try {
                store.openIfRaw(Uri.fromFile(malformed))
                fail("Malformed TIFF ARW must fail during native decode")
            } catch (_: java.io.IOException) {
                // Expected decoder failure; the store must recover below.
            }
            assertNull("Standard TIFF must not be identified as RAW", store.openIfRaw(Uri.fromFile(standardTiff)))
            assertNull("JPEG named .ARW must not be identified as RAW", store.openIfRaw(Uri.fromFile(jpegNamedArw)))
            requireNotNull(store.openIfRaw(Uri.fromFile(rw2Fixture("P1035087")))).use { raw ->
                assertEquals("DC-S9", raw.model)
                assertEquals(6000, raw.width)
                assertEquals(4000, raw.height)
            }
        }
        malformed.delete()
        standardTiff.delete()
        jpegNamedArw.delete()
    }

    private fun rw2Fixture(name: String) = File(context.cacheDir, "raw-fixtures/$name.RW2").also {
        assertTrue("Stage actual $name.RW2 fixture via ADB", it.isFile)
    }
}
