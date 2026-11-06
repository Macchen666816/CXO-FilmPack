package dev.cxo.filmpack.app

import android.net.Uri
import android.content.Intent
import android.os.SystemClock
import android.os.Bundle
import android.test.InstrumentationTestCase
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Opt-in product benchmark. All sources and results stay in the app's cache. */
@Suppress("DEPRECATION")
class PipelinePerformanceTest : InstrumentationTestCase() {
    fun testStagedPhotoWorkflow() {
        val args = (instrumentation as android.test.InstrumentationTestRunner).arguments
        val name = args.getString("perfFixture") ?: return
        val token = args.getString("perfFilm") ?: "KodakEliteColor400"
        val context = instrumentation.targetContext
        val uri = if (args.getString("perfProvider") == "true") {
            Uri.parse("content://dev.cxo.filmpack.app.test.photos/$name")
        } else Uri.fromFile(File(context.cacheDir, "raw-fixtures/$name").also { assertTrue(it.isFile) })
        val label = args.getString("perfLabel") ?: "unlabelled"
        val results = JSONObject().put("label", label).put("fixture", name).put("film", token)
        // ColorOS can freeze instrumentation with no visible activity. Keep the
        // app in the foreground, as it is during an actual interactive export.
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
        PhotoProcessor(context, exportWorkers = args.getString("perfWorkers")?.toInt() ?: 4).use { engine ->
            val film = engine.films.single { it.token == token }
            val started = SystemClock.elapsedRealtime()
            val original = engine.decodePreview(uri)
            results.put("importMs", SystemClock.elapsedRealtime() - started)
            Log.i("CXOPerf", "WORKFLOW_STAGE $label $name imported ${results.getLong("importMs")}ms")
            try {
                for (pass in 1..2) {
                    val tick = SystemClock.elapsedRealtime()
                    val rendered = engine.render(original, film, {}, { false }, rawUri = uri)
                    try {
                        results.put("filmPreview${pass}Ms", SystemClock.elapsedRealtime() - tick)
                        Log.i("CXOPerf", "WORKFLOW_STAGE $label $name preview$pass ${results.getLong("filmPreview${pass}Ms")}ms")
                        results.put("previewWidth", rendered.width).put("previewHeight", rendered.height)
                        val pixels = IntArray(rendered.width * rendered.height)
                        rendered.getPixels(pixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)
                        val hash = MessageDigest.getInstance("SHA-256")
                        for (pixel in pixels) {
                            hash.update((pixel ushr 16).toByte()); hash.update((pixel ushr 8).toByte()); hash.update(pixel.toByte())
                        }
                        results.put("preview${pass}Sha256", hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                    } finally { rendered.recycle() }
                }
            } finally { original.recycle() }
            val output = File(context.cacheDir, "perf-$label-$name-$token.jpg")
            val result = engine.export(uri, film, Uri.fromFile(output), {}, { false })
            assertFalse(result.resized)
            results.put("exportMs", result.elapsedMillis).put("width", result.width).put("height", result.height)
            results.put("bytes", output.length())
            val hash = MessageDigest.getInstance("SHA-256")
            output.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }
            results.put("exportSha256", hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        }
        val report = File(context.cacheDir, "perf-$label-$name-$token.json")
        report.writeText(results.toString(2))
        Log.i("CXOPerf", "WORKFLOW $results")
        // Non-debuggable preview builds do not allow adb run-as. Return the same
        // measurements directly through the instrumentation channel instead.
        instrumentation.sendStatus(2, Bundle().apply { putString("cxoPerf", results.toString()) })
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
