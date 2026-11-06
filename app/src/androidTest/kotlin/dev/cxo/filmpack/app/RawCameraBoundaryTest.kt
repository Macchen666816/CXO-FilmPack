package dev.cxo.filmpack.app

import android.net.Uri
import android.test.InstrumentationTestCase
import org.json.JSONObject
import java.io.File

/** Camera-linear decoder boundary checks. RAW fixtures are staged on-device by the test harness. */
@Suppress("DEPRECATION")
class RawCameraBoundaryTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext

    private fun fixture(name: String): File = File(context.cacheDir, "raw-fixtures/$name.RW2").also {
        assertTrue("Stage actual RAW fixture $name via ADB", it.isFile)
    }

    private fun sonyFixture(): File = File(context.cacheDir, "raw-fixtures/DSC01452.ARW").also {
        assertTrue("Stage actual DSC01452.ARW fixture via ADB", it.isFile)
    }

    fun testCameraDomainMetadataAnd16BitRowsForBothPanasonicBodies() {
        RawPhotoStore(context).use { store ->
            for ((name, expectedModel, expectedBits, expectedWidth, expectedHeight) in listOf(
                Five("P1040758", "DC-S1RM2", 14, 8144, 5424),
                Five("P1035087", "DC-S9", 12, 6000, 4000),
            )) {
                val uri = Uri.fromFile(fixture(name))
                requireNotNull(store.openCameraIfRaw(uri)).use { photo ->
                    assertEquals(expectedModel, photo.model)
                    assertEquals(expectedBits, photo.rawBps)
                    assertEquals(expectedWidth, photo.width)
                    assertEquals(expectedHeight, photo.height)
                    assertTrue(photo.orientation in 1..8)
                    assertTrue(photo.black >= 0)
                    assertTrue(photo.maximumAfterProcess > 0)
                    assertTrue(photo.dataMaximumAfterProcess > 0)
                    assertEquals(4, photo.camMul.size)
                    assertEquals(4, photo.preMul.size)
                    val row = ShortArray(photo.width * 3)
                    photo.readRows(photo.height / 2, 1, row)
                    assertTrue("camera RGB16 row must retain values beyond 8-bit", row.any { (it.toInt() and 0xffff) > 255 })
                    assertTrue("camera RGB16 row must not be only expanded 8-bit values",
                        row.count { (it.toInt() and 0xffff) % 257 != 0 } > row.size / 2)
                }
            }
        }
    }

    fun testLegacySrgbReaderRejectsCameraDomainMetadata() {
        val cache = File(context.cacheDir, "camera-domain-reject.rgb16le").apply { writeBytes(ByteArray(6)) }
        val metadata = JSONObject().apply {
            put("width", 1); put("height", 1); put("orientation", 1); put("rawBps", 14)
            put("colorDomain", "camera_linear_rgb16")
        }
        try {
            RawSrgb16Photo.open(cache, metadata)
            fail("sRGB reader must reject camera-linear cache")
        } catch (_: IllegalArgumentException) {
            // Expected domain guard.
        } finally {
            cache.delete()
        }
    }

    fun testSonyA7IvProfileSelectsCameraLinearPipeline() {
        PhotoProcessor(context).use { engine ->
            RawPhotoStore(context).use { store ->
                requireNotNull(store.openCameraIfRaw(Uri.fromFile(sonyFixture()))).use { photo ->
                    assertEquals("ILCE-7M4", photo.model)
                    assertEquals(14, photo.rawBps)
                    assertEquals(7008, photo.width)
                    assertEquals(4672, photo.height)
                    val pipeline = RawRenderPipeline(context)
                    assertTrue("A7 IV Original must select camera-linear rendering", pipeline.supports(photo, null))
                    val film = engine.films.single { it.token == "KodakEliteColor400" }
                    assertTrue("A7 IV Elite Color 400 must select camera-linear rendering", pipeline.supports(photo, film))
                    assertEquals("A7 IV has 151 generic providers; two camera-tone BW routes remain separate", 151,
                        engine.films.count { pipeline.supports(photo, it) })
                    for (choice in listOf(null, film)) {
                        val preview = pipeline.renderPreview(photo, choice, 320, 180_000, { false })
                        try {
                            assertTrue(preview.width > 0 && preview.height > 0)
                            val pixels = IntArray(preview.width * preview.height)
                            preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
                            assertTrue("A7 IV RAW preview should contain image variation", pixels.toSet().size > 32)
                        } finally {
                            preview.recycle()
                        }
                    }
                }
            }
        }
    }

    fun testCameraDecodeCancellationDoesNotLeaveOutput() {
        val output = File(context.cacheDir, "camera-boundary-cancel.rgb16le")
        val source = fixture("P1176257")
        try {
            NativeRawBridge.decodeCamera(source.path, output.path, RawCancellationProbe { true })
            fail("Cancelled camera decode must stop")
        } catch (_: java.util.concurrent.CancellationException) {
            // Expected callback cancellation before unpack.
        } finally {
            assertFalse(output.exists())
            output.delete()
        }
    }

    private data class Five(val a: String, val b: String, val c: Int, val d: Int, val e: Int)
}
