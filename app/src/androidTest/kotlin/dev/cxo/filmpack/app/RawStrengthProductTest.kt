package dev.cxo.filmpack.app

import android.graphics.Bitmap
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

/** Actual RW2 fixtures exercise strength routing and preview/export consistency.
 * Native numerical agreement is covered separately by core's RAW strength oracle check.
 */
@Suppress("DEPRECATION")
class RawStrengthProductTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext

    private fun fixture(name: String) = File(context.cacheDir, "raw-fixtures/$name.RW2").also {
        assertTrue("Stage actual RW2 fixture $name via ADB", it.isFile)
    }

    private fun choice(engine: PhotoProcessor, token: String) = engine.films.single { it.token == token }

    fun testRepresentativeStrengthsBothCamerasAndCacheRevisit() {
        PhotoProcessor(context).use { engine ->
            RawPhotoStore(context).use { store ->
                val pipeline = RawRenderPipeline(context)
                for (name in CAMERAS) {
                    requireNotNull(store.openCameraIfRaw(Uri.fromFile(fixture(name)))).use { camera ->
                        val input = ShortArray(camera.width * 3)
                        camera.readRows(camera.height / 2, 1, input)
                        val normalized = ShortArray(input.size)
                        val working = ShortArray(input.size)
                        for (token in FILMS) {
                            val film = choice(engine, token)
                            assertTrue("$name/$token camera-linear support", pipeline.supports(camera, film))
                            val outputs = LinkedHashMap<Int, ShortArray>()
                            val previews = LinkedHashMap<Int, IntArray>()
                            for (strength in STRENGTHS) {
                                val output = ShortArray(input.size)
                                pipeline.renderRows(camera, film, input, normalized, working, output, strength) { false }
                                assertTrue("$name/$token/$strength bounded Q15", output.all { it >= 0 })
                                if (token in BW_FILMS) assertTrue("$name/$token/$strength neutral working RGB",
                                    (output.indices step 3).all { output[it] == output[it + 1] && output[it] == output[it + 2] })
                                outputs[strength] = output
                                var visited = 0
                                val preview = pipeline.renderPreview(camera, film, 48, 2304, { false }, strength) { row, width, y ->
                                    assertEquals(visited++, y)
                                    assertEquals(width * 3, row.size)
                                    assertTrue(row.all { it.isFinite() && it in 0f..1f })
                                }
                                try {
                                    assertTrue(visited > 0)
                                    assertTrue(preview.width <= 48 && preview.height <= 48)
                                    previews[strength] = pixels(preview)
                                    assertTrue("$name/$token/$strength nonempty photo", previews.getValue(strength).toSet().size > 8)
                                } finally { preview.recycle() }
                            }
                            assertTrue("$name/$token 50 must affect row", !outputs.getValue(50).contentEquals(outputs.getValue(100)))
                            for (capped in listOf(150, 200)) {
                                assertTrue("$name/$token row cap $capped", outputs.getValue(100).contentEquals(outputs.getValue(capped)))
                                assertTrue("$name/$token preview cap $capped", previews.getValue(100).contentEquals(previews.getValue(capped)))
                            }
                            val revisit = ShortArray(input.size)
                            pipeline.renderRows(camera, film, input, normalized, working, revisit, 50) { false }
                            assertTrue("$name/$token cache revisit50", outputs.getValue(50).contentEquals(revisit))
                            val implicit100 = ShortArray(input.size)
                            pipeline.renderRows(camera, film, input, normalized, working, implicit100) { false }
                            assertTrue("$name/$token default100", outputs.getValue(100).contentEquals(implicit100))
                            Log.i("CXORaw", "strength-product $name $token strengths=0,50,100,150,200 rowPreviewCapAndRevisit=true")
                        }
                    }
                }
            }
        }
    }

    fun testPhotoProcessorUsesCameraLinearAtStrength50() {
        PhotoProcessor(context).use { engine ->
            RawPhotoStore(context).use { store ->
                for ((name, token) in listOf("P1035087" to "Ekta100VS", "P1040758" to "FujiSuperia200E6")) {
                    val uri = Uri.fromFile(fixture(name))
                    requireNotNull(store.openCameraIfRaw(uri)).use { camera ->
                        val film = choice(engine, token)
                        // Intentionally unrelated bitmap pixels: the camera-linear path must read rawUri.
                        val placeholder = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff135791.toInt()) }
                        val expected = RawRenderPipeline(context).renderPreview(camera, film, 96, 9216, { false }, 50) { _, _, _ -> }
                        try {
                            val actual = engine.render(placeholder, film, {}, { false }, 50, rawUri = uri)
                            try { assertSameBitmap("$name/$token PhotoProcessor rawUri50", expected, actual) }
                            finally { actual.recycle() }
                        } finally { expected.recycle(); placeholder.recycle() }
                    }
                }
            }
        }
    }

    fun testNon100FullSizeExportsMatchPipelineAndCmm() {
        PhotoProcessor(context).use { engine ->
            for ((name, token) in listOf("P1035087" to "Ekta100VS", "P1040758" to "FujiSuperia200E6")) {
                val uri = Uri.fromFile(fixture(name))
                val film = choice(engine, token)
                val output = File(context.cacheDir, "raw-strength-$name-$token-50.jpg")
                val result = engine.export(uri, film, Uri.fromFile(output), {}, { false }, 50)
                assertExportPipelineCmm(uri, film, output, result, 50)
            }
        }
    }

    fun testStrengthCancellationAndRecovery() {
        PhotoProcessor(context).use { engine ->
            val film = choice(engine, "Ekta100VS")
            val uri = Uri.fromFile(fixture("P1035087"))
            RawPhotoStore(context).use { store ->
                requireNotNull(store.openCameraIfRaw(uri)).use { camera ->
                    val pipeline = RawRenderPipeline(context)
                    var checks = 0
                    try {
                        pipeline.colorIntent(camera, film, 50) { ++checks >= 16 }
                        fail("Partial-strength scene sampling must cancel")
                    } catch (_: CancellationException) { }
                    assertEquals(16, checks)
                    val recovered = pipeline.colorIntent(camera, film, 50) { false }
                    assertTrue(recovered.isFinite() && recovered in 0f..1f)
                    val first = pipeline.renderPreview(camera, film, 48, 2304, { false }, 50)
                    try {
                        checks = 0
                        try {
                            pipeline.renderPreview(camera, film, 48, 2304, { ++checks >= 3 }, 50)
                            fail("Partial-strength preview must cancel")
                        } catch (_: CancellationException) { }
                        val second = pipeline.renderPreview(camera, film, 48, 2304, { false }, 50)
                        try { assertSameBitmap("strength cancellation recovery", first, second) }
                        finally { second.recycle() }
                    } finally { first.recycle() }
                }
            }
        }
    }

    fun testGrainOriginalUsesCameraLinearBeforeAnyFilm() {
        PhotoProcessor(context).use { engine ->
            RawPhotoStore(context).use { store ->
                for (name in CAMERAS) {
                    val uri = Uri.fromFile(fixture(name))
                    val decoded = engine.decodeGrainPreview(uri)
                    try {
                        requireNotNull(store.openCameraIfRaw(uri)).use { camera ->
                            assertEquals(camera.width, decoded.sourceWidth)
                            assertEquals(camera.height, decoded.sourceHeight)
                            assertEquals(camera.orientation, decoded.sourceExifOrientation)
                            val expected = RawRenderPipeline(context).renderPreview(camera, null,
                                maxOf(decoded.bitmap.width, decoded.bitmap.height),
                                decoded.bitmap.width.toLong() * decoded.bitmap.height, { false })
                            try { assertSameBitmap("$name grain Original camera-linear", expected, decoded.bitmap) }
                            finally { expected.recycle() }
                        }
                    } finally { decoded.bitmap.recycle() }
                }
            }
        }
    }

    fun testStrength50CameraLinearGrainPreviewAndFullExport() {
        PhotoProcessor(context).use { engine ->
            val uri = Uri.fromFile(fixture("P1035087"))
            val film = choice(engine, "KodakEliteColor400")
            val preview = engine.decodeGrainPreview(uri)
            try {
                GrainPackStore(context).use { grains ->
                    assertEquals("Verified grain pack must be staged before this test", 99, grains.openInstalled().size)
                    val request = engine.grainRequest(preview, film, "KodakPortra400",
                        NativeGrain8Geometry.Format.SMALL, 1f, 100, 17)
                    val layer = grains.prepare(request)
                    val actual = engine.render(preview.bitmap, film, {}, { false }, 50, layer, uri)
                    try {
                        RawPhotoStore(context).use { store ->
                            requireNotNull(store.openCameraIfRaw(uri)).use { camera ->
                                val expected = RawRenderPipeline(context).renderPreview(camera, film,
                                    maxOf(preview.bitmap.width, preview.bitmap.height),
                                    preview.bitmap.width.toLong() * preview.bitmap.height, { false }, 50) { row, width, y ->
                                    layer.renderPreviewSourceLayout(row, row, width, 0, y)
                                }
                                try { assertSameBitmap("camera-linear grain preview50", expected, actual) }
                                finally { expected.recycle() }
                            }
                        }
                    } finally { actual.recycle() }
                    val full = grains.prepare(request.copy(invocationScale = 0))
                    val cancelledOutput = File(context.cacheDir, "raw-strength-grain-cancelled.jpg").apply { delete() }
                    var stop = false
                    try {
                        engine.export(uri, film, Uri.fromFile(cancelledOutput), { if (it >= 10) stop = true }, { stop }, 50, full)
                        fail("Partial-strength grain export must cancel")
                    } catch (_: CancellationException) { }
                    assertFalse("Cancelled export must remove partial JPEG", cancelledOutput.exists())
                    val output = File(context.cacheDir, "raw-strength-s9-elite-50-grain.jpg")
                    val result = engine.export(uri, film, Uri.fromFile(output), {}, { false }, 50, full)
                    assertExportPipelineCmm(uri, film, output, result, 50, full)
                }
            } finally { preview.bitmap.recycle() }
        }
    }

    private fun assertExportPipelineCmm(uri: Uri, film: FilmChoice, output: File,
                                      result: ExportResult, intensity: Int, grain: PhotoGrainLayer? = null) {
        assertFalse(result.resized)
        assertTrue(output.isFile && output.length() > 0)
        val cmm = FixedWideGamutToSrgb.read(context.assets.open("films/output-cmm/widegamut-to-srgb.lcmsbin").use { it.readBytes() })
        RawPhotoStore(context).use { store ->
            requireNotNull(store.openCameraIfRaw(uri)).use { camera ->
                assertEquals(camera.displayWidth, result.width)
                assertEquals(camera.displayHeight, result.height)
                val pipeline = RawRenderPipeline(context)
                assertTrue(pipeline.supports(camera, film))
                requireNotNull(RegionSrgbPhoto.open(context, Uri.fromFile(output))).use { jpeg ->
                    assertEquals(camera.width, jpeg.width)
                    assertEquals(camera.height, jpeg.height)
                    val source = ShortArray(camera.width * 3)
                    val normalized = ShortArray(source.size)
                    val working = ShortArray(source.size)
                    val encoded = ShortArray(source.size)
                    val reference = FloatArray(source.size)
                    val row = IntArray(camera.width)
                    var squared = 0.0
                    var count = 0L
                    var maximum = 0
                    for (y in listOf(camera.height / 8, camera.height / 3, camera.height / 2,
                        camera.height * 2 / 3, camera.height * 7 / 8)) {
                        camera.readRows(y, 1, source)
                        pipeline.renderRows(camera, film, source, normalized, working, encoded, intensity) { false }
                        for (i in reference.indices) reference[i] = encoded[i].toInt() / 32767f
                        grain?.renderSourceLayout(reference, reference, camera.width, 0, y)
                        cmm.render(reference, reference)
                        jpeg.readRows(y, 1, row)
                        for (x in 0 until camera.width step 13) for (channel in 0..2) {
                            val expected = (reference[x * 3 + channel].coerceIn(0f, 1f) * 255f).roundToInt()
                            val actual = (row[x] ushr (16 - channel * 8)) and 255
                            val delta = abs(expected - actual)
                            squared += delta * delta
                            maximum = maxOf(maximum, delta)
                            count++
                        }
                    }
                    val rmse = sqrt(squared / count)
                    Log.i("CXORaw", "strength-export ${film.token} intensity=$intensity grain=${grain != null} " +
                        "${result.width}x${result.height} ms=${result.elapsedMillis} bytes=${output.length()} " +
                        "pipelineCmmJpegRmse=$rmse max=$maximum samples=$count")
                    assertTrue("${film.token} exported JPEG differs from pipeline/CMM: $rmse", rmse.isFinite() && rmse < 4.0)
                }
            }
        }
    }

    private fun assertSameBitmap(label: String, expected: Bitmap, actual: Bitmap) {
        assertEquals("$label width", expected.width, actual.width)
        assertEquals("$label height", expected.height, actual.height)
        assertTrue("$label pixels", pixels(expected).contentEquals(pixels(actual)))
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    companion object {
        private val CAMERAS = listOf("P1035087", "P1040758")
        private val FILMS = listOf("KodakEliteColor400", "Ekta100VS", "FujiSuperia200E6", "KodakTriX400",
            "KodakHIE", "CineTealAndPink", "NegativeAmberT200")
        private val BW_FILMS = setOf("KodakTriX400", "KodakHIE")
        private val STRENGTHS = listOf(0, 50, 100, 150, 200)
    }
}
