package dev.cxo.filmpack.app

import android.net.Uri
import android.test.InstrumentationTestCase
import android.util.Log
import dev.cxo.filmpack.core.FixedWideGamutToSrgb
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Product checks for the ten specialized RAW providers on real K40 fixtures. */
@Suppress("DEPRECATION")
class RawSpecialProviderProductTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext

    private fun fixture(name: String) = File(context.cacheDir, "raw-fixtures/$name.RW2").also {
        assertTrue("Stage actual RW2 fixture $name via ADB", it.isFile)
    }

    private fun film(engine: PhotoProcessor, token: String): FilmChoice =
        engine.films.single { it.token == token }

    fun testAllSpecialProvidersPreviewAcrossThreeCameras() {
        PhotoProcessor(context).use { engine ->
            RawPhotoStore(context).use { store ->
                val pipeline = RawRenderPipeline(context)
                val cameras = listOf("P1035087", "P1040758", "P1176257").map { name ->
                    name to requireNotNull(store.openCameraIfRaw(Uri.fromFile(fixture(name))))
                }
                try {
                    for (token in SPECIAL_FILMS) {
                        val choice = film(engine, token)
                        for ((name, camera) in cameras) {
                            assertTrue("$name/$token must select camera-linear RAW", pipeline.supports(camera, choice))
                            val preview = renderCheckedPreview(pipeline, camera, choice, name, token)
                            preview.recycle()
                            assertRepeatedRowStable(pipeline, camera, choice, name, token)
                        }
                    }
                } finally {
                    cameras.forEach { it.second.close() }
                }
            }
        }
    }

    fun testSpecialProviderCameraSwitchCancellationAndRecovery() {
        PhotoProcessor(context).use { engine ->
            RawPhotoStore(context).use { store ->
                val pipeline = RawRenderPipeline(context)
                val s1 = requireNotNull(store.openCameraIfRaw(Uri.fromFile(fixture("P1040758"))))
                val s9 = requireNotNull(store.openCameraIfRaw(Uri.fromFile(fixture("P1035087"))))
                try {
                    val choice = film(engine, "KodakEIR")
                    val firstS1 = renderPixels(pipeline, s1, choice)
                    val s9Pixels = renderPixels(pipeline, s9, choice)
                    val secondS1 = renderPixels(pipeline, s1, choice)
                    assertContentVariation("EIR S1RII and S9 scenes", firstS1, s9Pixels)
                    assertTrue("EIR S1RII result must survive S9 cache switch", firstS1.contentEquals(secondS1))

                    var checks = 0
                    try {
                        pipeline.renderPreview(s9, choice, 96, 12_000, { ++checks >= 3 })
                        fail("Cancelled special-provider preview must stop")
                    } catch (_: CancellationException) {
                        assertTrue("Special-provider preview must observe cancellation", checks >= 3)
                    }
                    val recovered = renderCheckedPreview(pipeline, s9, choice, "P1035087", choice.token)
                    recovered.recycle()
                    Log.i("CXORaw", "special-provider-cache token=${choice.token} cameras=P1040758,P1035087 recovered=true")
                } finally {
                    s1.close()
                    s9.close()
                }
            }
        }
    }

    fun testS9EirAndRolleiFullOriginalSizeExports() {
        PhotoProcessor(context).use { engine ->
            val uri = Uri.fromFile(fixture("P1035087"))
            val eir = film(engine, "KodakEIR")
            val rollei = film(engine, "RolleiIR400")
            val eirOutput = File(context.cacheDir, "raw-s9-special-kodak-eir.jpg")
            val rolleiOutput = File(context.cacheDir, "raw-s9-special-rollei-ir400.jpg")
            val eirResult = engine.export(uri, eir, Uri.fromFile(eirOutput), {}, { false })
            val rolleiResult = engine.export(uri, rollei, Uri.fromFile(rolleiOutput), {}, { false })
            assertFullS9Jpeg(eirResult, eirOutput)
            assertFullS9Jpeg(rolleiResult, rolleiOutput)
            assertTrue("EIR must export colored pixels", hasColor(eirOutput))
            assertTrue("Rollei IR400 export must contain image variation", hasVariation(rolleiOutput))
            assertTrue("Rollei IR400 export must remain neutral", hasNeutralPixels(rolleiOutput))
            assertEirPipelineCmm(uri, eir, eirOutput, eirResult)
        }
    }

    private fun renderCheckedPreview(
        pipeline: RawRenderPipeline,
        camera: RawCameraLinear16Photo,
        choice: FilmChoice,
        cameraName: String,
        token: String,
    ): android.graphics.Bitmap {
        var rows = 0
        val preview = pipeline.renderPreview(camera, choice, 96, 12_000, { false }) { row, width, y ->
            assertEquals("$cameraName/$token row index", rows, y)
            assertEquals("$cameraName/$token RGB row", width * 3, row.size)
            assertTrue("$cameraName/$token finite preview RGB", row.all { it.isFinite() && it in 0f..1f })
            if (token in BW_FILMS) {
                assertTrue("$cameraName/$token must be neutral before output CMM",
                    (row.indices step 3).all { row[it] == row[it + 1] && row[it] == row[it + 2] })
            }
            rows++
        }
        assertTrue("$cameraName/$token preview must visit rows", rows > 0)
        assertTrue("$cameraName/$token preview width", preview.width in 1..96)
        assertTrue("$cameraName/$token preview height", preview.height in 1..96)
        assertTrue("$cameraName/$token preview budget", preview.width.toLong() * preview.height <= 12_000)
        val pixels = IntArray(preview.width * preview.height)
        preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
        assertTrue("$cameraName/$token preview must contain image variation", pixels.toSet().size > 8)
        return preview
    }

    private fun assertRepeatedRowStable(
        pipeline: RawRenderPipeline,
        camera: RawCameraLinear16Photo,
        choice: FilmChoice,
        cameraName: String,
        token: String,
    ) {
        val source = ShortArray(camera.width * 3)
        camera.readRows(camera.height / 2, 1, source)
        val normalized = ShortArray(source.size)
        val working = ShortArray(source.size)
        val first = ShortArray(source.size)
        val second = ShortArray(source.size)
        pipeline.renderRows(camera, choice, source, normalized, working, first) { false }
        pipeline.renderRows(camera, choice, source, normalized, working, second) { false }
        assertTrue("$cameraName/$token repeated render row mismatch", first.contentEquals(second))
        assertTrue("$cameraName/$token encoded row must be Q15 bounded", first.all { it.toInt() in 0..32767 })
    }

    private fun renderPixels(
        pipeline: RawRenderPipeline,
        camera: RawCameraLinear16Photo,
        choice: FilmChoice,
    ): IntArray {
        val preview = pipeline.renderPreview(camera, choice, 96, 12_000, { false })
        return try {
            IntArray(preview.width * preview.height).also {
                preview.getPixels(it, 0, preview.width, 0, 0, preview.width, preview.height)
            }
        } finally {
            preview.recycle()
        }
    }

    private fun assertContentVariation(label: String, first: IntArray, second: IntArray) {
        val different = first.indices.count {
            first[it] != second[(it.toLong() * second.size / first.size).toInt()]
        }
        assertTrue("$label must not reuse another camera's pixels", different > first.size / 20)
    }

    private fun assertFullS9Jpeg(result: ExportResult, output: File) {
        assertFalse(result.resized)
        assertEquals(6000, result.width)
        assertEquals(4000, result.height)
        assertTrue("Full S9 special export must create a JPEG", output.isFile && output.length() > 0)
        requireNotNull(RegionSrgbPhoto.open(context, Uri.fromFile(output))).use { jpeg ->
            assertEquals(6000, jpeg.width)
            assertEquals(4000, jpeg.height)
        }
    }

    private fun assertEirPipelineCmm(uri: Uri, film: FilmChoice, output: File, result: ExportResult) {
        val cmm = FixedWideGamutToSrgb.read(
            context.assets.open("films/output-cmm/widegamut-to-srgb.lcmsbin").use { it.readBytes() }
        )
        RawPhotoStore(context).use { store ->
            requireNotNull(store.openCameraIfRaw(uri)).use { camera ->
                val pipeline = RawRenderPipeline(context)
                assertTrue(pipeline.supports(camera, film))
                requireNotNull(RegionSrgbPhoto.open(context, Uri.fromFile(output))).use { jpeg ->
                    val source = ShortArray(camera.width * 3)
                    val normalized = ShortArray(source.size)
                    val working = ShortArray(source.size)
                    val encoded = ShortArray(source.size)
                    val reference = FloatArray(source.size)
                    val pixels = IntArray(camera.width)
                    var squared = 0.0
                    var count = 0L
                    var maxError = 0
                    for (y in listOf(camera.height / 8, camera.height / 3, camera.height / 2,
                        camera.height * 2 / 3, camera.height * 7 / 8)) {
                        camera.readRows(y, 1, source)
                        pipeline.renderRows(camera, film, source, normalized, working, encoded) { false }
                        for (i in reference.indices) reference[i] = encoded[i].toInt() / 32767f
                        cmm.render(reference, reference)
                        jpeg.readRows(y, 1, pixels)
                        for (x in 0 until camera.width step 13) for (channel in 0..2) {
                            val expected = (reference[x * 3 + channel].coerceIn(0f, 1f) * 255f).roundToInt()
                            val actual = (pixels[x] ushr (16 - channel * 8)) and 255
                            val error = abs(actual - expected)
                            squared += error * error
                            count++
                            maxError = maxOf(maxError, error)
                        }
                    }
                    val rmse = sqrt(squared / count)
                    Log.i("CXORaw", "s9-special-export ${film.token} ${result.width}x${result.height} " +
                        "exportMs=${result.elapsedMillis} bytes=${output.length()} pipelineCmmJpegRmse=$rmse " +
                        "max=$maxError samples=$count")
                    assertTrue("EIR pipeline plus CMM differs from exported JPEG: $rmse",
                        rmse.isFinite() && rmse < 4.0)
                }
            }
        }
    }

    private fun hasColor(output: File): Boolean =
        requireNotNull(RegionSrgbPhoto.open(context, Uri.fromFile(output))).use { jpeg ->
            val row = IntArray(jpeg.width)
            jpeg.readRows(jpeg.height / 2, 1, row)
            row.any { pixel ->
                val red = (pixel ushr 16) and 255
                val green = (pixel ushr 8) and 255
                val blue = pixel and 255
                red != green || green != blue
            }
        }

    private fun hasVariation(output: File): Boolean =
        requireNotNull(RegionSrgbPhoto.open(context, Uri.fromFile(output))).use { jpeg ->
            val row = IntArray(jpeg.width)
            jpeg.readRows(jpeg.height / 2, 1, row)
            row.toSet().size > 16
        }

    private fun hasNeutralPixels(output: File): Boolean =
        requireNotNull(RegionSrgbPhoto.open(context, Uri.fromFile(output))).use { jpeg ->
            val row = IntArray(jpeg.width)
            jpeg.readRows(jpeg.height / 2, 1, row)
            var checked = 0
            var neutral = 0
            for (x in 0 until jpeg.width step 13) {
                val red = (row[x] ushr 16) and 255
                val green = (row[x] ushr 8) and 255
                val blue = row[x] and 255
                checked++
                if (maxOf(abs(red - green), abs(green - blue), abs(red - blue)) <= 2) neutral++
            }
            checked > 0 && neutral == checked
        }

    companion object {
        private val SPECIAL_FILMS = listOf(
            "Astia", "Provia", "Velvia", "Ekta100VS", "Kod64", "KodakEIR", "KodakHIE",
            "CineTealAndPink", "KodakHIEFiltre", "RolleiIR400",
        )
        private val BW_FILMS = setOf("KodakHIE", "KodakHIEFiltre", "RolleiIR400")
    }
}
