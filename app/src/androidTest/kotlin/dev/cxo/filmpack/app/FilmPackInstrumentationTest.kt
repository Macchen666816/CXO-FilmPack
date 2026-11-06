package dev.cxo.filmpack.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import dev.cxo.filmpack.app.ui.CatalogGlassButton as Button
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.EditText
import android.widget.ListView
import java.io.File
import java.util.concurrent.CancellationException

/** Product-path tests. They intentionally use the platform runner only. */
class FilmPackInstrumentationTest :
    ForegroundEditorTest() {

    private val timeoutMillis = 120_000L

    fun testAllFilmsLoadTheirProductionAssetsAndRenderOpaquePixels() {
        val processor = PhotoProcessor(instrumentation.targetContext)
        assertEquals(153, processor.films.size)
        assertEquals(146, processor.films.count { !it.experimental })
        assertEquals(7, processor.films.count { it.experimental })

        val decoded = processor.decodePreview(fixtureUri())
        val source = Bitmap.createScaledBitmap(decoded, 32, 16, true)
        decoded.recycle()
        try {
            processor.films.forEach { film ->
              for (intensity in listOf(0,100,200)) {
                val rendered = processor.render(source, film, {}, { false },intensity)
                try {
                    assertTrue("${film.token} rendered no pixels", rendered.width > 0 && rendered.height > 0)
                    val pixels = IntArray(rendered.width * rendered.height)
                    rendered.getPixels(pixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)
                    assertTrue("${film.token} rendered transparent pixels", pixels.all { (it ushr 24) == 0xFF })
                    assertTrue("${film.token} rendered a blank image", pixels.any { (it and 0x00FFFFFF) != 0 })
                } finally {
                    rendered.recycle()
                }
              }
            }
        } finally {
            source.recycle()
        }
    }

    fun testRealPhotoActivityImportCompareAndExportCancellationRecovery() {
        val activity = activity
        assertTrue("processor did not initialize", waitUntil {
            findTagged(activity, "choose_film")?.isEnabled == true
        })

        val sourceUri = Uri.parse("content://dev.cxo.filmpack.app.test.photos/photo-mosaic.png")
        val importMonitor = Instrumentation.ActivityMonitor(
            IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("*/*")
            },
            Instrumentation.ActivityResult(Activity.RESULT_OK,
                Intent().setData(sourceUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)), true)
        instrumentation.addMonitor(importMonitor)
        try {
            instrumentation.runOnMainSync { findTagged(activity, "import_photo")!!.performClick() }
        } finally { instrumentation.removeMonitor(importMonitor) }
        assertEquals(1, importMonitor.hits)
        val preview = findTagged(activity, "photo_preview") as ImageView
        assertTrue("photo did not decode and render", waitUntil {
            preview.drawable != null && preview.contentDescription == "胶片效果预览"
        })
        // Original comparison is a photo hold gesture; no visible compare button remains.
        val downTime = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
                preview.width / 2f, preview.height / 2f, 0).let { event ->
                try { preview.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        try {
            // Sleep on the instrumentation thread so the main-loop 500ms callback can run.
            SystemClock.sleep(550)
            assertTrue("photo hold did not show original", waitUntil {
                preview.contentDescription == "原图预览"
            })
        } finally {
            instrumentation.runOnMainSync {
                MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                    preview.width / 2f, preview.height / 2f, 0).let { event ->
                    try { preview.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
        }
        assertTrue("hold release did not return to effect", waitUntil {
            preview.contentDescription == "胶片效果预览"
        })

        // Inspect only the app's own dialog field; no hidden Android window APIs.
        instrumentation.runOnMainSync { findTagged(activity, "choose_film")!!.performClick() }
        val dialog = MainActivity::class.java.getDeclaredField("filmDialog").let {
            it.isAccessible = true
            it.get(activity) as AlertDialog
        }
        assertTrue("film sheet did not attach", waitUntil {
            findType(dialog.window!!.decorView, EditText::class.java) != null
        })
        val search = findType(dialog.window!!.decorView, EditText::class.java)!!
        val results = findType(dialog.window!!.decorView, ListView::class.java)!!
        instrumentation.runOnMainSync { search.setText("Kodak EIR") }
        instrumentation.waitForIdleSync()
        assertEquals(1, results.adapter.count)
        instrumentation.runOnMainSync { results.performItemClick(results.getChildAt(0), 0, 0) }
        assertTrue("film selection did not update preview", waitUntil {
            (findTagged(activity, "choose_film") as Button).text.contains("Kodak EIR") &&
                preview.contentDescription == "胶片效果预览"
        })

        val outputUri = Uri.parse("content://dev.cxo.filmpack.app.test.photos/activity-export.jpg")
        instrumentation.targetContext.contentResolver.delete(outputUri, null, null)
        val monitor = Instrumentation.ActivityMonitor(
            IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("image/jpeg")
            },
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(outputUri)),
            true
        )
        instrumentation.addMonitor(monitor)
        try {
            val export = findTagged(activity, "export_photo")
            assertNotNull(export)
            activity.runOnUiThread { export!!.performClick() }
            assertTrue("ACTION_CREATE_DOCUMENT was not intercepted", waitUntil {
                monitor.hits == 1 && findTagged(activity, "export_photo")!!.isEnabled
            })
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        val exported = instrumentation.targetContext.contentResolver.openInputStream(outputUri)!!.use {
            BitmapFactory.decodeStream(it)
        }
        assertNotNull("export was not a decodable JPEG", exported)
        assertEquals(1024, exported!!.width)
        assertEquals(1024, exported.height)
        assertFalse("JPEG export unexpectedly has alpha", exported.hasAlpha())
        exported.recycle()
        instrumentation.targetContext.contentResolver.delete(outputUri, null, null)

        val processor = PhotoProcessor(instrumentation.targetContext)
        val source = processor.decodePreview(sourceUri)
        val cancelledOutput = File(instrumentation.targetContext.cacheDir, "instrumented-cancelled.jpg")
        cancelledOutput.delete()
        cancelledOutput.writeBytes(byteArrayOf(1, 2, 3))
        val cancelled = booleanArrayOf(false)
        try {
            try {
                processor.export(
                    sourceUri,
                    processor.films.first { it.token == "KodakPortra400" },
                    Uri.fromFile(cancelledOutput),
                    { value -> if (value >= 10) cancelled[0] = true },
                    { cancelled[0] }
                )
                fail("cancelled export unexpectedly completed")
            } catch (_: CancellationException) {
                // Expected: PhotoProcessor must remove a partially-created output.
            }
            assertFalse("cancelled export left output behind", cancelledOutput.exists())
            val recovered = processor.render(source, processor.films.first(), {}, { false })
            recovered.recycle()
        } finally {
            source.recycle()
            cancelledOutput.delete()
        }
    }

    fun testRepresentativeFilmsWritePullablePngs() {
        val processor = PhotoProcessor(instrumentation.targetContext)
        val source = processor.decodePreview(fixtureUri())
        val tokens = listOf(
            "KodakPortra400", "KodakEIR", "FujiAstia100", "Astia", "FujiSuperia200E6",
            "KodakTriX400", "KodakHIEFiltre", "RolleiIR400"
        )
        try {
            tokens.forEach { token ->
                val film = processor.films.firstOrNull { it.token == token }
                assertNotNull("missing representative film token $token", film)
                val selected = film ?: return@forEach
                val rendered = processor.render(source, selected, {}, { false })
                try {
                    val output = File(instrumentation.targetContext.cacheDir, "quality-$token.png")
                    output.delete()
                    output.outputStream().use { stream ->
                        assertTrue("$token PNG encode failed", rendered.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    }
                    assertTrue("$token PNG was not written", output.isFile && output.length() > 0L)
                } finally {
                    rendered.recycle()
                }
            }
        } finally {
            source.recycle()
        }
    }

    private fun fixtureUri(): Uri {
        val target = File(instrumentation.targetContext.cacheDir, "instrumented-photo-mosaic.png")
        if (!target.isFile || target.length() == 0L) {
            instrumentation.context.assets.open("photo-mosaic.png").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return Uri.fromFile(target)
    }

    private fun findTagged(root: Activity, tag: String): View? = findTagged(root.window.decorView, tag)

    private fun findTagged(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findTagged(view.getChildAt(index), tag)?.let { return it }
        }
        return null
    }

    private fun <T : View> findType(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findType(view.getChildAt(index), type)?.let { return it }
        }
        return null
    }

    private fun waitUntil(description: String = "product operation", condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return true
            SystemClock.sleep(100)
        }
        fail("timed out: $description")
        return false
    }
}

