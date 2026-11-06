package dev.cxo.filmpack.app

import android.net.Uri
import android.os.Bundle
import dev.cxo.filmpack.core.NativeGrain8Geometry
import java.io.File
import java.util.concurrent.CancellationException
import android.graphics.BitmapFactory
import dev.cxo.filmpack.android.ExifOrientation

/** Real-device check for the HEIF -> cache JPEG grain compatibility bridge. */
@Suppress("DEPRECATION")
class HeicGrainCompatibilityTest : ForegroundEditorTest() {
    fun testHeicGrainPreviewUsesCompatibilityBridge() {
        val args = (instrumentation as android.test.InstrumentationTestRunner).arguments
        val name = args.getString("heicFixture") ?: return
        require(name.matches(Regex("[A-Za-z0-9._-]+")))
        activity
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, name)
        val destination = File(context.cacheDir, "heic-grain-export.jpg")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "cat /data/local/tmp/$name")).use { input -> source.outputStream().use { input.copyTo(it) } }
        check(source.length() > 1024)
        try {
            PhotoProcessor(context).use { engine ->
                val uri = Uri.fromFile(source)
                val ordinary = engine.decodePreview(uri)
                val preview = engine.decodeGrainPreview(uri)
                try {
                    check(ordinary.width > 0 && ordinary.height > 0)
                    check(preview.bitmap.width > 0 && preview.bitmap.height > 0)
                    check(kotlin.math.abs(preview.sourceWidth.toLong() * ordinary.height -
                        preview.sourceHeight.toLong() * ordinary.width) < preview.sourceWidth.toLong() * ordinary.height / 100)
                    val film = engine.films.first { it.token == "KodakPortra400" }
                    GrainPackStore(context).use { grains ->
                        val previewRequest = engine.grainRequest(preview, film, "KodakPortra400",
                            NativeGrain8Geometry.Format.MANUAL, 1f, 100, 117)
                        val previewLayer = grains.prepare(previewRequest)
                        val withGrain = engine.render(preview.bitmap, film, {}, { false }, grain = previewLayer)
                        val withoutGrain = engine.render(preview.bitmap, film, {}, { false })
                        try { check(!withGrain.sameAs(withoutGrain)) { "Grain must change preview pixels" } }
                        finally { withGrain.recycle(); withoutGrain.recycle() }
                        val fullRequest = engine.grainRequest(preview, film, "KodakPortra400",
                            NativeGrain8Geometry.Format.MANUAL, 1f, 100, 117, fullResolution = true)
                        val fullLayer = grains.prepare(fullRequest)
                        val exported = engine.export(uri, film, Uri.fromFile(destination), {}, { false },
                            grain = fullLayer, settings = ExportSettings(ExportFormat.JPEG, 90, 1024))
                        check(exported.width == 1024 && exported.height == 683)
                    }
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString("cxoHeicGrain", "{\"fixture\":\"$name\",\"previewWidth\":${ordinary.width},\"previewHeight\":${ordinary.height},\"grainWidth\":${preview.bitmap.width},\"grainHeight\":${preview.bitmap.height},\"sourceWidth\":${preview.sourceWidth},\"sourceHeight\":${preview.sourceHeight},\"bridge\":\"temporary-srgb-jpeg\"}")
                    })
                } finally { ordinary.recycle(); preview.bitmap.recycle() }
            }
        } finally { source.delete(); destination.delete() }
    }
    fun testHeicCacheRefreshCancellationAndFullSizeTiff() {
        val args = (instrumentation as android.test.InstrumentationTestRunner).arguments
        val name = args.getString("heicSmallFixture") ?: return
        require(name.matches(Regex("[A-Za-z0-9._-]+")))
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "heif-small-opaque.bin")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "cat /data/local/tmp/$name")).use { input -> source.outputStream().use { input.copyTo(it) } }
        val target = File(context.cacheDir, "heif-small-full.tif")
        try {
            val uri = Uri.fromFile(source)
            HeifJpegStore(context).use { store ->
                val normalized = requireNotNull(store.normalize(uri) { false })
                val cached = File(requireNotNull(normalized.path))
                val originalBytes = cached.readBytes()
                assertEquals(normalized, store.normalize(uri) { false })
                assertTrue(originalBytes.contentEquals(cached.readBytes()))
                cached.inputStream().use { assertEquals(1, ExifOrientation.readJpeg(it)) }
                store.invalidate(); assertFalse(cached.exists())
                var calls = 0
                try { store.normalize(uri) { ++calls > 5 }; fail("Cancel row encoding") }
                catch (_: CancellationException) { }
                val root = File(context.cacheDir, "heif-jpeg")
                assertFalse(root.walkTopDown().any { it.isFile && it.name.startsWith("cxo-heif-") })
                assertNotNull(store.normalize(uri) { false })
            }
            PhotoProcessor(context).use { engine ->
                val preview = engine.decodeGrainPreview(uri)
                try {
                    assertEquals(1, preview.sourceExifOrientation)
                    val film = engine.films.first { it.token == "KodakPortra400" }
                    GrainPackStore(context).use { grains ->
                        val layer = grains.prepare(engine.grainRequest(preview, film, "KodakPortra400",
                            NativeGrain8Geometry.Format.MANUAL, 1f, 100, 117, fullResolution = true))
                        val exported = engine.export(uri, film, Uri.fromFile(target), {}, { false }, grain = layer,
                            settings = ExportSettings(ExportFormat.TIFF))
                        assertEquals(preview.sourceWidth, exported.width)
                        assertEquals(preview.sourceHeight, exported.height)
                        assertFalse(exported.resized)
                        assertTrue(target.length() > exported.width.toLong() * exported.height * 3)
                    }
                    engine.beginImport()
                    instrumentation.context.assets.open("orientation6.jpg").use { input -> source.outputStream().use { input.copyTo(it) } }
                    val fresh = engine.decodePreview(uri)
                    try { assertEquals(40, fresh.width); assertEquals(80, fresh.height) }
                    finally { fresh.recycle() }
                } finally { preview.bitmap.recycle() }
            }
        } finally { source.delete(); target.delete() }
    }

}
