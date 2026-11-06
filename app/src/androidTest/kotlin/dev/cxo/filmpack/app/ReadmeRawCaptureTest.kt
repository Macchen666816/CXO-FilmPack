package dev.cxo.filmpack.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.File

/** Opt-in documentation capture from real RAW files staged by the owner. */
@Suppress("DEPRECATION")
class ReadmeRawCaptureTest : android.test.ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    fun testCaptureDocumentationImages() {
        val editor = activity
        val context = instrumentation.targetContext
        val out = File(context.getExternalFilesDir(null), "readme-raw").apply { mkdirs() }
        for (name in listOf("P1176257.RW2", "DSC_3271.NEF", "DSC07319.ARW")) {
            val source = File(context.cacheDir, "readme-$name")
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cat /data/local/tmp/$name")).use { input -> source.outputStream().use { input.copyTo(it) } }
            try {
                PhotoProcessor(context).use { engine ->
                    val uri = Uri.fromFile(source)
                    val original = engine.decodePreview(uri, 1600)
                    try {
                        fun save(bitmap: Bitmap, suffix: String) = File(out, "$name-$suffix.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        save(original, "original")
                        for (token in listOf("KodakPortra400", "KodakEliteColor400")) {
                            val result = engine.render(original, engine.films.single { it.token == token }, {}, { false }, rawUri = uri)
                            try { save(result, token) } finally { result.recycle() }
                        }
                    } finally { original.recycle() }
                }
                if (name == "P1176257.RW2") {
                    instrumentation.runOnMainSync { editor.loadPhoto(Uri.fromFile(source)) }
                    val deadline = SystemClock.elapsedRealtime() + 120_000
                    var ready = false
                    while (!ready && SystemClock.elapsedRealtime() < deadline) {
                        instrumentation.runOnMainSync {
                            val field = MainActivity::class.java.getDeclaredField("precisePreviewReady").apply { isAccessible = true }
                            ready = field.getBoolean(editor)
                        }
                        if (!ready) SystemClock.sleep(100)
                    }
                    assertTrue("Editor preview must finish before its screenshot", ready)
                    instrumentation.runOnMainSync {
                        MainActivity::class.java.getDeclaredMethod("setEditorDrawerExpanded", Boolean::class.javaPrimitiveType)
                            .apply { isAccessible = true }.invoke(editor, true)
                    }
                    SystemClock.sleep(500)
                    instrumentation.waitForIdleSync()
                    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try { File(out, "workbench.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { screenshot.recycle() }
                }
            } finally { source.delete() }
        }
    }
}
