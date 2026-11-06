package dev.cxo.filmpack.app

import android.net.Uri
import android.graphics.Color
import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.FixedWideGamutToSrgb
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

@Suppress("DEPRECATION")
class RawCacheContractTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private fun fixture(name: String) = File(context.cacheDir, "raw-fixtures/$name").also { assertTrue(it.isFile) }

    fun testCameraCacheReopensAfterHandleCloseButHonorsCancellationAndDomain() {
        val uri = Uri.fromFile(fixture("P1035087.RW2"))
        RawPhotoStore(context).use { store ->
            val firstId: String
            val firstRow: ShortArray
            requireNotNull(store.openCameraIfRaw(uri)).use { first ->
                firstId = first.decodeId
                firstRow = ShortArray(first.width * 3).also { first.readRows(first.height / 2, 1, it) }
            }
            requireNotNull(store.openCameraIfRaw(uri)).use { reopened ->
                assertEquals(firstId, reopened.decodeId)
                val row = ShortArray(firstRow.size)
                reopened.readRows(reopened.height / 2, 1, row)
                assertTrue(firstRow.contentEquals(row))
            }
            try { store.openCameraIfRaw(uri) { true }; fail("Cached RAW still honors cancellation") }
            catch (_: CancellationException) { }
            requireNotNull(store.openIfRaw(uri)).use { srgb ->
                assertEquals("srgb_encoded", srgb.colorDomain)
            }
            requireNotNull(store.openCameraIfRaw(uri)).use { camera ->
                assertTrue("sRGB cache must never masquerade as camera-linear", firstId != camera.decodeId)
            }
            store.invalidate()
            requireNotNull(store.openCameraIfRaw(uri)).use { refreshed ->
                assertTrue(firstId != refreshed.decodeId)
            }
        }
    }

    fun testReimportOfReusedUriRevalidatesChangedCameraMetadata() {
        val input = File(context.cacheDir, "reimport-same-uri.NEF")
        val uri = Uri.fromFile(input)
        try {
            fixture("DSC_3271.NEF").copyTo(input, overwrite = true)
            PhotoProcessor(context).use { engine ->
                engine.decodePreview(uri, 64).recycle()
                fixture("unknown-camera.NEF").copyTo(input, overwrite = true)
                try {
                    engine.decodePreview(uri, 64).recycle()
                    fail("A new import must not reuse the previous camera's cached pixels")
                } catch (failure: IOException) {
                    assertTrue(failure.message, failure.message?.contains("缺少机身标定") == true)
                }
                fixture("DSC_3271.NEF").copyTo(input, overwrite = true)
                engine.decodePreview(uri, 64).recycle()
            }
        } finally { input.delete() }
    }

    fun testReducedPreviewMatchesFullWidthRenderingBeforeSampling() {
        val uri = Uri.fromFile(fixture("P1035087.RW2"))
        val cmm = FixedWideGamutToSrgb.read(context.assets.open("films/output-cmm/widegamut-to-srgb.lcmsbin").use { it.readBytes() })
        PhotoProcessor(context).use { engine ->
            RawPhotoStore(context).use { store ->
                requireNotNull(store.openCameraIfRaw(uri)).use { raw ->
                    val pipeline = RawRenderPipeline(context)
                    val choices = listOf(null) + listOf("KodakEliteColor400", "FujiSuperia200E6", "KodakEIR", "DigitalFujiMonochromeRedFilter")
                        .map { token -> engine.films.single { it.token == token } }
                    val width = 13; val height = 9
                    for (film in choices) {
                        val plan = pipeline.prepareRows(raw, film, 50) { false }
                        val actual = pipeline.renderPreview(raw, film, width, (width * height).toLong(), { false }, 50,
                            targetDisplaySize = width to height)
                        try {
                            val input = ShortArray(raw.width * 3); val normalized = ShortArray(input.size)
                            val working = ShortArray(input.size); val encoded = ShortArray(input.size)
                            val floats = FloatArray(input.size)
                            for (y in 0 until height) {
                                raw.readRows(y * raw.height / height, 1, input)
                                plan(input, normalized, working, encoded)
                                for (i in floats.indices) floats[i] = encoded[i].toInt() / 32767f
                                cmm.render(floats, floats)
                                for (x in 0 until width) {
                                    val offset = x * raw.width / width * 3
                                    fun q(i: Int) = (floats[offset + i].coerceIn(0f, 1f) * 255f + .5f).toInt()
                                    assertEquals("${film?.token ?: "Original"} at $x,$y", Color.rgb(q(0), q(1), q(2)), actual.getPixel(x, y))
                                }
                            }
                        } finally { actual.recycle() }
                    }
                }
            }
        }
    }
}
