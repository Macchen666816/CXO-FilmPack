package dev.cxo.filmpack.app

import android.graphics.Bitmap
import android.net.Uri
import android.test.InstrumentationTestCase
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

@Suppress("DEPRECATION")
class RawRoutingAuditTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val testContext get() = instrumentation.context
    private fun fileUri(name: String): Uri = Uri.fromFile(File(context.cacheDir, "raw-fixtures/$name").also { assertTrue(it.isFile) })
    private fun opaqueUri(name: String, identity: String = "opaque"): Uri = Uri.parse(
        "content://dev.cxo.filmpack.app.test.photos/asset0001?fixture=$name&identity=$identity")

    fun testAllIndexedOfficialNamesAfterLibRawNormalizationResolveToCorrectProfile() {
        val golden = testContext.assets.open("raw-model-routing.golden.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(584, golden.getInt("profileCount"))
        val rows = golden.getJSONArray("pairs")
        assertEquals(1186, rows.length())
        val profiles = RawCameraProfiles(context)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val make = row.getString("make"); val model = row.getString("model")
            assertEquals("$make / $model", row.getString("expectedId"), profiles.forModel(model, make)?.id)
        }
        assertNull("Do not guess a manufacturer for Leica's short model", profiles.forModel("M9", "Other"))
        assertNull("Do not guess a manufacturer for Fuji's short model", profiles.forModel("X100", "Other"))
    }

    fun testRealRw2ArwNefDngAndOpaqueProviderUseSameCalibratedOriginal() {
        for (name in listOf("P1035087.RW2", "P1040758.RW2", "DSC01452.ARW", "DSC07319.ARW", "DSC_3271.NEF", "DSC_5180.dng")) {
            PhotoProcessor(context).use { engine ->
                val expected = engine.decodePreview(fileUri(name), 320)
                try {
                    val actual = engine.decodePreview(opaqueUri(name), 320)
                    try { assertTrue("Opaque $name must preserve calibrated RAW route", actual.sameAs(expected)) }
                    finally { actual.recycle() }
                } finally { expected.recycle() }
            }
        }
    }

    fun testArwMimeIdentityWorksWithoutRawDisplayNameOrPath() {
        RawPhotoStore(context).use { store ->
            requireNotNull(store.openCameraIfRaw(opaqueUri("DSC01452.ARW", "mime"))).use { raw ->
                assertEquals("ILCE-7M4", raw.model)
                assertTrue(RawRenderPipeline(context).supports(raw, null))
            }
        }
    }

    fun testRw2AndArwGrainSourceDoesNotSwitchToRgb() {
        for (name in listOf("P1035087.RW2", "DSC01452.ARW")) {
            PhotoProcessor(context).use { engine ->
                val grain = engine.decodeGrainPreview(opaqueUri(name), maxEdge = 4096, maxPixels = 4_000_000L)
                try {
                    RawPhotoStore(context).use { store ->
                        requireNotNull(store.openCameraIfRaw(fileUri(name))).use { raw ->
                            val expected = RawRenderPipeline(context).renderPreview(raw, null,
                                maxOf(grain.bitmap.width, grain.bitmap.height),
                                grain.bitmap.width.toLong() * grain.bitmap.height, { false },
                                targetDisplaySize = grain.bitmap.width to grain.bitmap.height)
                            try { assertTrue("$name grain source must use calibrated Original", expected.sameAs(grain.bitmap)) }
                            finally { expected.recycle() }
                        }
                    }
                } finally { grain.bitmap.recycle() }
            }
        }
    }

    fun testUnknownCameraFailsPreviewGrainRenderAndExportWithoutFallback() {
        val uri = fileUri("unknown-camera.NEF")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val output = File(context.cacheDir, "unknown-camera-should-not-export.jpg").also { it.delete() }
        try {
            PhotoProcessor(context).use { engine ->
                val film = engine.films.single { it.token == "KodakEliteColor400" }
                expectUnsupported("缺少机身标定") { engine.decodePreview(uri, 320).recycle() }
                expectUnsupported("缺少机身标定") { engine.decodeGrainPreview(uri).bitmap.recycle() }
                expectUnsupported("缺少机身标定") { engine.render(bitmap, film, {}, { false }, rawUri = uri).recycle() }
                expectUnsupported("缺少机身标定") { engine.export(uri, film, Uri.fromFile(output), {}, { false }) }
                assertFalse("Rejected RAW must not create a misleading export", output.exists())
            }
        } finally { bitmap.recycle(); output.delete() }
    }

    fun testUnsupportedSonyFilmRejectsPreviewAndExportConsistently() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val output = File(context.cacheDir, "unsupported-film-should-not-export.jpg").also { it.delete() }
        try {
            PhotoProcessor(context).use { engine ->
                val film = engine.films.single { it.id == "ColorModeArgentiqueKodakHIEFiltre" }
                val uri = fileUri("DSC01452.ARW")
                expectUnsupported("请更换胶片") { engine.render(bitmap, film, {}, { false }, rawUri = uri).recycle() }
                expectUnsupported("请更换胶片") { engine.export(uri, film, Uri.fromFile(output), {}, { false }) }
                assertFalse(output.exists())
            }
        } finally { bitmap.recycle(); output.delete() }
    }

    fun testOrdinaryTiffAndDisguisedJpegStayOutOfRaw() {
        val directory = File(context.cacheDir, "routing-rgb-fixtures").also { it.mkdirs() }
        try {
            val tiff = File(directory, "rendered.tiff")
            testContext.assets.open("rendered-rgb.tiff").use { input -> tiff.outputStream().use { input.copyTo(it) } }
            val jpeg = File(directory, "image.ARW")
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            try { jpeg.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
            finally { bitmap.recycle() }
            RawPhotoStore(context).use { store ->
                assertNull("A valid rendered TIFF is not RAW", store.openCameraIfRaw(Uri.fromFile(tiff)))
                assertNull("A valid JPEG with .ARW suffix stays RGB", store.openCameraIfRaw(Uri.fromFile(jpeg)))
            }
        } finally { directory.deleteRecursively() }
    }

    fun testMetadataProbeHonorsCancellation() {
        try {
            NativeRawBridge.probeRaw(File(context.cacheDir, "raw-fixtures/DSC01452.ARW").path, RawCancellationProbe { true })
            fail("Metadata probe must propagate cancellation")
        } catch (_: CancellationException) { }
    }

    private fun expectUnsupported(message: String, operation: () -> Unit) {
        try { operation(); fail("RAW must report unsupported instead of falling back") }
        catch (failure: IOException) { assertTrue(failure.message, failure.message?.contains(message) == true) }
    }
}
