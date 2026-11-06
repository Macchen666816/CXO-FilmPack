package dev.cxo.filmpack.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import dev.cxo.filmpack.app.ui.CatalogGlassButton as Button
import android.widget.ImageView
import dev.cxo.filmpack.app.ui.CatalogGlassSlider as SeekBar
import android.widget.TextView

/** Platform-only UI coverage for the 0..200 film intensity control. */
class IntensityUiTest : ForegroundEditorTest() {
    private val timeoutMillis = 120_000L

    private fun expandControls(activity:Activity) {
        instrumentation.runOnMainSync { findTagged(activity,"editor_drawer_handle")!!.performClick() }
        assertTrue("operation drawer did not expand",waitUntil {
            findTagged(activity,"editor_drawer_scroll")?.visibility==View.VISIBLE
        })
    }

    fun testIntensityRangePreviewPixelsAndReset() {
        val activity = activity
        assertTrue("processor did not initialize", waitUntil {
            findTagged(activity, "choose_film")?.isEnabled == true
        })
        expandControls(activity)
        val slider = findTagged(activity, "intensity_slider") as SeekBar
        assertEquals(200, slider.max)
        assertEquals(100, slider.progress)
        assertEquals("强度 100", (findTagged(activity, "intensity_value") as TextView).text.toString())

        instrumentation.runOnMainSync {
            activity.loadPhoto(Uri.parse("content://dev.cxo.filmpack.app.test.photos/photo-mosaic.png"))
        }
        val preview = findTagged(activity, "photo_preview") as ImageView
        assertTrue("photo did not render", waitUntil {
            preview.drawable is BitmapDrawable && preview.contentDescription == "胶片效果预览"
        })
        val baseline = previewFingerprint(preview)

        // A real DPAD event is reported as fromUser=true by the glass slider, like keyboard and
        // accessibility actions. It must still update the label and submit a render.
        instrumentation.runOnMainSync { slider.requestFocus() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("keyboard intensity change was not applied", waitUntil {
            (findTagged(activity, "intensity_value") as TextView).text.toString() == "强度 101"
        })

        // 50 is a reliable pixel-affecting check; 200 is also a valid completed value,
        // but some identity-like film paths legitimately match 100 at that endpoint.
        instrumentation.runOnMainSync { slider.progress = 50 }
        assertTrue("strength 50 did not render a changed preview", waitUntil {
            (findTagged(activity, "intensity_value") as TextView).text.toString() == "强度 50" &&
                preview.contentDescription == "胶片效果预览" &&
                previewFingerprint(preview) != baseline
        })

        instrumentation.runOnMainSync {
            (findTagged(activity, "reset_intensity") as Button).performClick()
        }
        assertTrue("reset did not restore 100", waitUntil {
            slider.progress == 100 &&
                (findTagged(activity, "intensity_value") as TextView).text.toString() == "强度 100" &&
                preview.contentDescription == "胶片效果预览" &&
                previewFingerprint(preview) == baseline
        })

        instrumentation.runOnMainSync { slider.progress = 200 }
        assertTrue("strength 200 did not complete a preview", waitUntil {
            (findTagged(activity, "intensity_value") as TextView).text.toString() == "强度 200" &&
                preview.contentDescription == "胶片效果预览"
        })
    }

    fun testIntensitySavedAndRestored() {
        val activity = activity
        assertTrue("processor did not initialize", waitUntil {
            findTagged(activity, "choose_film")?.isEnabled == true
        })
        expandControls(activity)
        val slider = findTagged(activity, "intensity_slider") as SeekBar
        instrumentation.runOnMainSync { activity.loadPhoto(Uri.parse("content://dev.cxo.filmpack.app.test.photos/photo-mosaic.png")) }
        assertTrue(waitUntil { findTagged(activity,"export_photo")!!.isEnabled })
        instrumentation.runOnMainSync { slider.progress = 25 }
        assertTrue(waitUntil { findTagged(activity,"export_photo")!!.isEnabled })
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name,null,false)
        try {
            instrumentation.runOnMainSync { activity.recreate() }
            val restored = monitor.waitForActivityWithTimeout(30_000L) as? MainActivity
            assertNotNull("Activity was not recreated",restored)
            try {
                assertTrue(waitUntil { findTagged(restored!!,"export_photo")!!.isEnabled })
                assertEquals(25,(findTagged(restored!!,"intensity_slider") as SeekBar).progress)
                assertEquals("强度 25",(findTagged(restored,"intensity_value") as TextView).text.toString())
            } finally { if (restored != null) instrumentation.runOnMainSync { restored.finish() } }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    fun testSavedPhotoUsesChosenStrength() {
        val activity = activity
        assertTrue(waitUntil { findTagged(activity,"choose_film")!!.isEnabled })
        expandControls(activity)
        instrumentation.runOnMainSync { activity.loadPhoto(Uri.parse("content://dev.cxo.filmpack.app.test.photos/photo-mosaic.png")) }
        assertTrue(waitUntil { findTagged(activity,"export_photo")!!.isEnabled })
        val slider = findTagged(activity,"intensity_slider") as SeekBar
        instrumentation.runOnMainSync { slider.progress = 50 }
        assertTrue(waitUntil { findTagged(activity,"export_photo")!!.isEnabled })
        val expected = ((findTagged(activity,"photo_preview") as ImageView).drawable as BitmapDrawable).bitmap
        val destination = Uri.parse("content://dev.cxo.filmpack.app.test.photos/intensity-saved.jpg")
        val monitor = Instrumentation.ActivityMonitor(IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); addDataType("image/jpeg")
        },Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(destination)),true)
        instrumentation.addMonitor(monitor)
        try {
            instrumentation.runOnMainSync {
                (findTagged(activity,"export_photo") as Button).performClick()
                assertFalse("intensity must lock while choosing/saving",slider.isEnabled)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val field=MainActivity::class.java.getDeclaredField("exportSettingsDialog").apply {isAccessible=true}
                val dialog=field.get(activity) as android.app.AlertDialog
                requireNotNull(findTagged(dialog.window!!.decorView,"export_confirm")).performClick()
            }
            assertTrue(waitUntil { monitor.hits == 1 && findTagged(activity,"export_photo")!!.isEnabled })
            val actual = PhotoProcessor(instrumentation.targetContext).decodePreview(destination)
            try {
                assertEquals(expected.width,actual.width); assertEquals(expected.height,actual.height)
                var squared = 0.0; var count = 0
                for (y in 0 until actual.height step 8) for (x in 0 until actual.width step 8) {
                    val a = actual.getPixel(x,y); val b = expected.getPixel(x,y)
                    for (shift in intArrayOf(0,8,16)) {
                        val error = ((a ushr shift) and 255)-((b ushr shift) and 255)
                        squared += error.toDouble()*error; count++
                    }
                }
                assertTrue("saved strength differs from preview",kotlin.math.sqrt(squared/count) < 3.0)
            } finally { actual.recycle() }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.targetContext.contentResolver.delete(destination,null,null)
        }
    }

    private fun previewFingerprint(image: ImageView): Long {
        val bitmap = (image.drawable as BitmapDrawable).bitmap
        var fingerprint = 17L
        val stepX = maxOf(1, bitmap.width / 8)
        val stepY = maxOf(1, bitmap.height / 8)
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                fingerprint = fingerprint * 31L + bitmap.getPixel(x, y).toLong()
            }
        }
        return fingerprint
    }

    private fun findTagged(root: Activity, tag: String): View? = findTagged(root.window.decorView, tag)

    private fun findTagged(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findTagged(view.getChildAt(index), tag)?.let { return it }
        }
        return null
    }

    private fun waitUntil(description: String = "intensity operation", condition: () -> Boolean): Boolean {
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
