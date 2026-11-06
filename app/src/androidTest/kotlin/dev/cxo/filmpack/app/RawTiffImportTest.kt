package dev.cxo.filmpack.app

import android.net.Uri
import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.FixedWideGamutToSrgb
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Real RAW fixtures must reach calibration, rather than Android's embedded-JPEG decoder. */
@Suppress("DEPRECATION")
class RawTiffImportTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext

    fun testNefAndDngUseCalibratedCameraPathDespiteGenericProviderMime() {
        for (name in listOf("DSC_3271.NEF", "DSC_5180.dng")) {
            val uri = Uri.parse("content://dev.cxo.filmpack.app.test.photos/$name")
            RawPhotoStore(context).use { store ->
                requireNotNull(store.openCameraIfRaw(uri)).use { photo ->
                    assertEquals("Z 6_2", photo.model)
                    assertEquals("Nikon", photo.make)
                    val pipeline = RawRenderPipeline(context)
                    assertTrue(pipeline.supports(photo, null))
                    assertTrue(photo.width > 6000 && photo.height > 4000)
                    val expected = pipeline.renderPreview(photo, null, 320, 100_000L, { false })
                    try {
                        PhotoProcessor(context).use { engine ->
                            val actual = engine.decodePreview(uri, 320)
                            try {
                                assertTrue("$name must use the calibrated Original, not an embedded JPEG", expected.sameAs(actual))
                            } finally { actual.recycle() }
                        }
                    } finally { expected.recycle() }
                }
            }
        }
    }

    fun testTiffRawIdentityRequiresBothHeaderAndRawIdentity() {
        val directory = File(context.cacheDir, "tiff-raw-identity").also { it.mkdirs() }
        val tiff = byteArrayOf(0x49, 0x49, 0x2a, 0, 8, 0, 0, 0)
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())
        try {
            RawPhotoStore(context).use { store ->
                for (extension in listOf("ArW", "NeF", "DnG")) {
                    val malformed = File(directory, "malformed.$extension").apply { writeBytes(tiff) }
                    try {
                        store.openCameraIfRaw(Uri.fromFile(malformed))
                        fail("TIFF + .$extension must reach native validation")
                    } catch (_: java.io.IOException) { /* correctly routed, invalid file */ }
                    val mislabeledJpeg = File(directory, "jpeg.$extension").apply { writeBytes(jpeg) }
                    assertNull(store.openCameraIfRaw(Uri.fromFile(mislabeledJpeg)))
                }
                val truncated = File(directory, "truncated.tiff").apply { writeBytes(tiff) }
                try {
                    store.openCameraIfRaw(Uri.fromFile(truncated))
                    fail("A failed metadata read must not silently select RGB import")
                } catch (_: java.io.IOException) { /* preserve the I/O failure */ }
                val rendered = File(directory, "rendered.tiff").apply {
                    instrumentation.context.assets.open("rendered-rgb.tiff").use { input ->
                        outputStream().use { input.copyTo(it) }
                    }
                }
                assertNull("Rendered TIFF must remain on managed RGB import", store.openCameraIfRaw(Uri.fromFile(rendered)))
            }
        } finally { directory.deleteRecursively() }
    }

    fun testNefFullExportMatchesCalibratedRows() {
        val sourceFile = File(context.cacheDir, "raw-fixtures/DSC_3271.NEF")
        assertTrue(sourceFile.isFile)
        val uri = Uri.fromFile(sourceFile)
        val output = File(context.cacheDir, "raw-import-diagnostic/DSC_3271-Elite-export.jpg")
        PhotoProcessor(context).use { engine ->
            val film = engine.films.single { it.token == "KodakEliteColor400" }
            val result = engine.export(uri, film, Uri.fromFile(output), {}, { false })
            assertFalse(result.resized)
            val cmm = FixedWideGamutToSrgb.read(context.assets.open("films/output-cmm/widegamut-to-srgb.lcmsbin").use { it.readBytes() })
            RawPhotoStore(context).use { store ->
                requireNotNull(store.openCameraIfRaw(uri)).use { photo ->
                    assertEquals(photo.displayWidth, result.width)
                    assertEquals(photo.displayHeight, result.height)
                    requireNotNull(RegionSrgbPhoto.open(context, Uri.fromFile(output))).use { jpeg ->
                        assertEquals(photo.width, jpeg.width)
                        assertEquals(photo.height, jpeg.height)
                        val pipeline = RawRenderPipeline(context)
                        val input = ShortArray(photo.width * 3)
                        val normalized = ShortArray(input.size)
                        val working = ShortArray(input.size)
                        val encoded = ShortArray(input.size)
                        val floats = FloatArray(input.size)
                        val pixels = IntArray(photo.width)
                        var squared = 0.0
                        var count = 0
                        for (y in listOf(photo.height / 8, photo.height / 3, photo.height / 2, photo.height * 7 / 8)) {
                            photo.readRows(y, 1, input)
                            pipeline.renderRows(photo, film, input, normalized, working, encoded, 100) { false }
                            for (i in floats.indices) floats[i] = encoded[i].toInt() / 32767f
                            cmm.render(floats, floats)
                            jpeg.readRows(y, 1, pixels)
                            for (x in 0 until photo.width step 13) for (c in 0..2) {
                                val expected = (floats[x * 3 + c].coerceIn(0f, 1f) * 255f).roundToInt()
                                val actual = pixels[x] ushr (16 - c * 8) and 255
                                squared += (expected - actual).toDouble() * (expected - actual)
                                count++
                            }
                        }
                        val rmse = sqrt(squared / count)
                        assertTrue("NEF export must use camera-linear rendering, RMSE=$rmse", rmse < 4.0)
                        android.util.Log.i("CXORaw", "NEF export ${result.width}x${result.height} elapsedMs=${result.elapsedMillis} rowRMSE=$rmse")
                    }
                }
            }
        }
    }
}
