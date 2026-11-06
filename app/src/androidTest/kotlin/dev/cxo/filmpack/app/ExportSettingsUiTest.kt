package dev.cxo.filmpack.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Spinner
import dev.cxo.filmpack.app.ui.CatalogGlassSlider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("DEPRECATION")
class ExportSettingsUiTest : ForegroundEditorTest() {
    override fun setUp() {
        super.setUp()
        instrumentation.targetContext.getSharedPreferences("photo-settings",0).edit().clear().commit()
    }
    override fun tearDown() {
        instrumentation.targetContext.getSharedPreferences("photo-settings",0).edit().clear().commit()
        super.tearDown()
    }
    private fun tagged(view:View,tag:String):View? {
        if(view.tag==tag)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)tagged(view.getChildAt(i),tag)?.let{return it}
        return null
    }
    private fun waitFor(label:String,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+90000
        while(SystemClock.elapsedRealtime()<deadline) {
            instrumentation.waitForIdleSync()
            var ready=false
            instrumentation.runOnMainSync {ready=condition()}
            if(ready)return
            SystemClock.sleep(80)
        }
        fail("Timeout: $label")
    }
    private fun onUi(block:()->Unit) {
        var failure:Throwable?=null
        instrumentation.runOnMainSync {try {block()}catch(error:Throwable){failure=error}}
        failure?.let {throw it}
    }
    private fun dialog(a:MainActivity):AlertDialog?=MainActivity::class.java.getDeclaredField("exportSettingsDialog").let {
        it.isAccessible=true;it.get(a) as? AlertDialog
    }
    private fun prepared():MainActivity {
        val a=activity
        waitFor("initialize") {tagged(a.window.decorView,"choose_film")!!.isEnabled}
        val file=File(instrumentation.targetContext.cacheDir,"export-ui-source.png")
        instrumentation.context.assets.open("photo-mosaic.png").use {input->file.outputStream().use {input.copyTo(it)}}
        onUi {a.loadPhoto(Uri.fromFile(file))}
        waitFor("render") {tagged(a.window.decorView,"export_photo")!!.isEnabled}
        return a
    }
    private fun open(a:MainActivity):View {
        onUi {tagged(a.window.decorView,"export_photo")!!.performClick()}
        waitFor("export sheet") {dialog(a)?.isShowing==true}
        return dialog(a)!!.window!!.decorView
    }
    fun testLostExportSnapshotDeletesNewEmptyDocumentAndKeepsOriginal() {
        val a = prepared()
        val output = File(instrumentation.targetContext.cacheDir,"interrupted-export-empty.jpg")
        output.writeBytes(byteArrayOf())
        onUi {
            val callback = MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            callback.invoke(a, 102, Activity.RESULT_OK, Intent().setData(Uri.fromFile(output)))
        }
        waitFor("empty document cleanup") { !output.exists() }
        assertTrue(File(instrumentation.targetContext.cacheDir,"export-ui-source.png").isFile)
    }
    fun testCancelAndCustomValidationKeepEditorUsable() {
        val a=prepared();val root=open(a)
        onUi {
            assertFalse(tagged(a.window.decorView,"intensity_slider")!!.isEnabled)
            (tagged(root,"export_resolution") as Spinner).setSelection(7)
        }
        instrumentation.waitForIdleSync()
        onUi {
            (tagged(root,"export_custom_edge") as EditText).setText("0")
            tagged(root,"export_confirm")!!.performClick()
            assertTrue(dialog(a)!!.isShowing)
            assertNotNull((tagged(root,"export_custom_edge") as EditText).error)
            tagged(root,"export_cancel")!!.performClick()
        }
        waitFor("cancel unlock") {dialog(a)==null && tagged(a.window.decorView,"export_photo")!!.isEnabled}
    }
    fun testJpegAndTiffMenuReachRealEncoderWithSelectedSettings() {
        val a=prepared()
        for(format in ExportFormat.entries) {
            val output=File(instrumentation.targetContext.cacheDir,"export-ui.${format.extension}")
            val monitor=Instrumentation.ActivityMonitor(IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE);addDataType(format.mimeType)
            },Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.fromFile(output))),true)
            instrumentation.addMonitor(monitor)
            try {
                val root=open(a)
                onUi {
                    (tagged(root,"export_format") as Spinner).setSelection(format.ordinal)
                    (tagged(root,"export_resolution") as Spinner).setSelection(7)
                    (tagged(root,"export_quality") as CatalogGlassSlider).progress=72
                }
                waitFor("format controls") {
                    tagged(root,"export_quality")!!.isEnabled == (format==ExportFormat.JPEG) &&
                        tagged(root,"export_custom_edge")!!.visibility==View.VISIBLE
                }
                // Capture the settled sheet, rather than its translucent window entrance animation.
                SystemClock.sleep(500)
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "screencap -p /data/local/tmp/cxo-export-${format.name}.png"
                )).use { it.readBytes() }
                onUi {
                    assertEquals(format==ExportFormat.JPEG,tagged(root,"export_quality")!!.isEnabled)
                    (tagged(root,"export_custom_edge") as EditText).setText("257")
                    tagged(root,"export_confirm")!!.performClick()
                }
                waitFor("save ${format.name}") {monitor.hits==1 && tagged(a.window.decorView,"export_photo")!!.isEnabled}
                assertTrue(output.isFile)
                if(format==ExportFormat.JPEG) {
                    val bitmap=requireNotNull(BitmapFactory.decodeFile(output.path))
                    try {assertEquals(257,maxOf(bitmap.width,bitmap.height))}finally{bitmap.recycle()}
                } else {
                    val bytes=output.readBytes();val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    assertEquals(42,b.getShort(2).toInt())
                    val start=b.getInt(4);val tags=HashMap<Int,Int>()
                    repeat(b.getShort(start).toInt()) {val p=start+2+it*12;tags[b.getShort(p).toInt() and 65535]=b.getInt(p+8)}
                    assertEquals(257,maxOf(tags.getValue(256),tags.getValue(257)))
                }
                val prefs=instrumentation.targetContext.getSharedPreferences("photo-settings",0)
                assertEquals(format.name,prefs.getString("export.format",null))
                assertEquals(73,prefs.getInt("export.quality",0));assertEquals(257,prefs.getInt("export.longEdge",0))
            }finally{instrumentation.removeMonitor(monitor);output.delete()}
        }
    }
}
