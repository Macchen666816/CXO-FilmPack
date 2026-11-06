package dev.cxo.filmpack.app

import android.app.AlertDialog
import java.io.File
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import dev.cxo.filmpack.app.ui.CatalogGlassSlider
import dev.cxo.filmpack.app.ui.CatalogGlassToggle
import dev.cxo.filmpack.core.NativeGrain8Geometry
import java.util.concurrent.atomic.AtomicInteger

/** Real photo decode plus transactional live grain settings; no private render calls. */
class GrainLiveSettingsTest : ForegroundEditorTest() {
    private fun main(block:()->Unit) {
        var failure:Throwable?=null
        instrumentation.runOnMainSync {try {block()} catch(error:Throwable) {failure=error}}
        failure?.let {throw it}
    }
    private fun find(v:View,tag:String):View? {
        if(v.tag==tag)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let {return it}
        return null
    }
    private fun field(a:MainActivity,name:String):Any?=MainActivity::class.java.getDeclaredField(name).let {it.isAccessible=true;it.get(a)}
    private fun settings(a:MainActivity)=field(a,"grainSettings") as GrainSettings
    private fun dialog(a:MainActivity)=field(a,"grainDialog") as? AlertDialog
    private fun imageGeneration(a:MainActivity)=(field(a,"imageGeneration") as AtomicInteger).get()
    private fun waitFor(label:String,condition:()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+120_000
        while(SystemClock.uptimeMillis()<deadline) {
            instrumentation.waitForIdleSync();var ready=false;main {ready=condition()};if(ready)return;SystemClock.sleep(80)
        }
        fail(label)
    }
    fun testLiveChangesDecodeOnceAndCancelThenApply() {
        val preferences=instrumentation.targetContext.getSharedPreferences("photo-settings",0)
        val saved=GrainSettings.read(preferences)
        GrainSettings().save(preferences)
        val a=activity;val root=a.window.decorView
        waitFor("initialization") {find(root,"choose_film")?.isEnabled==true && field(a,"grainIdentifiers").let {it is List<*> && it.isNotEmpty()}}
        val localFixture=File(instrumentation.targetContext.cacheDir,"grain-live-mosaic.png")
        instrumentation.context.assets.open("photo-mosaic.png").use {input -> localFixture.outputStream().use {input.copyTo(it)}}
        main {MainActivity::class.java.getDeclaredField("grainSettings").let {it.isAccessible=true;it.set(a,GrainSettings())};a.loadPhoto(Uri.fromFile(localFixture))}
        waitFor("baseline photo") {find(root,"export_photo")!!.isEnabled}
        val initialGeneration=imageGeneration(a)
        try {
            main {find(root,"grain_settings")!!.performClick()}
            instrumentation.waitForIdleSync()
            main {
                assertEquals(initialGeneration,imageGeneration(a))
                val d=dialog(a)!!
                assertEquals(0f,d.window!!.attributes.dimAmount)
                (find(d.window!!.decorView,"grain_enabled") as CatalogGlassToggle).isChecked=true
                assertTrue("Toggle must publish before Apply",settings(a).enabled)
                assertFalse("Live settings must stay transactional",GrainSettings.read(preferences).enabled)
            }
            waitFor("first native grain geometry") {field(a,"grainPreview")!=null && find(root,"export_photo")!!.isEnabled}
            val nativeGeneration=imageGeneration(a)
            assertEquals(initialGeneration+1,nativeGeneration)
            main {
                val d=dialog(a)!!.window!!.decorView
                (find(d,"grain_intensity") as CatalogGlassSlider).progress=144
                assertEquals(144,settings(a).intensity)
                (find(d,"grain_format") as Spinner).setSelection(3)
            }
            instrumentation.waitForIdleSync()
            main {
                val d=dialog(a)!!.window!!.decorView
                (find(d,"grain_manual_size") as CatalogGlassSlider).progress=5
                assertEquals(3f,settings(a).manualSize)
                assertEquals(NativeGrain8Geometry.Format.MANUAL,settings(a).format)
                assertEquals(nativeGeneration,imageGeneration(a))
                find(d,"grain_cancel")!!.performClick()
            }
            waitFor("cancel rolls back settings") {dialog(a)==null && settings(a)==GrainSettings()}
            assertFalse(GrainSettings.read(preferences).enabled)
            waitFor("cancel restores preview") {find(root,"export_photo")!!.isEnabled}
            assertEquals(nativeGeneration,imageGeneration(a))
            main {find(root,"grain_settings")!!.performClick()}
            instrumentation.waitForIdleSync()
            main {
                val d=dialog(a)!!.window!!.decorView
                (find(d,"grain_enabled") as CatalogGlassToggle).isChecked=true
                (find(d,"grain_intensity") as CatalogGlassSlider).progress=63
                assertEquals(nativeGeneration,imageGeneration(a))
                find(d,"grain_apply")!!.performClick()
                assertTrue(GrainSettings.read(preferences).enabled)
                assertEquals(63,GrainSettings.read(preferences).intensity)
            }
            waitFor("applied live preview") {find(root,"export_photo")!!.isEnabled}
            assertEquals(nativeGeneration,imageGeneration(a))
        } finally {
            main {dialog(a)?.dismiss()}
            instrumentation.waitForIdleSync()
            val localFixture=File(instrumentation.targetContext.cacheDir,"grain-live-mosaic.png")
        instrumentation.context.assets.open("photo-mosaic.png").use {input -> localFixture.outputStream().use {input.copyTo(it)}}
        main {MainActivity::class.java.getDeclaredField("grainSettings").let {it.isAccessible=true;it.set(a,saved)};saved.save(preferences)}
        }
    }
}
