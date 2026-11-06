package dev.cxo.filmpack.app

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.test.InstrumentationTestCase
import java.io.File

@Suppress("DEPRECATION")
class StreamingPhotoTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private fun fixture(name: String): File = File(context.cacheDir,"stream-input-$name").also { file ->
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    fun testRegionRowsMatchManagedDecoderIncludingProfileAndOrientation() {
        val engine = PhotoProcessor(context)
        for (name in listOf("photo-mosaic.png","odd-photo.png","adobe-photo.jpg","orientation6.jpg")) {
            val uri = Uri.fromFile(fixture(name))
            val managed = engine.decodePreview(uri)
            try {
                requireNotNull(RegionSrgbPhoto.open(context,uri)).use { source ->
                    assertEquals(managed.width,source.displayWidth)
                    assertEquals(managed.height,source.displayHeight)
                    val raw = IntArray(source.width*source.height)
                    val block = IntArray(source.width*7)
                    var top = 0
                    while (top < source.height) {
                        val count = minOf(7,source.height-top)
                        source.readRows(top,count,block)
                        block.copyInto(raw,top*source.width,0,count*source.width)
                        top += count
                    }
                    var maxError = 0
                    for (y in 0 until managed.height) for (x in 0 until managed.width) {
                        // Fixtures are normal or EXIF6 (clockwise90). Use the
                        // expected photographic geometry, independent of adapter helpers.
                        assertTrue(source.orientation == 1 || source.orientation == 6)
                        val sx = if (source.orientation == 6) y else x
                        val sy = if (source.orientation == 6) source.height-1-x else y
                        val a = raw[sy*source.width+sx]; val b = managed.getPixel(x,y)
                        for (shift in intArrayOf(0,8,16)) maxError = maxOf(maxError,kotlin.math.abs(((a ushr shift) and 255)-((b ushr shift) and 255)))
                    }
                    assertTrue("$name region/profile/orientation error $maxError",maxError <= 2)
                }
            } finally { managed.recycle() }
        }
    }

    fun testStreamingExportQualityOrientationAndIntensity() {
        val engine = PhotoProcessor(context)
        val portra = engine.films.first { it.token == "KodakPortra400" }
        val eir = engine.films.first { it.token == "KodakEIR" }
        for ((name,film,intensity) in listOf(
            Triple("photo-mosaic.png",portra,100),Triple("photo-mosaic.png",eir,50),
            Triple("photo-mosaic.png",eir,200),Triple("odd-photo.png",portra,100),
            Triple("orientation6.jpg",portra,100),Triple("adobe-photo.jpg",portra,100))) {
            val uri = Uri.fromFile(fixture(name))
            val source = engine.decodePreview(uri)
            val expected = engine.render(source,film,{}, { false },intensity)
            val file = File(context.cacheDir,"quality-stream-${name.substringBeforeLast('.')}-${film.token}-$intensity.jpg")
            try {
                val result = engine.export(uri,film,Uri.fromFile(file),{}, { false },intensity)
                assertFalse(result.resized)
                val actual = engine.decodePreview(Uri.fromFile(file))
                try {
                    assertEquals(expected.width,actual.width); assertEquals(expected.height,actual.height)
                    assertEquals(expected.width,result.width); assertEquals(expected.height,result.height)
                    var squared = 0.0
                    for (y in 0 until actual.height) for (x in 0 until actual.width) {
                        val a = actual.getPixel(x,y); val b = expected.getPixel(x,y)
                        for (shift in intArrayOf(0,8,16)) {
                            val error = ((a ushr shift) and 255)-((b ushr shift) and 255)
                            squared += error.toDouble()*error
                        }
                    }
                    val rmse = kotlin.math.sqrt(squared/(actual.width.toLong()*actual.height*3))
                    assertTrue("$name/${film.token}/$intensity JPEG color/orientation RMSE $rmse",rmse < 3.0)
                    android.util.Log.i("CXOProduct","Stream JPEG $name ${film.token} strength$intensity RMSE=$rmse")
                } finally { actual.recycle() }
            } finally { source.recycle(); expected.recycle() }
        }
    }

    fun testSourceCannotBeOverwrittenAndCancellationRemovesContentOutput() {
        val engine = PhotoProcessor(context)
        val film = engine.films.first { it.token == "KodakPortra400" }
        val input = fixture("odd-photo.png")
        val original = input.readBytes()
        try {
            engine.export(Uri.fromFile(input),film,Uri.fromFile(input),{}, { false })
            fail("overwriting the input should fail")
        } catch (_: IllegalArgumentException) { }
        assertTrue(original.contentEquals(input.readBytes()))
        val output = Uri.parse("content://dev.cxo.filmpack.app.test.photos/cancelled-stream.jpg")
        var cancel = false
        try {
            engine.export(Uri.fromFile(input),film,output,{ if (it >= 10) cancel = true }, { cancel })
            fail("cancelled streaming export should fail")
        } catch (_: java.util.concurrent.CancellationException) { }
        try {
            context.contentResolver.openInputStream(output)?.use { fail("partial content output remains") }
        } catch (_: java.io.FileNotFoundException) { }
        val recovered = File(context.cacheDir,"stream-after-cancel.jpg")
        engine.export(Uri.fromFile(input),film,Uri.fromFile(recovered),{}, { false })
        assertNotNull(BitmapFactory.decodeFile(recovered.path)?.also { it.recycle() })
    }
}
