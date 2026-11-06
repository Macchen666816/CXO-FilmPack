package dev.cxo.filmpack.app

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.cxo.filmpack.app.ui.CatalogGlassButton as Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.LinearLayout
import dev.cxo.filmpack.app.ui.CatalogControl
import java.io.File

@Suppress("DEPRECATION")
class EditorWorkbenchTest : ForegroundEditorTest() {
    private fun find(root:View,tag:String):View? {
        if(root.tag==tag)return root
        if(root is ViewGroup)for(i in 0 until root.childCount)find(root.getChildAt(i),tag)?.let{return it}
        return null
    }
    private fun allGlass(root:View):List<CatalogControl> = buildList {
        if(root is CatalogControl)add(root)
        if(root is ViewGroup)for(i in 0 until root.childCount)addAll(allGlass(root.getChildAt(i)))
    }
    private fun <T:View> type(root:View,klass:Class<T>):T? {
        if(klass.isInstance(root))return klass.cast(root)
        if(root is ViewGroup)for(i in 0 until root.childCount)type(root.getChildAt(i),klass)?.let{return it}
        return null
    }
    private fun waitFor(label:String,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+120_000
        while(SystemClock.elapsedRealtime()<end) {
            instrumentation.waitForIdleSync()
            var done=false;instrumentation.runOnMainSync {done=condition()}
            if(done)return
            SystemClock.sleep(70)
        }
        fail("Timed out: $label")
    }
    private fun screenshot(name:String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1000) // Settle window entry/exit and device rotation animations.
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        try {
            // Instrumentation executes with the target UID. Use the test-only
            // provider to write its own cache, also for non-debuggable preview.
            instrumentation.targetContext.contentResolver.openOutputStream(
                Uri.parse("content://dev.cxo.filmpack.app.test.photos/ui-review-$name.png"),"w"
            )!!.use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        } finally {bitmap.recycle()}
    }
    private fun arg(name:String):String? = (instrumentation as android.test.InstrumentationTestRunner).arguments.getString(name)

    fun testWorkbenchBundledGrainRealThumbnailsCompareFavoriteSheetsAndRotation() {
        val prefs=instrumentation.targetContext.getSharedPreferences("photo-settings",0)
        val oldEnabled=prefs.getBoolean("grain.enabled",false)
        val oldFavorites=prefs.getStringSet("film.favorites",emptySet()).orEmpty().toSet()
        prefs.edit().putBoolean("grain.enabled",false).putStringSet("film.favorites",emptySet()).commit()
        val a=activity
        try {
            waitFor("initialization") {find(a.window.decorView,"choose_film")?.isEnabled==true}
            assertTrue((find(a.window.decorView,"bundled_grain_info") as TextView).text.contains("99"))
            val uri=Uri.parse(arg("photoUri") ?: "content://dev.cxo.filmpack.app.test.photos/photo-mosaic.png")
            instrumentation.runOnMainSync {a.loadPhoto(uri)}
            waitFor("photo") {find(a.window.decorView,"export_photo")?.isEnabled==true}
            waitFor("visible film thumbnails") {
                val cache=MainActivity::class.java.getDeclaredField("thumbnailCache").apply {isAccessible=true}.get(a) as Map<*,*>
                cache.size>=3
            }
            val photo=find(a.window.decorView,"photo_preview") as ImageView
            val bitmap=(photo.drawable as BitmapDrawable).bitmap
            val before=bitmap.getPixel(bitmap.width/2,bitmap.height/2)
            waitFor("original Catalog controls") {
                val panels=allGlass(a.window.decorView)
                panels.size>=6 && panels.all {it.componentImplementation=="Backdrop Catalog 2.0.1"}
            }
            assertNull("Explicit compare button should be removed",find(a.window.decorView,"compare_photo"))
            assertNull("Duplicate film picker should be removed",find(a.window.decorView,"browse_films"))
            assertNull("Bottom navigation is reserved for future content",find(a.window.decorView,"editor_bottom_tabs"))
            val tools=find(a.window.decorView,"editor_tools")!!
            assertNull("Large enclosing tools card should be removed",tools.background)
            assertEquals("All films must be horizontally browsable",153,(find(a.window.decorView,"film_strip_items") as LinearLayout).childCount)
            assertEquals("Glass changed photo bitmap",before,bitmap.getPixel(bitmap.width/2,bitmap.height/2))
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
            val screen=instrumentation.uiAutomation.takeScreenshot()!!
            try {
                val location=IntArray(2)
                instrumentation.runOnMainSync {photo.getLocationOnScreen(location)}
                val pixel=screen.getPixel(location[0]+2,location[1]+2)
                for(shift in intArrayOf(0,8,16))assertTrue("Focus overlay tinted photo canvas",kotlin.math.abs(((pixel ushr shift) and 255)-((dev.cxo.filmpack.app.ui.EditorPalette.background ushr shift) and 255))<=2)
            } finally {screen.recycle()}
            screenshot("editor-portrait")
            val downTime=SystemClock.uptimeMillis()
            instrumentation.runOnMainSync {
                val down=MotionEvent.obtain(downTime,downTime,MotionEvent.ACTION_DOWN,photo.width/2f,photo.height/2f,0)
                photo.dispatchTouchEvent(down);down.recycle()
                assertEquals("胶片效果预览",photo.contentDescription)
            }
            SystemClock.sleep(250)
            instrumentation.runOnMainSync {assertEquals("A short touch must not compare","胶片效果预览",photo.contentDescription)}
            SystemClock.sleep(300)
            instrumentation.runOnMainSync {
                assertEquals("原图预览",photo.contentDescription)
                val up=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,photo.width/2f,photo.height/2f,0)
                photo.dispatchTouchEvent(up);up.recycle()
                assertEquals("胶片效果预览",photo.contentDescription)
                (find(a.window.decorView,"favorite_film") as Button).performClick()
                assertEquals("★",(find(a.window.decorView,"favorite_film") as Button).text.toString())
                (find(a.window.decorView,"favorite_film") as Button).performClick()
                (find(a.window.decorView,"choose_film") as Button).performClick()
            }
            instrumentation.waitForIdleSync()
            screenshot("film-library")
            val filmDialog=MainActivity::class.java.getDeclaredField("filmDialog").apply {isAccessible=true}.get(a) as AlertDialog
            instrumentation.runOnMainSync {type(filmDialog.window!!.decorView,EditText::class.java)!!.setText("Kodak EIR")}
            instrumentation.waitForIdleSync()
            assertEquals(1,type(filmDialog.window!!.decorView,ListView::class.java)!!.adapter.count)
            instrumentation.runOnMainSync {filmDialog.dismiss();(find(a.window.decorView,"grain_settings") as Button).performClick()}
            instrumentation.waitForIdleSync()
            screenshot("grain-settings")
            val grainDialog=MainActivity::class.java.getDeclaredField("grainDialog").apply {isAccessible=true}.get(a) as AlertDialog
            instrumentation.runOnMainSync {grainDialog.dismiss();a.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            waitFor("landscape") {a.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE && find(a.window.decorView,"export_photo")?.isEnabled==true}
            screenshot("editor-landscape")
            instrumentation.runOnMainSync {(find(a.window.decorView,"choose_film") as Button).performClick()}
            instrumentation.waitForIdleSync()
            val landscapeDialog=MainActivity::class.java.getDeclaredField("filmDialog").apply {isAccessible=true}.get(a) as AlertDialog
            val list=type(landscapeDialog.window!!.decorView,ListView::class.java)!!
            instrumentation.runOnMainSync {list.setSelection(list.adapter.count-1)}
            waitFor("landscape last film reachable") {list.lastVisiblePosition==list.adapter.count-1 && list.childCount>0}
            screenshot("landscape-film-library")
            instrumentation.runOnMainSync {
                val rect=android.graphics.Rect();landscapeDialog.window!!.decorView.getWindowVisibleDisplayFrame(rect)
                val last=list.getChildAt(list.childCount-1);val loc=IntArray(2);last.getLocationOnScreen(loc)
                assertTrue("Last film clipped below visible window",loc[1]+last.height<=rect.bottom)
                landscapeDialog.dismiss();a.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            waitFor("portrait restored") {a.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT}
        } finally {prefs.edit().putBoolean("grain.enabled",oldEnabled).putStringSet("film.favorites",oldFavorites).commit()}
    }
}
