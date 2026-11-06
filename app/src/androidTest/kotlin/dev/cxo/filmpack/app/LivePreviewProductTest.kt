package dev.cxo.filmpack.app

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import dev.cxo.filmpack.app.post.PostEditingSettings
import dev.cxo.filmpack.app.ui.CatalogGlassSlider
import dev.cxo.filmpack.core.PostProcessSettings
import java.io.File

/** Actual owner/scheduler: pixels must change before ACTION_UP. */
class LivePreviewProductTest : ForegroundEditorTest() {
    private fun report(stage:String,millis:Long) {
        instrumentation.sendStatus(2,android.os.Bundle().apply {
            putString("cxoLivePerf",org.json.JSONObject().put("stage",stage).put("firstMs",millis).toString())
        })
    }
    private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun find(v:View,tag:String):View? {
        if(v.tag==tag)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let {return it}
        return null
    }
    private fun field(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.get(a)
    private fun waitFor(label:String,condition:()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+90000
        while(SystemClock.uptimeMillis()<deadline) {
            var ready=false;main {ready=condition()}
            if(ready)return
            SystemClock.sleep(20)
        }
        fail(label)
    }
    private fun bitmap(a:MainActivity)=((find(a.window.decorView,"photo_preview") as ImageView).drawable as BitmapDrawable).bitmap
    private fun touch(slider:CatalogGlassSlider,action:Int,down:Long) {
        main {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,slider.width*.5f,slider.height*.5f,0)
            try {slider.dispatchTouchEvent(event)} finally {event.recycle()}
        }
    }
    private var savedPreferences: Map<String, *> = emptyMap<String,Any>()
    override fun setUp() {
        super.setUp()
        val preferences=instrumentation.targetContext.getSharedPreferences("photo-settings",0)
        savedPreferences=preferences.all.toMap();preferences.edit().clear().commit()
    }
    override fun tearDown() {
        val editor=instrumentation.targetContext.getSharedPreferences("photo-settings",0).edit().clear()
        for((key,value) in savedPreferences)when(value) {
            is String -> editor.putString(key,value)
            is Int -> editor.putInt(key,value)
            is Long -> editor.putLong(key,value)
            is Float -> editor.putFloat(key,value)
            is Boolean -> editor.putBoolean(key,value)
            is Set<*> -> editor.putStringSet(key,value.filterIsInstance<String>().toSet())
        }
        editor.commit();super.tearDown()
    }
    private fun fixture():Uri {
        val file=File(instrumentation.targetContext.cacheDir,"live-preview-source.png")
        val image=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888)
        val row=IntArray(1200)
        for(y in 0 until 800) {
            for(x in row.indices)row[x]=android.graphics.Color.rgb(30+x*170/1200,20+y*150/800,40+(x+y)*110/2000)
            image.setPixels(row,0,1200,0,y,1200,1)
        }
        try {file.outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {image.recycle()}
        return Uri.fromFile(file)
    }
    private fun ready(uri:Uri):MainActivity {
        val a=activity
        waitFor("initialization") {find(a.window.decorView,"choose_film")?.isEnabled==true}
        main {a.loadPhoto(uri)}
        waitFor("precise photo") {find(a.window.decorView,"export_photo")!!.isEnabled}
        return a
    }
    private fun assertFinal(a:MainActivity,uri:Uri,post:PostEditingSettings,intensity:Int=100) {
        val film=field(a,"selectedFilm") as FilmChoice
        PhotoProcessor(instrumentation.targetContext).use {engine ->
            val source=engine.decodePreview(uri)
            val expected=engine.render(source,film,{}, {false},intensity=intensity,rawUri=uri,post=post)
            try {main {assertTrue("Final frame must equal direct precise pipeline",bitmap(a).sameAs(expected))}}
            finally {source.recycle();expected.recycle()}
        }
    }
    fun testPostChangesBeforeReleaseAndLatestValueWins() {
        val uri=fixture();val a=ready(uri);val root=a.window.decorView
        lateinit var baseline:Bitmap
        main {baseline=bitmap(a);find(root,"editor_drawer_handle")!!.performClick();find(root,"module_post")!!.performClick();find(root,"post_light")!!.performClick()}
        instrumentation.waitForIdleSync()
        val slider=find(root,"post_brightness") as CatalogGlassSlider
        val down=SystemClock.uptimeMillis();touch(slider,MotionEvent.ACTION_DOWN,down)
        val started=SystemClock.uptimeMillis()
        main {slider.progress=135}
        waitFor("post frame while finger is held") {bitmap(a)!==baseline && bitmap(a).width==640}
        val first=SystemClock.uptimeMillis()-started
        main {assertFalse("Export must wait for precise final frame",find(root,"export_photo")!!.isEnabled)}
        var frames=0
        var previous=bitmap(a)
        for(value in 140..180 step 5) {
            main {slider.progress=value}
            SystemClock.sleep(65)
            main {if(bitmap(a)!==previous){frames++;previous=bitmap(a)}}
        }
        touch(slider,MotionEvent.ACTION_CANCEL,down)
        waitFor("final post frame") {find(root,"export_photo")!!.isEnabled}
        assertEquals(80,(field(a,"postSettings") as PostEditingSettings).development.brightness)
        assertTrue("No continuous frames during drag",frames>0)
        assertFinal(a,uri,PostEditingSettings(PostProcessSettings(brightness=80)))
        android.util.Log.i("CXOLivePreview","post first_ms="+first+" intermediate_frames="+frames+" final=exact")
        report("post",first)
        main {find(root,"post_light")!!.performClick();assertEquals(View.GONE,find(root,"post_light_content")!!.visibility)}
    }
    fun testFilmIntensityChangesBeforeReleaseAndFinalIsPrecise() {
        val uri=fixture();val a=ready(uri);val root=a.window.decorView
        val slider=find(root,"intensity_slider") as CatalogGlassSlider
        main {find(root,"editor_drawer_handle")!!.performClick()}
        instrumentation.waitForIdleSync()
        val baseline=bitmap(a);val down=SystemClock.uptimeMillis()
        touch(slider,MotionEvent.ACTION_DOWN,down)
        val started=SystemClock.uptimeMillis();main {slider.progress=45}
        waitFor("film frame before release") {bitmap(a)!==baseline && bitmap(a).width==640}
        val first=SystemClock.uptimeMillis()-started
        main {for(value in 50..75)slider.progress=value}
        touch(slider,MotionEvent.ACTION_CANCEL,down)
        waitFor("film final") {find(root,"export_photo")!!.isEnabled}
        assertFinal(a,uri,PostEditingSettings(),75)
        android.util.Log.i("CXOLivePreview","film first_ms="+first+" final=exact")
        report("film",first)
    }
    fun testRawPostChangesReuseCameraPipelineAndFinalIsPrecise() {
        val raw=File(instrumentation.targetContext.cacheDir,"raw-fixtures/P1035087.RW2")
        val args=(instrumentation as android.test.InstrumentationTestRunner).arguments
        val uri=args.getString("liveRawSource")?.let(Uri::parse) ?: Uri.fromFile(raw).also {
            assertTrue("Stage S9 fixture",raw.isFile)
        }
        val a=ready(uri);val root=a.window.decorView
        main {find(root,"editor_drawer_handle")!!.performClick();find(root,"module_post")!!.performClick();find(root,"post_light")!!.performClick()}
        instrumentation.waitForIdleSync()
        val slider=find(root,"post_brightness") as CatalogGlassSlider
        val baseline=bitmap(a);val down=SystemClock.uptimeMillis();touch(slider,MotionEvent.ACTION_DOWN,down)
        val started=SystemClock.uptimeMillis();main {slider.progress=125}
        waitFor("RAW cached post frame while held") {bitmap(a)!==baseline && bitmap(a).width==640}
        val first=SystemClock.uptimeMillis()-started
        touch(slider,MotionEvent.ACTION_CANCEL,down)
        waitFor("RAW precise final") {find(root,"export_photo")!!.isEnabled}
        assertFinal(a,uri,PostEditingSettings(PostProcessSettings(brightness=25)))
        android.util.Log.i("CXOLivePreview","raw_post first_ms="+first+" final=exact")
        report("raw_post",first)
    }
}
