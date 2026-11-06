package dev.cxo.filmpack.app

import android.graphics.BitmapFactory
import android.net.Uri
import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.*
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

class OutputCmmTest : InstrumentationTestCase() {
    fun testIndependentLcmsOutputBoundary() {
        val transform = FixedWideGamutToSrgb.read(instrumentation.targetContext.assets.open("films/output-cmm/widegamut-to-srgb.lcmsbin").use { it.readBytes() })
        val rows = instrumentation.context.assets.open("output-cmm-rgb16.golden").use { verifyOutputCmm(it,transform) }
        assertEquals(132768,rows)
    }

    fun testManagedOriginalPhotoReferences() {
        val context = instrumentation.targetContext
        val engine = PhotoProcessor(context)
        val input = File(context.cacheDir,"managed-reference-source.png")
        instrumentation.context.assets.open("photo-mosaic.png").use { source -> input.outputStream().use(source::copyTo) }
        val source = engine.decodePreview(Uri.fromFile(input))
        try {
            for ((token,strength) in listOf("KodakPortra400" to 100,"KodakPortra400" to 50,"KodakEIR" to 100,"KodakTriX400" to 100,"FujiSuperia200E6" to 100)) {
                val expected = instrumentation.context.assets.open("managed-$token-$strength.png").use { requireNotNull(BitmapFactory.decodeStream(it)) }
                val actual = engine.render(source,engine.films.first { it.token == token },{}, { false },strength)
                try {
                    assertEquals(expected.width,actual.width); assertEquals(expected.height,actual.height)
                    val a = IntArray(actual.width*actual.height); val b = IntArray(a.size)
                    actual.getPixels(a,0,actual.width,0,0,actual.width,actual.height)
                    expected.getPixels(b,0,expected.width,0,0,expected.width,expected.height)
                    var sum = 0.0; var maximum = 0
                    for (i in a.indices) for (shift in intArrayOf(0,8,16)) {
                        val error = abs(((a[i] ushr shift) and 255)-((b[i] ushr shift) and 255))
                        sum += error.toDouble()*error; maximum=maxOf(maximum,error)
                    }
                    val rmse = sqrt(sum/(a.size.toLong()*3))
                    android.util.Log.i("CXOOutputCmm","$token/$strength managed RMSE=$rmse max=$maximum")
                    File(context.cacheDir,"managed-actual-$token-$strength.png").outputStream().use { actual.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                    assertTrue("$token/$strength managed RMSE=$rmse max=$maximum",rmse < .3 && maximum <= 4)

                    // Compare the saved file with the independent original-engine
                    // reference as well: preview/export agreement alone can hide
                    // the same missing output colour transform in both paths.
                    val saved = File(context.cacheDir,"managed-saved-$token-$strength.jpg")
                    val result = engine.export(Uri.fromFile(input),engine.films.first { it.token == token },
                        Uri.fromFile(saved),{}, { false },strength)
                    assertFalse(result.resized)
                    val jpeg = requireNotNull(BitmapFactory.decodeFile(saved.path))
                    try {
                        assertEquals(expected.width,jpeg.width); assertEquals(expected.height,jpeg.height)
                        val decoded = IntArray(b.size)
                        jpeg.getPixels(decoded,0,jpeg.width,0,0,jpeg.width,jpeg.height)
                        var jpegSquared = 0.0
                        for (i in decoded.indices) for (shift in intArrayOf(0,8,16)) {
                            val error = ((decoded[i] ushr shift) and 255)-((b[i] ushr shift) and 255)
                            jpegSquared += error.toDouble()*error
                        }
                        val jpegRmse = sqrt(jpegSquared/(decoded.size.toLong()*3))
                        android.util.Log.i("CXOOutputCmm","$token/$strength saved managed JPEG RMSE=$jpegRmse")
                        assertTrue("$token/$strength saved JPEG differs from managed original: $jpegRmse",jpegRmse < 3.0)
                    } finally { jpeg.recycle() }
                } finally { actual.recycle(); expected.recycle() }
            }
        } finally { source.recycle() }
    }
}
