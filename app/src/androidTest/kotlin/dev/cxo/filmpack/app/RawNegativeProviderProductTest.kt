package dev.cxo.filmpack.app

import android.net.Uri
import android.test.InstrumentationTestCase
import android.util.Log
import dev.cxo.filmpack.core.RawNegativeProviderCatalog
import java.io.File
import java.util.concurrent.CancellationException

/** Actual RW2s exercise the packaged provider adapter and camera-linear product entry. */
@Suppress("DEPRECATION")
class RawNegativeProviderProductTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private fun fixture(name: String) = File(context.cacheDir, "raw-fixtures/$name.RW2").also {
        assertTrue("Stage $name.RW2 via ADB", it.isFile)
    }

    fun testAll47ReadyProvidersAtBothWhiteBalanceScenes() {
        val catalog = context.assets.open("raw-negative-providers/catalog.cxornp").use(RawNegativeProviderCatalog::read)
        val ready = catalog.ids.filter { !requireNotNull(catalog.film(it)).requiresAdditionalColorStage }
        assertEquals(68, catalog.ids.size)
        assertEquals(47, ready.size)
        PhotoProcessor(context).use { engine ->
            val choices = engine.films.associateBy { it.id }
            RawPhotoStore(context).use { store ->
                val pipeline = RawRenderPipeline(context)
                for (name in listOf("P1040758", "P1176257")) {
                    requireNotNull(store.openCameraIfRaw(Uri.fromFile(fixture(name)))).use { camera ->
                        assertEquals(153, engine.films.count { pipeline.supports(camera, it) })
                        for (id in catalog.ids) {
                            assertTrue("$name/$id static provider route", pipeline.supports(camera, choices.getValue(id)))
                        }
                        for (id in ready) {
                            val film = choices.getValue(id)
                            val isBw = requireNotNull(catalog.film(id)).runtimeBw
                            var checkedRows = 0
                            val bitmap = pipeline.renderPreview(camera, film, 48, 2304, { false }) { row, width, rowY ->
                                assertEquals(checkedRows, rowY)
                                assertEquals(width * 3, row.size)
                                assertTrue("$name/$id finite output", row.all { it.isFinite() && it in 0f..1f })
                                if (isBw) assertTrue("$name/$id gray before CMM", (row.indices step 3).all {
                                    row[it] == row[it + 1] && row[it] == row[it + 2]
                                })
                                checkedRows++
                            }
                            try {
                                val pixels = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                assertTrue("$name/$id image variation", pixels.toSet().size > 8)
                                assertTrue(checkedRows > 0)
                            } finally { bitmap.recycle() }
                            Log.i("CXORaw", "static-provider-preview $name ${film.token} BW=$isBw passed")
                        }
                    }
                }
            }
        }
    }

    fun testAll21ScaleFilterProvidersAtBothWhiteBalanceScenes() {
        val catalog = context.assets.open("raw-negative-providers/catalog.cxornp").use(RawNegativeProviderCatalog::read)
        val scaled = catalog.ids.filter { requireNotNull(catalog.film(it)).requiresAdditionalColorStage }
        assertEquals(21, scaled.size)
        PhotoProcessor(context).use { engine ->
            val choices = engine.films.associateBy { it.id }
            RawPhotoStore(context).use { store ->
                val pipeline = RawRenderPipeline(context)
                for (name in listOf("P1040758", "P1176257")) {
                    requireNotNull(store.openCameraIfRaw(Uri.fromFile(fixture(name)))).use { camera ->
                        val input = ShortArray(camera.width * 3)
                        camera.readRows(camera.height / 2, 1, input)
                        val normalized = ShortArray(input.size); val working = ShortArray(input.size)
                        val first = ShortArray(input.size); val second = ShortArray(input.size)
                        for (id in scaled) {
                            val film = choices.getValue(id)
                            assertTrue("$name/$id must use camera-linear RAW", pipeline.supports(camera, film))
                            val intent = pipeline.colorIntent(camera, film) { false }
                            assertTrue("$name/$id finite estimated intent", intent.isFinite() && intent in 0f..1f)
                            var checkedRows = 0
                            val preview = pipeline.renderPreview(camera, film, 48, 2304, { false }) { row, width, y ->
                                assertEquals(checkedRows, y)
                                assertEquals(width * 3, row.size)
                                assertTrue("$name/$id finite working output", row.all { it.isFinite() && it in 0f..1f })
                                checkedRows++
                            }
                            try {
                                val pixels = IntArray(preview.width * preview.height)
                                preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
                                assertTrue("$name/$id image variation", pixels.toSet().size > 8)
                                assertTrue(checkedRows > 0)
                            } finally { preview.recycle() }
                            pipeline.renderRows(camera, film, input, normalized, working, first) { false }
                            pipeline.renderRows(camera, film, input, normalized, working, second) { false }
                            assertTrue("$name/$id repeated row mismatch", first.contentEquals(second))
                            assertTrue("$name/$id nonnegative encoded Q15", first.all { it >= 0 })
                            Log.i("CXORaw", "scale-filter-provider-preview $name ${film.token} sceneIntent=$intent stableRows=true passed")
                        }
                    }
                }
            }
        }
    }

    fun testScaleFilterIntentSamplingCancellationAndRecovery() {
        PhotoProcessor(context).use { engine ->
            val film = engine.films.single { it.token == "NegativeAmberT200" }
            RawPhotoStore(context).use { store ->
                requireNotNull(store.openCameraIfRaw(Uri.fromFile(fixture("P1176257")))).use { camera ->
                    // A fresh pipeline has no cached scene sample. Stop on the
                    // middle of sampling, after several source rows were read.
                    val pipeline = RawRenderPipeline(context)
                    var checks = 0
                    try {
                        pipeline.colorIntent(camera, film) { ++checks >= 16 }
                        fail("Cancelled scene sampling must stop")
                    } catch (_: CancellationException) { }
                    assertEquals("Scene sampling must honor cancellation during row reads", 16, checks)
                    val recovered = pipeline.colorIntent(camera, film) { false }
                    assertTrue(recovered.isFinite() && recovered in 0f..1f)
                    Log.i("CXORaw", "scale-filter-intent samplingCancelled=true recoveredIntent=$recovered")
                }
            }
        }
    }

    fun testFujiRedFilterNativeSizeExportAndDeterministicRows() {
        PhotoProcessor(context).use { engine ->
            val uri = Uri.fromFile(fixture("P1176257"))
            val film = engine.films.single { it.token == "DigitalFujiMonochromeRedFilter" }
            val output = File(context.cacheDir, "raw-negative-provider-red-filter.jpg")
            val result = engine.export(uri, film, Uri.fromFile(output), {}, { false })
            assertFalse(result.resized)
            assertEquals(8144, result.width)
            assertEquals(5424, result.height)
            assertTrue(output.length() > 0)
            requireNotNull(RegionSrgbPhoto.open(context, Uri.fromFile(output))).use { jpeg ->
                assertEquals(8144, jpeg.width); assertEquals(5424, jpeg.height)
            }
            RawPhotoStore(context).use { store ->
                requireNotNull(store.openCameraIfRaw(uri)).use { camera ->
                    val pipeline = RawRenderPipeline(context)
                    assertTrue(pipeline.supports(camera, film))
                    val input = ShortArray(camera.width * 3)
                    camera.readRows(camera.height / 2, 1, input)
                    val normalized = ShortArray(input.size); val working = ShortArray(input.size)
                    val first = ShortArray(input.size); val second = ShortArray(input.size)
                    pipeline.renderRows(camera, film, input, normalized, working, first) { false }
                    pipeline.renderRows(camera, film, input, normalized, working, second) { false }
                    assertTrue(first.contentEquals(second))
                    assertTrue((first.indices step 3).all { first[it] == first[it + 1] && first[it] == first[it + 2] })
                }
            }
            Log.i("CXORaw", "static-provider-red-filter ${result.width}x${result.height} exportMs=${result.elapsedMillis} bytes=${output.length()}")
        }
    }
}
