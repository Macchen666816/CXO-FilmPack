package dev.cxo.filmpack.app

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import android.net.Uri
import android.test.InstrumentationTestCase
import android.util.Log
import java.io.File

/** Actual Android decoder/encoder checks; these fixtures are never shipped in the app. */
@Suppress("DEPRECATION")
class PhotoBoundaryTest : InstrumentationTestCase() {
    private fun fixture(name: String): File = File(instrumentation.targetContext.cacheDir, name).also { file ->
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
    }

    fun testExifOrientationAppliedOnceAndTransparentJpegIsWhite() {
        val engine = PhotoProcessor(instrumentation.targetContext)
        val oriented = engine.decodePreview(Uri.fromFile(fixture("orientation6.jpg")))
        try {
            assertEquals(40, oriented.width)
            assertEquals(80, oriented.height)
            assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), oriented.colorSpace)
            assertTrue(Color.red(oriented.getPixel(20, 10)) > 230)
            assertTrue(Color.blue(oriented.getPixel(20, 70)) > 230)
        } finally { oriented.recycle() }
        val output = File(instrumentation.targetContext.cacheDir, "transparent-export.jpg")
        val film = engine.films.first { it.token == "KodakPortra400" }
        engine.export(Uri.fromFile(fixture("transparent.png")), film, Uri.fromFile(output), {}, { false })
        val bitmap = requireNotNull(BitmapFactory.decodeFile(output.path))
        try {
            assertEquals(64, bitmap.width)
            val pixel = bitmap.getPixel(32, 16)
            assertTrue(Color.red(pixel) >= 254 && Color.green(pixel) >= 254 && Color.blue(pixel) >= 254)
        } finally { bitmap.recycle() }
    }

    fun testLargePhotoKeepsOriginalSizeAndGradientRenders() {
        val engine = PhotoProcessor(instrumentation.targetContext)
        val film = engine.films.first { it.token == "KodakPortra400" }
        val output = File(instrumentation.targetContext.cacheDir, "large-export.jpg")
        var maxJava = 0L; var maxNative = 0L
        val result = engine.export(Uri.fromFile(fixture("large-photo-24mp.jpg")), film, Uri.fromFile(output), {
            maxJava = maxOf(maxJava,Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
            maxNative = maxOf(maxNative,android.os.Debug.getNativeHeapAllocatedSize())
        }, { false })
        assertFalse(result.resized)
        assertEquals(6000,result.width)
        assertEquals(4000,result.height)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.path, bounds)
        assertEquals(result.width, bounds.outWidth)
        assertEquals(result.height, bounds.outHeight)
        Log.i("CXOProduct", "Full photo export: ${result.width}x${result.height}, ${result.elapsedMillis}ms, maxJavaObserved=$maxJava, maxNativeObserved=$maxNative, heapLimit=${Runtime.getRuntime().maxMemory()}")
        val gradient = engine.decodePreview(Uri.fromFile(fixture("sky-gradient.png")))
        try {
            val rendered = engine.render(gradient, film, {}, { false })
            try {
                File(instrumentation.targetContext.cacheDir, "quality-gradient.png").outputStream().use {
                    assertTrue(rendered.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                // Smooth input must not introduce large isolated jumps along either axis.
                for (y in 16 until rendered.height step 32) {
                    for (x in 1 until rendered.width) {
                        val a = rendered.getPixel(x - 1, y); val b = rendered.getPixel(x, y)
                        assertTrue(kotlin.math.abs(Color.red(a) - Color.red(b)) <= 5)
                        assertTrue(kotlin.math.abs(Color.green(a) - Color.green(b)) <= 5)
                        assertTrue(kotlin.math.abs(Color.blue(a) - Color.blue(b)) <= 5)
                    }
                }
            } finally { rendered.recycle() }
        } finally { gradient.recycle() }
    }
}
