package dev.cxo.filmpack.app

import android.net.Uri
import android.test.InstrumentationTestCase
import android.util.Log
import java.io.File
import java.security.MessageDigest

/** Device-only opt-in benchmark. Uses an already granted source, preserves it,
 * and keeps all outputs inside app cache. Not part of default correctness tests. */
@Suppress("DEPRECATION")
class ExportPerformanceTest : InstrumentationTestCase() {
    fun testK40RealPhotoExport() {
        val context = instrumentation.targetContext
        val arguments = (instrumentation as android.test.InstrumentationTestRunner).arguments
        val sourceUri = arguments.getString("perfSource") ?: return // explicit opt-in only
        val source = Uri.parse(sourceUri)
        val workers = arguments.getString("perfWorkers")?.toInt() ?: 4
        val native = arguments.getString("perfNative") != "false"
        val jpeg = arguments.getString("perfJpeg") != "false"
        val engine = PhotoProcessor(context,exportWorkers=workers,useNativeEntry=native,useNativeJpeg=jpeg)
        if (jpeg && arguments.getString("requireNative") == "true") assertTrue("ARM64 native JPEG must load",NativeJpegWriter.isSupported)
        if (native && arguments.getString("requireNative") == "true") assertTrue("ARM64 native entry must load",engine.nativeEntryEnabled)
        val film = engine.films.first { it.token == (arguments.getString("perfFilm") ?: "DigitalFujiMonochromeRedFilter") }
        val preview = engine.decodePreview(source)
        val previewStart = android.os.SystemClock.elapsedRealtime()
        val rendered = engine.render(preview,film,{}, { false })
        Log.i("CXOPerf", "PREVIEW ${preview.width}x${preview.height} elapsedMs=${android.os.SystemClock.elapsedRealtime()-previewStart}")
        rendered.recycle(); preview.recycle()
        val output = File(context.cacheDir,"k40-perf-latest.jpg")
        val result = engine.export(source,film,Uri.fromFile(output),{}, { false })
        arguments.getString("perfWidth")?.let { assertEquals(it.toInt(),result.width) }
        arguments.getString("perfHeight")?.let { assertEquals(it.toInt(),result.height) }
        assertFalse(result.resized)
        val hash = MessageDigest.getInstance("SHA-256")
        output.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) { val n = input.read(bytes); if (n < 0) break; hash.update(bytes,0,n) }
        }
        Log.i("CXOPerf", "RESULT workers=$workers nativeEntry=${engine.nativeEntryEnabled} nativeJpeg=${jpeg && NativeJpegWriter.isSupported} elapsedMs=${result.elapsedMillis} bytes=${output.length()} sha256=" +
            hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }
}
