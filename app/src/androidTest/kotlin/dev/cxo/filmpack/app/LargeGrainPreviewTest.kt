package dev.cxo.filmpack.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import dev.cxo.filmpack.app.ui.CatalogGlassToggle as Switch
import dev.cxo.filmpack.core.NativeGrain8Geometry
import dev.cxo.filmpack.core.StreamingJpegWriter
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Requires the installed pack, or the same staged pack as GrainPackStoreTest.
 * Optional -e grainLargeSource <already-granted URI> tests an actual 8256x6192
 * source instead; the default JPEG is streamed with at most eight source rows. */
@Suppress("DEPRECATION")
class LargeGrainPreviewTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private var savedPreferences: Map<String, *> = emptyMap<String, Any>()

    override fun setUp() {
        super.setUp()
        val preferences = context.getSharedPreferences("photo-settings",0)
        savedPreferences = preferences.all.toMap()
        preferences.edit().clear().commit()
    }

    override fun tearDown() {
        try { super.tearDown() }
        finally {
            val editor = context.getSharedPreferences("photo-settings",0).edit().clear()
            for ((key,value) in savedPreferences) when (value) {
                is Boolean -> editor.putBoolean(key,value)
                is Int -> editor.putInt(key,value)
                is Long -> editor.putLong(key,value)
                is Float -> editor.putFloat(key,value)
                is String -> editor.putString(key,value)
                is Set<*> -> editor.putStringSet(key,value.filterIsInstance<String>().toSet())
            }
            editor.commit()
        }
    }

    private fun tagged(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) tagged(view.getChildAt(i),tag)?.let { return it }
        return null
    }

    private fun find(activity: Activity, tag: String) = requireNotNull(tagged(activity.window.decorView,tag))
    private fun field(activity: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.get(activity)
    }

    private fun <T> onMain(label: String, block: () -> T): T {
        val task = FutureTask(Callable { block() })
        check(Handler(Looper.getMainLooper()).post(task)) { "Cannot post UI action: $label" }
        try {
            return task.get(5,TimeUnit.SECONDS)
        } catch (failure: TimeoutException) {
            task.cancel(false)
            throw AssertionError("Timeout waiting for main thread: $label",failure)
        } catch (failure: ExecutionException) {
            throw requireNotNull(failure.cause)
        }
    }

    private fun launchActivity(monitor: Instrumentation.ActivityMonitor): MainActivity {
        // The legacy startActivitySync waits indefinitely and matches launch
        // intents exactly. A class monitor also accepts an external foreground launch.
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "-n dev.cxo.filmpack.app/.MainActivity"
        ).use {
            return monitor.waitForActivityWithTimeout(30_000) as? MainActivity
                ?: throw AssertionError("MainActivity did not start within 30 seconds")
        }
    }

    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime()+120_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(label,condition)) return
            SystemClock.sleep(80)
        }
        fail("Timeout: $label")
    }

    fun test51MpPhotoCanEnableNativeLevel2GrainAndExportOriginalSize() {
        val generated = File(context.cacheDir,"grain-large-51mp-source.jpg")
        val argument = (instrumentation as android.test.InstrumentationTestRunner).arguments.getString("grainLargeSource")
        val source = if (argument != null) Uri.parse(argument) else {
            generated.outputStream().use { output ->
                StreamingJpegWriter.write(output,8256,6192) { _,count,argb ->
                    argb.fill(Color.rgb(112,128,144),0,8256*count)
                }
            }
            Uri.fromFile(generated)
        }
        val destination = File(context.cacheDir,"grain-large-51mp-export.jpg")
        destination.delete()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name,null,false)
        var launchedActivity: MainActivity? = null
        try {
            GrainPackStore(context).use { store ->
                if (store.openInstalled().isEmpty()) {
                    val staged = File(context.getExternalFilesDir(null),"grain-product-input.cxograinpack")
                    assertTrue("Stage grain-product-input.cxograinpack or install the verified grain pack",staged.isFile)
                    assertEquals(99,store.importPack(Uri.fromFile(staged),{}, { false }).size)
                }
                val a = launchActivity(monitor)
                launchedActivity = a
                waitFor("initialization") { find(a,"choose_film").isEnabled }
                onMain("load 51MP photo") { a.loadPhoto(source) }
                waitFor("51MP photo without grain") { find(a,"export_photo").isEnabled }
                onMain("open grain dialog") { find(a,"grain_settings").performClick() }
                waitFor("grain dialog") { (field(a,"grainDialog") as? AlertDialog)?.isShowing == true }
                onMain("enable grain") {
                    val dialog = field(a,"grainDialog") as AlertDialog
                    (tagged(dialog.window!!.decorView,"grain_enabled") as Switch).isChecked = true
                    requireNotNull(tagged(dialog.window!!.decorView,"grain_apply")).performClick()
                }
                waitFor("51MP grain preview") { find(a,"export_photo").isEnabled }
                assertTrue(context.getSharedPreferences("photo-settings",0).getBoolean("grain.enabled",false))
                val preview = onMain("read grain preview") { field(a,"grainPreview") as PhotoGrainPreview }
                assertEquals(8256,preview.sourceWidth); assertEquals(6192,preview.sourceHeight)
                assertEquals(2,preview.invocationScale)
                val width = if (preview.sourceExifOrientation >= 5) 1548 else 2064
                val height = if (preview.sourceExifOrientation >= 5) 2064 else 1548
                assertEquals(width,preview.bitmap.width); assertEquals(height,preview.bitmap.height)
                val rendered = onMain("read rendered preview") {
                    ((find(a,"photo_preview") as ImageView).drawable as BitmapDrawable).bitmap
                }
                assertEquals(width,rendered.width); assertEquals(height,rendered.height)

                val engine = PhotoProcessor(context)
                // The old limits still reject this image; level3 remains unsupported.
                assertTrue(runCatching { engine.decodeGrainPreview(source,2048,2_000_000) }.exceptionOrNull() is IllegalArgumentException)
                val film = onMain("read selected film") { field(a,"selectedFilm") as FilmChoice }
                val withoutGrain = engine.render(preview.bitmap,film,{}, { false })
                try {
                    val expected = IntArray(width); val actual = IntArray(width)
                    var changed = false
                    for (y in 0 until height step maxOf(1,height/32)) {
                        withoutGrain.getPixels(expected,0,width,0,y,width,1)
                        rendered.getPixels(actual,0,width,0,y,width,1)
                        if (!expected.contentEquals(actual)) { changed = true; break }
                    }
                    assertTrue("Enabling grain must change preview pixels",changed)
                } finally { withoutGrain.recycle() }
                val request = engine.grainRequest(preview,film,"KodakPortra400",
                    NativeGrain8Geometry.Format.SMALL,1f,100,17)
                val previewLayer = store.prepare(request)
                assertEquals(width,previewLayer.width); assertEquals(height,previewLayer.height)
                val rejected = runCatching {
                    engine.export(source,film,Uri.fromFile(destination),{}, { false },100,previewLayer)
                }.exceptionOrNull()
                assertTrue("A preview layer must never silently reduce export dimensions",rejected is IllegalArgumentException)
                assertFalse(destination.exists())

                val fullRequest = engine.grainRequest(preview,film,"KodakPortra400",
                    NativeGrain8Geometry.Format.SMALL,1f,100,17,fullResolution=true)
                assertEquals(0,fullRequest.invocationScale)
                assertEquals(8256,fullRequest.sourceWidth); assertEquals(6192,fullRequest.sourceHeight)
                val fullLayer = store.prepare(fullRequest)
                val fullWidth = if (preview.sourceExifOrientation >= 5) 6192 else 8256
                val fullHeight = if (preview.sourceExifOrientation >= 5) 8256 else 6192
                assertEquals(fullWidth,fullLayer.width); assertEquals(fullHeight,fullLayer.height)
                val result = engine.export(source,film,Uri.fromFile(destination),{}, { false },100,fullLayer)
                assertFalse(result.resized)
                assertEquals(fullWidth,result.width); assertEquals(fullHeight,result.height)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(destination.path,bounds)
                assertEquals(8256,bounds.outWidth); assertEquals(6192,bounds.outHeight)
                android.util.Log.i("CXOGrainProduct","51MP grain export ${result.elapsedMillis}ms ${destination.length()}bytes")
            }
        } finally {
            try {
                launchedActivity?.let { a -> onMain("finish test activity") { a.finish() } }
            } finally {
                instrumentation.removeMonitor(monitor)
                if (argument == null) generated.delete()
            }
        }
    }
}
