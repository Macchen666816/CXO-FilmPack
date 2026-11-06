package dev.cxo.filmpack.app

import android.net.Uri
import android.test.InstrumentationTestCase
import android.util.Log
import dev.cxo.filmpack.core.FixedWideGamutToSrgb
import dev.cxo.filmpack.core.RawNegativeProviderCatalog
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Product checks for the S9 static provider catalog on real K40 RW2 fixtures. */
@Suppress("DEPRECATION")
class RawStaticProviderProductTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext

    private fun fixture(name: String) = File(context.cacheDir, "raw-fixtures/$name.RW2").also {
        assertTrue("Stage actual RW2 fixture $name via ADB", it.isFile)
    }

    private fun film(engine: PhotoProcessor, token: String): FilmChoice =
        engine.films.single { it.token == token }

    private fun providerIds(asset: String): Set<String> =
        context.assets.open(asset).use(RawNegativeProviderCatalog::read).ids.toSet()

    fun testS9OriginalAnd153ProvidersAreSupported() {
        PhotoProcessor(context).use { engine ->
            RawPhotoStore(context).use { store ->
                val pipeline = RawRenderPipeline(context)
                requireNotNull(store.openCameraIfRaw(Uri.fromFile(fixture("P1035087")))).use { camera ->
                    assertEquals("DC-S9", camera.model)
                    assertEquals(6000, camera.width)
                    assertEquals(4000, camera.height)
                    assertTrue("S9 original RAW route", pipeline.supports(camera, null))

                    val negative = providerIds("raw-negative-providers/catalog.cxornp")
                    val fixed = providerIds("raw-static-providers/catalog.cxornp")
                    assertEquals(68, negative.size)
                    assertEquals(75, fixed.size)
                    assertTrue("Provider catalogs must remain disjoint", negative.intersect(fixed).isEmpty())
                    val genericIds = negative + fixed
                    assertEquals(143, genericIds.size)
                    for (id in genericIds) {
                        val choice = engine.films.single { it.id == id }
                        assertTrue("S9 generic provider route ${choice.token}", pipeline.supports(camera, choice))
                    }
                    val supported = engine.films.count { pipeline.supports(camera, it) }
                    assertEquals("S9 must expose the 143 generic plus 10 special providers", 153, supported)

                    val preview = pipeline.renderPreview(camera, null, 96, 12_000, { false })
                    try {
                        assertTrue(preview.width in 1..96 && preview.height in 1..96)
                        assertTrue(preview.width.toLong() * preview.height <= 12_000)
                        val pixels = IntArray(preview.width * preview.height)
                        preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
                        assertTrue("Original RAW preview must contain image variation", pixels.toSet().size > 8)
                    } finally {
                        preview.recycle()
                    }
                }
            }
        }
    }

    fun testScaleAndBwProvidersPreviewOnS1RiiAndS9WithCancellationRecoveryAndCameraCache() {
        PhotoProcessor(context).use { engine ->
            RawPhotoStore(context).use { store ->
                val pipeline = RawRenderPipeline(context)
                val uris = listOf(
                    "P1040758" to Uri.fromFile(fixture("P1040758")),
                    "P1035087" to Uri.fromFile(fixture("P1035087")),
                )
                val cameras = uris.map { (name, uri) ->
                    name to requireNotNull(store.openCameraIfRaw(uri))
                }
                try {
                    for (token in SCALE_FILMS + BW_FILMS) {
                        val choice = film(engine, token)
                        for ((name, camera) in cameras) {
                            assertTrue("$name/$token must use the camera-linear route",
                                pipeline.supports(camera, choice))
                            val preview = renderCheckedPreview(pipeline, camera, choice, name, token)
                            preview.recycle()
                        }
                    }

                    val scaleFilm = film(engine, SCALE_FILMS.first())
                    val s1 = cameras.first { it.first == "P1040758" }.second
                    val s9 = cameras.first { it.first == "P1035087" }.second
                    var checks = 0
                    try {
                        pipeline.renderPreview(s9, scaleFilm, 96, 12_000, { ++checks >= 3 })
                        fail("Cancelled S9 static-provider preview must stop")
                    } catch (_: CancellationException) {
                        assertTrue("Cancellation must be observed during preview", checks >= 3)
                    }
                    val recovered = renderCheckedPreview(pipeline, s9, scaleFilm, "P1035087", scaleFilm.token)
                    recovered.recycle()

                    // Reusing one pipeline across bodies must not leak the S9
                    // scene sample into S1RII, and returning to S1RII must be stable.
                    val firstS1 = renderPixels(pipeline, s1, scaleFilm)
                    val s9Pixels = renderPixels(pipeline, s9, scaleFilm)
                    val secondS1 = renderPixels(pipeline, s1, scaleFilm)
                    assertContentVariation("S1RII and S9 scenes", firstS1, s9Pixels)
                    assertTrue("Camera switch must preserve S1RII cached result", firstS1.contentEquals(secondS1))
                    Log.i("CXORaw", "static-provider-preview-cameras=${cameras.map { it.first }} cacheStable=true")
                } finally {
                    cameras.forEach { it.second.close() }
                }
            }
        }
    }

    fun testS9StaticProviderExportsFullOriginalSize() {
        PhotoProcessor(context).use { engine ->
            val uri = Uri.fromFile(fixture("P1035087"))
            val choice = film(engine, "AdoxCHS100II")
            val output = File(context.cacheDir, "raw-s9-static-adox-chs100ii.jpg")
            val result = engine.export(uri, choice, Uri.fromFile(output), {}, { false })
            assertFalse(result.resized)
            assertEquals(6000, result.width)
            assertEquals(4000, result.height)
            assertTrue("Full S9 export must create a non-empty JPEG", output.isFile && output.length() > 0)
            requireNotNull(RegionSrgbPhoto.open(context, Uri.fromFile(output))).use { jpeg ->
                assertEquals(6000, jpeg.width)
                assertEquals(4000, jpeg.height)
                val row = IntArray(jpeg.width)
                jpeg.readRows(jpeg.height / 2, 1, row)
                assertTrue("Exported JPEG must contain image variation", row.toSet().size > 16)
            }
            Log.i("CXORaw", "s9-static-export ${choice.token} ${result.width}x${result.height} bytes=${output.length()}")
        }
    }

    fun testS9KodakEliteColor400FullExportMatchesPipelineCmm() {
        PhotoProcessor(context).use { engine ->
            val uri = Uri.fromFile(fixture("P1035087"))
            val choice = film(engine, "KodakEliteColor400")
            val output = File(context.cacheDir, "raw-s9-kodak-elite-color-400.jpg")
            val result = engine.export(uri, choice, Uri.fromFile(output), {}, { false })
            assertFalse(result.resized)
            assertEquals(6000, result.width)
            assertEquals(4000, result.height)
            assertTrue("Full S9 Elite export must create a non-empty JPEG", output.isFile && output.length() > 0)

            val cmm = FixedWideGamutToSrgb.read(
                context.assets.open("films/output-cmm/widegamut-to-srgb.lcmsbin").use { it.readBytes() }
            )
            RawPhotoStore(context).use { store ->
                requireNotNull(store.openCameraIfRaw(uri)).use { camera ->
                    assertEquals(6000, camera.width)
                    assertEquals(4000, camera.height)
                    val pipeline = RawRenderPipeline(context)
                    assertTrue(pipeline.supports(camera, choice))
                    val renderingIntent = pipeline.renderingIntent(camera) { false }
                    assertTrue(renderingIntent.isFinite() && renderingIntent in 0f..1f)
                    requireNotNull(RegionSrgbPhoto.open(context, Uri.fromFile(output))).use { jpeg ->
                        assertEquals(camera.width, jpeg.width)
                        assertEquals(camera.height, jpeg.height)
                        assertEquals(camera.orientation, jpeg.orientation)
                        val source = ShortArray(camera.width * 3)
                        val normalized = ShortArray(source.size)
                        val working = ShortArray(source.size)
                        val encoded = ShortArray(source.size)
                        val reference = FloatArray(source.size)
                        val pixels = IntArray(camera.width)
                        var squared = 0.0
                        var count = 0L
                        var maxError = 0
                        var colorfulSamples = 0
                        for (y in listOf(camera.height / 8, camera.height / 3, camera.height / 2,
                            camera.height * 2 / 3, camera.height * 7 / 8)) {
                            camera.readRows(y, 1, source)
                            pipeline.renderRows(camera, choice, source, normalized, working, encoded) { false }
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
                            for (x in 0 until camera.width step 13) {
                                val red = (pixels[x] ushr 16) and 255
                                val green = (pixels[x] ushr 8) and 255
                                val blue = pixels[x] and 255
                                if (red != green || green != blue) colorfulSamples++
                            }
                        }
                        val rmse = sqrt(squared / count)
                        assertTrue("Kodak Elite Color export must retain colored pixels", colorfulSamples > 0)
                        Log.i("CXORaw", "s9-elite-export ${choice.token} ${result.width}x${result.height} " +
                            "exportMs=${result.elapsedMillis} bytes=${output.length()} renderingIntent=$renderingIntent " +
                            "pipelineCmmJpegRmse=$rmse max=$maxError samples=$count colorfulSamples=$colorfulSamples")
                        assertTrue("Pipeline plus CMM differs from exported Elite JPEG: $rmse",
                            rmse.isFinite() && rmse < 4.0)
                    }
                }
            }
        }
    }

    fun testRenderingIntentIsBoundedAndRecomputedAcrossCameraSwitches() {
        RawPhotoStore(context).use { store ->
            val p104 = requireNotNull(store.openCameraIfRaw(Uri.fromFile(fixture("P1040758"))))
            val p103 = requireNotNull(store.openCameraIfRaw(Uri.fromFile(fixture("P1035087"))))
            try {
                val pipeline = RawRenderPipeline(context)
                val firstP104 = pipeline.renderingIntent(p104) { false }
                val firstP103 = pipeline.renderingIntent(p103) { false }
                val secondP104 = pipeline.renderingIntent(p104) { false }
                assertBoundedIntent("P104 first", firstP104)
                assertBoundedIntent("P103 after switch", firstP103)
                assertBoundedIntent("P104 after switch", secondP104)

                val fresh = RawRenderPipeline(context)
                val freshP104 = fresh.renderingIntent(p104) { false }
                val freshP103 = fresh.renderingIntent(p103) { false }
                assertBoundedIntent("fresh P104", freshP104)
                assertBoundedIntent("fresh P103", freshP103)
                assertEquals("P104 must be stable after returning from P103", firstP104, secondP104)
                assertEquals("P104 cache must match fresh pipeline", firstP104, freshP104)
                assertEquals("P103 cache must match fresh pipeline", firstP103, freshP103)
                Log.i("CXORaw", "rendering-intent-cache P104=$firstP104 P103=$firstP103 " +
                    "P104AfterSwitch=$secondP104 freshP104=$freshP104 freshP103=$freshP103")
            } finally {
                p104.close()
                p103.close()
            }
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
            assertEquals("$cameraName/$token row height", rows, y)
            assertEquals("$cameraName/$token RGB row", width * 3, row.size)
            assertTrue("$cameraName/$token finite preview working RGB",
                row.all { it.isFinite() && it in 0f..1f })
            if (token in BW_FILMS) assertTrue("$cameraName/$token gray before CMM",
                (row.indices step 3).all { row[it] == row[it + 1] && row[it] == row[it + 2] })
            rows++
        }
        assertTrue("$cameraName/$token preview rows", rows > 0)
        assertTrue("$cameraName/$token preview width", preview.width in 1..96)
        assertTrue("$cameraName/$token preview height", preview.height in 1..96)
        assertTrue("$cameraName/$token preview pixel budget", preview.width.toLong() * preview.height <= 12_000)
        val pixels = IntArray(preview.width * preview.height)
        preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
        assertTrue("$cameraName/$token preview must contain image variation", pixels.toSet().size > 8)
        return preview
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
        // The bodies have slightly different active-image aspect ratios.
        // Compare relative positions without assuming identical preview sizes.
        val different = first.indices.count {
            first[it] != second[(it.toLong() * second.size / first.size).toInt()]
        }
        assertTrue("$label must not reuse another camera's pixels", different > first.size / 20)
    }

    private fun assertBoundedIntent(label: String, value: Float) {
        assertTrue("$label rendering intent must be finite and bounded: $value",
            value.isFinite() && value in 0f..1f)
    }

    companion object {
        private val SCALE_FILMS = listOf(
            "KodakE100GXEktachrome100", "KodakEkta100VS", "KodakKodachrome200",
            "KodakKodachrome25", "KodakKodachrome64", "LomoRedScale100", "PolaroidPolachrome",
        )
        private val BW_FILMS = listOf("AdoxCHS100II", "FujiNeopan400")
    }
}
