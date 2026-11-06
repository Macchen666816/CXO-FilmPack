package dev.cxo.filmpack.app

import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class RawUiTest : InstrumentationTestCase() {
    fun testPortraitFileAndOctetStreamContentRenderAndCompare() {
        val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n dev.cxo.filmpack.app/.MainActivity"
        ).close()
        val screen=monitor.waitForActivityWithTimeout(30_000) as? MainActivity
            ?: throw AssertionError("MainActivity did not start within 30 seconds")
        try {
        assertTrue(waitUntil { tagged(screen.window.decorView,"choose_film")?.isEnabled==true })
        val image=tagged(screen.window.decorView,"photo_preview") as ImageView
        val file=File(instrumentation.targetContext.cacheDir,"raw-fixtures/P1055542.RW2")
        assertTrue(file.isFile)
        onMain { screen.loadPhoto(Uri.fromFile(file)) }
        assertTrue("RAW portrait preview did not render",waitUntil {
            image.drawable is BitmapDrawable && image.contentDescription=="胶片效果预览"
        })
        val bitmap=onMain { (image.drawable as BitmapDrawable).bitmap }
        assertTrue("RAW EXIF8 preview must be portrait",bitmap.height>bitmap.width)
        val downTime=SystemClock.uptimeMillis()
        onMain {
            MotionEvent.obtain(downTime,downTime,MotionEvent.ACTION_DOWN,
                image.width/2f,image.height/2f,0).let { event ->
                try { image.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        try {
            // The main thread must remain free to deliver the delayed 500ms comparison.
            SystemClock.sleep(550)
            assertEquals("原图预览",onMain { image.contentDescription })
        } finally {
            onMain {
                MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,
                    image.width/2f,image.height/2f,0).let { event ->
                    try { image.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
        }
        assertEquals("胶片效果预览",onMain { image.contentDescription })
        val content=Uri.parse("content://dev.cxo.filmpack.app.test.photos/P1035087.RW2")
        assertEquals("application/octet-stream",instrumentation.targetContext.contentResolver.getType(content))
        onMain { screen.loadPhoto(content) }
        assertTrue("S9 RAW content preview did not render",waitUntil {
            image.contentDescription=="胶片效果预览" && tagged(screen.window.decorView,"export_photo")?.isEnabled==true
        })
        val s9=onMain { (image.drawable as BitmapDrawable).bitmap }
        assertTrue(s9.width>s9.height)
        android.util.Log.i("CXORaw","Activity portrait compare and S9 octet-stream content preview passed")
        } finally {
            onMain { screen.finish() }
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun waitUntil(test:()->Boolean):Boolean {
        val deadline=SystemClock.elapsedRealtime()+120_000
        while(SystemClock.elapsedRealtime()<deadline) {
            val done=onMain(test)
            if(done)return true
            SystemClock.sleep(100)
        }
        return false
    }
    private fun <T> onMain(action:()->T):T {
        val task=FutureTask(Callable { action() })
        Handler(Looper.getMainLooper()).post(task)
        return task.get(5,TimeUnit.SECONDS)
    }
    private fun tagged(view:View,tag:String):View? {
        if(view.tag==tag)return view
        if(view is ViewGroup)for(i in 0 until view.childCount) tagged(view.getChildAt(i),tag)?.let { return it }
        return null
    }
}
