package dev.cxo.filmpack.app

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import dev.cxo.filmpack.app.post.*
import dev.cxo.filmpack.app.ui.CatalogGlassSlider
import dev.cxo.filmpack.core.PostProcessSettings

class PostEditingProductTest : ForegroundEditorTest() {
    private fun find(v:View,tag:String):View? {
        if(v.tag==tag)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let {return it}
        return null
    }
    private fun waitFor(label:String,condition:()->Boolean) {
        val end=SystemClock.uptimeMillis()+90000
        while(SystemClock.uptimeMillis()<end) {
            instrumentation.waitForIdleSync();var ready=false
            instrumentation.runOnMainSync {ready=condition()}
            if(ready)return
            SystemClock.sleep(80)
        }
        fail(label)
    }
    private fun dialog(a:MainActivity)=MainActivity::class.java.getDeclaredField("postDialog").let {it.isAccessible=true;it.get(a) as? AlertDialog}
    private fun settings(a:MainActivity)=MainActivity::class.java.getDeclaredField("postSettings").let {it.isAccessible=true;it.get(a) as PostEditingSettings}
    private fun screenshot(name:String) {
        instrumentation.waitForIdleSync();SystemClock.sleep(350)
        val bitmap=instrumentation.uiAutomation.takeScreenshot()!!
        try {instrumentation.targetContext.contentResolver.openOutputStream(Uri.parse("content://dev.cxo.filmpack.app.test.photos/post-$name.png"),"w")!!.use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
    }
    fun testSeparateModuleInlineSettingsAndReset() {
        val a=activity;val root=a.window.decorView
        waitFor("initialization") {find(root,"choose_film")?.isEnabled==true}
        val original=settings(a)
        try {
            instrumentation.runOnMainSync {find(root,"editor_drawer_handle")!!.performClick()}
            waitFor("drawer") {find(root,"module_post")!!.height>0}
            instrumentation.runOnMainSync {find(root,"module_post")!!.performClick()}
            waitFor("post module") {find(root,"post_tools")!!.visibility==View.VISIBLE}
            assertFalse("Film controls must not share post page",find(root,"choose_film")!!.isShown)
            screenshot("module")
            instrumentation.runOnMainSync {find(root,"post_light")!!.performClick()}
            waitFor("inline light settings") {find(root,"post_exposure")!=null}
            instrumentation.runOnMainSync {(find(root,"post_exposure") as CatalogGlassSlider).progress=400}
            assertEquals(1f,settings(a).development.exposureEv)
            assertEquals(original.edge,settings(a).edge)
            screenshot("light")
            instrumentation.runOnMainSync {find(root,"post_light")!!.performClick()}
            assertEquals(View.GONE,find(root,"post_light_content")!!.visibility)
            assertEquals(1f,settings(a).development.exposureEv)
            instrumentation.runOnMainSync {find(root,"post_edge")!!.performClick()}
            waitFor("inline edge settings") {find(root,"post_edge_source")!=null}
            instrumentation.runOnMainSync {(find(root,"post_edge_source") as Spinner).setSelection(1)}
            waitFor("edge applied") {settings(a).edge.id.isNotEmpty()}
            screenshot("edge")
            assertEquals(1f,settings(a).development.exposureEv)
            instrumentation.runOnMainSync {find(root,"post_reset")!!.performClick()}
            assertTrue(settings(a).isIdentity)
        } finally {
            instrumentation.runOnMainSync {MainActivity::class.java.getDeclaredField("postSettings").let {it.isAccessible=true;it.set(a,original)};original.save(a.getSharedPreferences("photo-settings",0))}
        }
    }
    fun testPreviewAndStreamingExportContainSamePostEffects() {
        val context=instrumentation.targetContext
        val processor=PhotoProcessor(context)
        val uri=Uri.parse("content://dev.cxo.filmpack.app.test.photos/photo-mosaic.png")
        val output=Uri.parse("content://dev.cxo.filmpack.app.test.photos/post-export.jpg")
        val source=processor.decodePreview(uri)
        val film=processor.films.first {it.token=="KodakPortra400"}
        val post=PostEditingSettings(PostProcessSettings(exposureEv=.7f,contrast=30,saturation=-20),
            EdgeDefectSettings(EdgeDefects.catalog(context).first().id,70,EdgePosition.LEFT,52))
        val baseline=processor.render(source,film,{}, {false})
        val expected=processor.render(source,film,{}, {false},post=post)
        val default=processor.render(source,film,{}, {false},post=PostEditingSettings())
        try {
            assertTrue("Default must preserve original film output",baseline.sameAs(default))
            assertFalse("Enabled post must change pixels",baseline.sameAs(expected))
            processor.export(uri,film,output,{}, {false},post=post)
            val exported=context.contentResolver.openInputStream(output)!!.use {BitmapFactory.decodeStream(it)}!!
            try {
                assertEquals(expected.width,exported.width);assertEquals(expected.height,exported.height)
                var sum=0L;var count=0
                for(y in 4 until expected.height-4 step 13)for(x in 4 until expected.width-4 step 13) {
                    val a=expected.getPixel(x,y);val b=exported.getPixel(x,y)
                    for(shift in listOf(0,8,16)){sum+=kotlin.math.abs((a ushr shift and 255)-(b ushr shift and 255));count++}
                }
                assertTrue("Preview/export differ beyond JPEG tolerance: ${sum.toDouble()/count}",sum.toDouble()/count<4.0)
            } finally {exported.recycle()}
        } finally {baseline.recycle();default.recycle();expected.recycle();source.recycle();processor.close();context.contentResolver.delete(output,null,null)}
    }
    fun testRawPreviewAndFullSizeExportPostProcessing() {
        val context=instrumentation.targetContext
        val source=java.io.File(context.cacheDir,"raw-fixtures/P1035087.RW2")
        assertTrue("Stage actual S9 RAW fixture",source.isFile)
        val uri=Uri.fromFile(source)
        val output=java.io.File(context.cacheDir,"post-raw-export.jpg")
        val post=PostEditingSettings(PostProcessSettings(exposureEv=.4f,temperature=15,contrast=25),
            EdgeDefectSettings("fuitelum1",65,EdgePosition.LEFT,72))
        PhotoProcessor(context).use {processor ->
            val film=processor.films.first {it.token=="KodakPortra400"}
            val placeholder=Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888)
            val plain=processor.render(placeholder,film,{}, {false},rawUri=uri)
            val edited=processor.render(placeholder,film,{}, {false},rawUri=uri,post=post)
            val expected=plain.copy(Bitmap.Config.ARGB_8888,true)
            try {
                applyPostEditing(context,expected,post) {false}
                assertTrue("RAW preview must apply post after camera pipeline",expected.sameAs(edited))
                assertFalse(plain.sameAs(edited))
                val result=processor.export(uri,film,Uri.fromFile(output),{}, {false},post=post)
                assertFalse(result.resized)
                RawPhotoStore(context).use {store ->
                    requireNotNull(store.openCameraIfRaw(uri)).use {camera ->
                        requireNotNull(RegionSrgbPhoto.open(context,Uri.fromFile(output))).use {jpeg ->
                            assertEquals(camera.width,jpeg.width);assertEquals(camera.height,jpeg.height)
                            val cmm=dev.cxo.filmpack.core.FixedWideGamutToSrgb.read(context.assets.open("films/output-cmm/widegamut-to-srgb.lcmsbin").use {it.readBytes()})
                            val input=ShortArray(camera.width*3);val normalized=ShortArray(input.size)
                            val working=ShortArray(input.size);val encoded=ShortArray(input.size)
                            val floats=FloatArray(input.size);val pixels=IntArray(camera.width);val actual=IntArray(camera.width)
                            val renderer=PostEditingRenderer(context,post,camera.width,camera.height,camera.orientation)
                            val pipeline=RawRenderPipeline(context);var error=0L;var count=0
                            for(y in listOf(camera.height/5,camera.height/2,camera.height*4/5)) {
                                camera.readRows(y,1,input)
                                pipeline.renderRows(camera,film,input,normalized,working,encoded) {false}
                                for(i in floats.indices)floats[i]=encoded[i].toInt()/32767f
                                cmm.render(floats,floats)
                                for(x in pixels.indices) {
                                    fun channel(c:Int)=kotlin.math.round(floats[x*3+c].coerceIn(0f,1f)*255f).toInt()
                                    pixels[x]=android.graphics.Color.rgb(channel(0),channel(1),channel(2))
                                }
                                renderer.apply(pixels,y);jpeg.readRows(y,1,actual)
                                for(x in 4 until pixels.size-4 step 17)for(shift in listOf(0,8,16)) {
                                    error+=kotlin.math.abs((pixels[x] ushr shift and 255)-(actual[x] ushr shift and 255));count++
                                }
                            }
                            assertTrue("RAW export post error ${error.toDouble()/count}",error.toDouble()/count<4.0)
                        }
                    }
                }
            } finally {plain.recycle();edited.recycle();expected.recycle();placeholder.recycle();output.delete()}
        }
    }
}
