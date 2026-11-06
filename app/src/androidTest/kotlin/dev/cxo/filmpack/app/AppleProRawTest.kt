package dev.cxo.filmpack.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import dev.cxo.filmpack.app.post.PostEditingSettings
import dev.cxo.filmpack.core.NativeGrain8Geometry
import dev.cxo.filmpack.core.PostProcessSettings
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/** Real Apple Linear DNG regression. Stage IMG_3046.DNG in /data/local/tmp. */
@Suppress("DEPRECATION")
class AppleProRawTest : ForegroundEditorTest() {
    private val context get() = instrumentation.targetContext
    private fun fixture(): File {
        activity
        return File(context.cacheDir, "IMG_3046.DNG").also { file ->
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cat /data/local/tmp/IMG_3046.DNG")).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            assertEquals(57_349_142L, file.length())
        }
    }

    fun testActualImportFilmAndFullResolutionExports() {
        val source = fixture()
        val uri = Uri.fromFile(source)
        val out = File(context.getExternalFilesDir(null), "apple-proraw-regression").apply { mkdirs() }
        try {
            RawPhotoStore(context).use { store ->
                requireNotNull(store.openAppleProRaw(uri)).use { raw ->
                    assertEquals(8064, raw.width); assertEquals(6048, raw.height)
                    assertEquals(6, raw.orientation)
                    assertEquals("prophoto_linear_q15", raw.colorDomain)
                    assertEquals(1.013671875, raw.metadata.getDouble("dngBaselineScale"), 0.0)
                    assertTrue(raw.metadata.getDouble("dngTemperature") in 5500.0..5700.0)
                    val row = ShortArray(raw.width * 3)
                    raw.readRows(raw.height / 2, 1, row)
                    assertTrue("Linear cache must stay in the signed Q15 RAW domain", row.all { it >= 0 })
                    assertTrue("Retain real RGB16 precision", row.count { (it.toInt() and 65535) % 257 != 0 } > row.size / 2)
                }
                // A cached reopen must not call the SDK or the cancellation probe many times.
                val calls = AtomicInteger()
                requireNotNull(store.openAppleProRaw(uri) { calls.incrementAndGet() > 2 }).close()
                assertTrue(calls.get() <= 2)
            }
            PhotoProcessor(context).use { engine ->
                val original = engine.decodePreview(uri)
                try {
                    assertEquals(960, original.width); assertEquals(1280, original.height)
                    File(out, "original.png").outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    for (token in listOf("KodakPortra400", "KodakEliteColor400")) {
                        val film = engine.films.single { it.token == token }
                        val rendered = engine.render(original, film, {}, { false }, rawUri = uri)
                        try {
                            assertEquals(original.width, rendered.width); assertEquals(original.height, rendered.height)
                            assertFalse("Film must change the actual image", rendered.sameAs(original))
                            File(out, "$token.png").outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        } finally { rendered.recycle() }
                    }
                    val film = engine.films.single { it.token == "KodakPortra400" }
                    for (format in ExportFormat.entries) {
                        val dest = File(out, "portra400.${format.extension}")
                        val result = engine.export(uri, film, Uri.fromFile(dest), {}, { false },
                            settings = ExportSettings(format = format))
                        assertEquals(6048, result.width); assertEquals(8064, result.height)
                        assertTrue(dest.length() > 1024)
                        instrumentation.sendStatus(2, Bundle().apply {
                            putString("appleProRawExport", "$format ${result.width}x${result.height} bytes=${dest.length()} ms=${result.elapsedMillis}")
                        })
                    }
                } finally { original.recycle() }
            }
        } finally { source.delete() }
    }

    fun testGrainCoordinatesPostEditingAndReducedExport() {
        val source = fixture()
        val uri = Uri.fromFile(source)
        try {
            PhotoProcessor(context).use { engine ->
                GrainPackStore(context).use { grains ->
                    val ids = grains.openInstalled()
                    val preview = engine.decodeGrainPreview(uri)
                    try {
                        assertEquals(2, preview.invocationScale)
                        assertEquals(1512, preview.bitmap.width); assertEquals(2016, preview.bitmap.height)
                        val film = engine.films.single { it.token == "KodakPortra400" }
                        val settings = GrainSettings(enabled = true)
                        val id = settings.resolve(film, ids)
                        val request = engine.grainRequest(preview, film, id, NativeGrain8Geometry.Format.SMALL,
                            1f, 60, 1234)
                        val layer = grains.prepare(request)
                        val post = PostEditingSettings(development = PostProcessSettings(exposureEv = 0.2f, contrast = 5))
                        val rendered = engine.render(preview.bitmap, film, {}, { false }, grain = layer, rawUri = uri, post = post)
                        try {
                            assertEquals(1512, rendered.width); assertEquals(2016, rendered.height)
                        } finally { rendered.recycle() }
                        val fullLayer = grains.prepare(engine.grainRequest(preview, film, id,
                            NativeGrain8Geometry.Format.SMALL, 1f, 60, 1234, fullResolution = true))
                        val dest = File(context.cacheDir, "apple-grain-reduced.jpg")
                        try {
                            val result = engine.export(uri, film, Uri.fromFile(dest), {}, { false },
                                grain = fullLayer, post = post, settings = ExportSettings(maxLongEdge = 640))
                            assertEquals(480, result.width); assertEquals(640, result.height)
                            assertTrue(dest.length() > 1024)
                        } finally { dest.delete() }
                    } finally { preview.bitmap.recycle() }
                }
            }
        } finally { source.delete() }
    }

    fun testEditorLoadsAppleDngThroughNormalImport() {
        val source = fixture()
        val editor = activity
        try {
            instrumentation.runOnMainSync { editor.loadPhoto(Uri.fromFile(source)) }
            val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
            var status = ""
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    val root = editor.window.decorView
                    fun text(view: android.view.View): String = when (view) {
                        is android.widget.TextView -> view.text.toString()
                        is android.view.ViewGroup -> (0 until view.childCount).joinToString("\n") { text(view.getChildAt(it)) }
                        else -> ""
                    }
                    status = text(root)
                }
                if (status.contains("预览已就绪") || status.contains("失败") || status.contains("无法读取")) break
                Thread.sleep(100)
            }
            assertTrue(status, status.contains("预览已就绪"))
        } finally { source.delete() }
    }

    fun testNativeCancellationRemovesPartialRgb16() {
        val source = fixture()
        val output = File(context.cacheDir, "apple-cancel.rgb16le")
        val calls = AtomicInteger()
        try {
            NativeRawBridge.decodeAppleProRaw(source.path, output.path, RawCancellationProbe {
                calls.incrementAndGet() > 12
            })
            fail("SDK rendering must observe cancellation")
        } catch (_: CancellationException) {
            assertTrue(calls.get() > 12)
            assertFalse("Cancelled SDK output must be removed", output.exists())
        } finally { source.delete(); output.delete() }
    }
}
