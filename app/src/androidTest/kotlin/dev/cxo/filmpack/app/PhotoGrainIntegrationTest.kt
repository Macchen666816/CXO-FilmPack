package dev.cxo.filmpack.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.*
import java.io.File
import java.util.zip.InflaterInputStream
import kotlin.math.abs
import kotlin.math.sqrt

class PhotoGrainIntegrationTest : InstrumentationTestCase() {
    private fun prepare(film: FilmChoice, engine: PhotoProcessor, bitmap: Bitmap): PhotoGrainLayer {
        val assets = instrumentation.context.assets
        val catalog = NativeGrain8Catalog.read(assets.open("grain-catalog.cxograin").use { it.readBytes() })
        val lut = NativeGrain8ColorLutFactory.fromQ15Transform(LcmsOptimizedQ15Transform.read(assets.open("grain-adobe-to-widegamut.lcmsbin").use { it.readBytes() }))
        val loader = PhotoGrainLoader(catalog,lut) { definition ->
            InflaterInputStream(assets.open("grain-catalog-sources/${definition.grainEnum}.rgbz")).use { it.readBytes() }
        }
        val preview = PhotoGrainPreview(bitmap,bitmap.width,bitmap.height,0)
        return loader.prepare(engine.grainRequest(preview,film,film.token,
            NativeGrain8Geometry.Format.MANUAL,1f,100,17))
    }

    private fun error(a: Bitmap, b: Bitmap): Pair<Double,Int> {
        assertEquals(a.width,b.width); assertEquals(a.height,b.height)
        val actual = IntArray(a.width*a.height); val expected = IntArray(actual.size)
        a.getPixels(actual,0,a.width,0,0,a.width,a.height); b.getPixels(expected,0,b.width,0,0,b.width,b.height)
        var sum = 0.0; var maximum = 0
        for (i in actual.indices) for (shift in intArrayOf(0,8,16)) {
            val e = abs(((actual[i] ushr shift) and 255)-((expected[i] ushr shift) and 255))
            sum += e.toDouble()*e; maximum=maxOf(maximum,e)
        }
        return sqrt(sum/(actual.size.toLong()*3)) to maximum
    }

    fun testFullPhotoGrainTiledPreviewAndStreamingSave() {
        val context = instrumentation.targetContext
        val engine = PhotoProcessor(context)
        val file = File(context.cacheDir,"grain-photo-source.png")
        instrumentation.context.assets.open("photo-mosaic.png").use { input -> file.outputStream().use(input::copyTo) }
        val source = engine.decodePreview(Uri.fromFile(file))
        try {
            for ((token,strength) in listOf("KodakPortra400" to 100,"KodakPortra400" to 50,"KodakEIR" to 100,"KodakTriX400" to 100,"FujiSuperia200E6" to 100)) {
                val film = engine.films.first { it.token == token }; val grain = prepare(film,engine,source)
                val actual = engine.render(source,film,{}, { false },strength,grain)
                val expected = instrumentation.context.assets.open("managed-grain-$token-$strength.png").use { requireNotNull(BitmapFactory.decodeStream(it)) }
                try {
                    val (rmse,max) = error(actual,expected)
                    android.util.Log.i("CXOPhotoGrain","$token/$strength native-managed RMSE=$rmse max=$max")
                    assertTrue("$token/$strength RMSE=$rmse max=$max",rmse < .15 && max <= 1)
                    File(context.cacheDir,"grain-actual-$token-$strength.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG,100,it) }
                    val output = File(context.cacheDir,"grain-stream-$token-$strength.jpg")
                    val result = engine.export(Uri.fromFile(file),film,Uri.fromFile(output),{}, { false },strength,grain)
                    assertFalse(result.resized)
                    val jpeg = engine.decodePreview(Uri.fromFile(output))
                    try {
                        val saved = error(jpeg,actual).first
                        android.util.Log.i("CXOPhotoGrain","$token/$strength saved JPEG RMSE=$saved")
                        assertTrue("$token/$strength saved RMSE=$saved",saved < 4)
                    } finally { jpeg.recycle() }
                } finally { actual.recycle(); expected.recycle() }
            }
        } finally { source.recycle() }
    }

    fun testMismatchedCoordinateGridRejected() {
        val engine = PhotoProcessor(instrumentation.targetContext)
        val source = Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        try {
            val layer = PhotoGrainLayer(3,2,NativeGrain8Patch(byteArrayOf(127,127,127),1,1,1,IntArray(256)))
            assertTrue(runCatching { engine.render(source,engine.films.first(),{}, { false },100,layer) }.isFailure)
        } finally { source.recycle() }
    }
}
