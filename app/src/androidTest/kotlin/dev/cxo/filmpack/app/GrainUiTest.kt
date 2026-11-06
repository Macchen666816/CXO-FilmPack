package dev.cxo.filmpack.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.cxo.filmpack.app.ui.CatalogGlassButton as Button
import dev.cxo.filmpack.app.ui.CatalogGlassSlider as SeekBar
import dev.cxo.filmpack.app.ui.CatalogGlassToggle as Switch
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

class GrainUiTest : ForegroundEditorTest() {
    override fun setUp() {
        super.setUp()
        instrumentation.targetContext.getSharedPreferences("photo-settings",0).edit().clear().commit()
    }
    override fun tearDown() {
        instrumentation.targetContext.getSharedPreferences("photo-settings",0).edit().clear().commit()
        super.tearDown()
    }
    private fun tagged(view: View,tag: String): View? {
        if (view.tag==tag) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) tagged(view.getChildAt(i),tag)?.let { return it }
        return null
    }
    private fun find(activity: Activity,tag: String)=requireNotNull(tagged(activity.window.decorView,tag))
    private fun waitFor(label: String,condition: () -> Boolean) {
        val deadline=SystemClock.elapsedRealtime()+120_000
        while (SystemClock.elapsedRealtime()<deadline) {
            instrumentation.waitForIdleSync()
            var ready=false;instrumentation.runOnMainSync { ready=condition() }
            if (ready) return
            SystemClock.sleep(80)
        }
        fail("Timeout: $label")
    }
    private fun dialog(activity: MainActivity)=MainActivity::class.java.getDeclaredField("grainDialog").let {
        it.isAccessible=true;it.get(activity) as AlertDialog
    }
    private fun setGrain(activity: MainActivity,enabled: Boolean,intensity: Int=100) {
        instrumentation.runOnMainSync { find(activity,"grain_settings").performClick() }
        // Dialog onShow is posted to the main loop; allow it to install the
        // validating Apply listener before sending the next user action.
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val d=dialog(activity);val root=d.window!!.decorView
            (tagged(root,"grain_enabled") as Switch).isChecked=enabled
            (tagged(root,"grain_intensity") as SeekBar).progress=intensity
            (tagged(root,"grain_format") as Spinner).setSelection(3)
            (tagged(root,"grain_manual_size") as SeekBar).progress=1 // Manual1
            requireNotNull(tagged(root,"grain_apply")).performClick()
        }
        waitFor("grain render") { find(activity,"export_photo").isEnabled }
        assertEquals(enabled,instrumentation.targetContext.getSharedPreferences("photo-settings",0).getBoolean("grain.enabled",false))
    }
    private fun bitmap(activity: Activity)=((find(activity,"photo_preview") as ImageView).drawable as BitmapDrawable).bitmap
    private fun difference(a: Bitmap,b: Bitmap): Pair<Double,Int> {
        assertEquals(a.width,b.width);assertEquals(a.height,b.height)
        val aa=IntArray(a.width*a.height);val bb=IntArray(aa.size)
        a.getPixels(aa,0,a.width,0,0,a.width,a.height);b.getPixels(bb,0,b.width,0,0,b.width,b.height)
        var sum=0.0;var maximum=0
        for (i in aa.indices) for (s in intArrayOf(0,8,16)) {
            val delta=abs(((aa[i] ushr s) and 255)-((bb[i] ushr s) and 255))
            sum+=delta.toDouble()*delta;maximum=maxOf(maximum,delta)
        }
        return sqrt(sum/(aa.size.toLong()*3)) to maximum
    }

    fun testBundledGrainNativeReferenceSaveAndRecreate() {
        val a=activity
        waitFor("initialization") { find(a,"choose_film").isEnabled }
        val context=instrumentation.targetContext
        assertTrue((find(a,"bundled_grain_info") as TextView).text.contains("99"))
        instrumentation.runOnMainSync { a.loadPhoto(Uri.parse("content://dev.cxo.filmpack.app.test.photos/photo-mosaic.png")) }
        waitFor("baseline photo") { find(a,"export_photo").isEnabled }
        val noGrain=bitmap(a).copy(Bitmap.Config.ARGB_8888,false)!!
        setGrain(a,true)
        val actual=bitmap(a)
        val reference=instrumentation.context.assets.open("managed-grain-KodakPortra400-100.png").use { requireNotNull(BitmapFactory.decodeStream(it)) }
        try {
            assertTrue("UI grain did not change pixels",difference(actual,noGrain).first>.3)
            val (rmse,max)=difference(actual,reference)
            assertTrue("UI grain/reference mismatch $rmse/$max",rmse<.15 && max<=1)
            File(context.cacheDir,"product-ui-grain.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG,100,it) }
            val destination=Uri.parse("content://dev.cxo.filmpack.app.test.photos/product-ui-grain.jpg")
            val save=Instrumentation.ActivityMonitor(IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE);addDataType("image/jpeg")
            },Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(destination)),true)
            instrumentation.addMonitor(save)
            try {
                instrumentation.runOnMainSync {
                    find(a,"export_photo").performClick()
                    assertFalse(find(a,"grain_settings").isEnabled)
                    assertFalse("Bundled grain count is information, not an action",find(a,"bundled_grain_info").isClickable)
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val field=MainActivity::class.java.getDeclaredField("exportSettingsDialog").apply {isAccessible=true}
                    val dialog=field.get(a) as AlertDialog
                    requireNotNull(tagged(dialog.window!!.decorView,"export_confirm")).performClick()
                }
                waitFor("grain save") { save.hits==1 && find(a,"export_photo").isEnabled }
                val saved=requireNotNull(context.contentResolver.openInputStream(destination).use { BitmapFactory.decodeStream(it) })
                try { assertTrue(difference(saved,reference).first<4) } finally { saved.recycle() }
                context.contentResolver.openInputStream(destination)!!.use { input ->
                    File(context.cacheDir,"product-ui-grain.jpg").outputStream().use { input.copyTo(it) }
                }
            } finally { instrumentation.removeMonitor(save);context.contentResolver.delete(destination,null,null) }
            val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
            try {
                instrumentation.runOnMainSync { a.recreate() }
                val restored=monitor.waitForActivityWithTimeout(30_000) as MainActivity
                try {
                    waitFor("grain recreation") { find(restored,"export_photo").isEnabled }
                    assertTrue((find(restored,"grain_settings") as Button).text.contains("100"))
                    assertEquals(0,difference(bitmap(restored),actual).second)
                    setGrain(restored,true,0)
                    assertTrue("zero grain should match no grain",difference(bitmap(restored),noGrain).second<=1)
                    setGrain(restored,false)
                    assertEquals(0,difference(bitmap(restored),noGrain).second)
                } finally { instrumentation.runOnMainSync { restored.finish() } }
            } finally { instrumentation.removeMonitor(monitor) }
        } finally { reference.recycle();noGrain.recycle() }
    }

    fun testUnsupportedGrainPhotoCanRecoverByDisablingGrain() {
        // GrainPackStoreTest establishes the installed pack before this class.
        val a=activity
        waitFor("initialization") { find(a,"choose_film").isEnabled }
        instrumentation.runOnMainSync { a.loadPhoto(Uri.parse("content://dev.cxo.filmpack.app.test.photos/photo-mosaic.png")) }
        waitFor("photo") { find(a,"export_photo").isEnabled }
        setGrain(a,true)
        val context=instrumentation.targetContext
        val webp=File(context.cacheDir,"grain-unsupported.webp")
        val source=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888)
        try { source.eraseColor(android.graphics.Color.rgb(120,80,60));webp.outputStream().use { source.compress(Bitmap.CompressFormat.WEBP,100,it) } }
        finally { source.recycle() }
        instrumentation.runOnMainSync { a.loadPhoto(Uri.fromFile(webp)) }
        waitFor("unsupported grain decode ends") {
            val loading=MainActivity::class.java.getDeclaredField("loading").apply { isAccessible=true }.getBoolean(a)
            !loading && !find(a,"export_photo").isEnabled
        }
        setGrain(a,false)
        assertEquals(320,bitmap(a).width);assertEquals(180,bitmap(a).height)
    }
}
