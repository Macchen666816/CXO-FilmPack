package dev.cxo.filmpack.app

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.NativeGrain8Geometry
import java.io.File
import kotlin.math.sqrt

/** Product-layout validation, distinct from original-engine geometry evidence. */
class GrainProductGeometryTest : InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext

    fun testAllExifDisplayPreviewsMatchStreamingGrainSaves() {
        val engine=PhotoProcessor(context)
        val film=engine.films.first { it.token=="KodakPortra400" }
        val raw=Bitmap.createBitmap(137,89,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(110,130,160)) }
        try {
            GrainPackStore(context).use { store ->
                assertEquals(99,store.openInstalled().size)
                for (orientation in 1..8) {
                    val source=File(context.cacheDir,"product-exif-$orientation.jpg")
                    source.outputStream().use { raw.compress(Bitmap.CompressFormat.JPEG,100,it) }
                    ExifInterface(source.path).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes()
                    }
                    val preview=engine.decodeGrainPreview(Uri.fromFile(source))
                    try {
                        assertEquals(orientation,preview.sourceExifOrientation)
                        assertEquals(if (orientation>=5) 89 else 137,preview.bitmap.width)
                        val layer=store.prepare(engine.grainRequest(preview,film,"KodakPortra400",
                            NativeGrain8Geometry.Format.MANUAL,1f,100,17))
                        val rendered=engine.render(preview.bitmap,film,{}, { false },100,layer)
                        val output=File(context.cacheDir,"product-grain-exif-$orientation.jpg")
                        try {
                            engine.export(Uri.fromFile(source),film,Uri.fromFile(output),{}, { false },100,layer)
                            val actual=engine.decodePreview(Uri.fromFile(output))
                            try {
                                assertEquals(rendered.width,actual.width);assertEquals(rendered.height,actual.height)
                                var sum=0.0
                                for (y in 0 until actual.height) for (x in 0 until actual.width) {
                                    val a=actual.getPixel(x,y);val b=rendered.getPixel(x,y)
                                    for (s in intArrayOf(0,8,16)) {
                                        val d=((a ushr s) and 255)-((b ushr s) and 255);sum+=d.toDouble()*d
                                    }
                                }
                                val rmse=sqrt(sum/(actual.width.toLong()*actual.height*3))
                                android.util.Log.i("CXOGrainProduct","EXIF$orientation grain display/save RMSE=$rmse")
                                assertTrue("EXIF$orientation grain phase/rotation mismatch: $rmse",rmse<3)
                            } finally { actual.recycle() }
                        } finally { rendered.recycle() }
                    } finally { preview.bitmap.recycle() }
                }
            }
        } finally { raw.recycle() }
    }

    fun testLargePreviewAndOriginalSizeSaveWithIndependentGrainSelection() {
        val source=File(context.cacheDir,"product-large-source.jpg")
        instrumentation.context.assets.open("large-photo-24mp.jpg").use { input -> source.outputStream().use { input.copyTo(it) } }
        val engine=PhotoProcessor(context)
        val film=engine.films.first { it.token=="KodakTriX400" }
        val preview=engine.decodeGrainPreview(Uri.fromFile(source))
        try {
            assertEquals(2,preview.invocationScale)
            assertEquals(1500,preview.bitmap.width);assertEquals(1000,preview.bitmap.height)
            GrainPackStore(context).use { store ->
                // Rendering BW flags must come from Tri-X, despite a Portra source.
                val request=engine.grainRequest(preview,film,"KodakPortra400",NativeGrain8Geometry.Format.SMALL,1f,100,17)
                assertFalse(request.color)
                val previewLayer=store.prepare(request)
                val actual=engine.render(preview.bitmap,film,{}, { false },100,previewLayer)
                actual.recycle()
                val destination=File(context.cacheDir,"product-grain-large.jpg")
                val failure=runCatching { engine.export(Uri.fromFile(source),film,Uri.fromFile(destination),{}, { false },100,previewLayer) }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException);assertFalse(destination.exists())
                val full=store.prepare(request.copy(invocationScale=0))
                var cancelled=false
                val stopped=runCatching { engine.export(Uri.fromFile(source),film,Uri.fromFile(destination),
                    { if (it>=10) cancelled=true },{ cancelled },100,full) }.exceptionOrNull()
                assertTrue(stopped is java.util.concurrent.CancellationException);assertFalse(destination.exists())
                val result=engine.export(Uri.fromFile(source),film,Uri.fromFile(destination),{}, { false },100,full)
                assertFalse(result.resized);assertEquals(6000,result.width);assertEquals(4000,result.height)
                android.util.Log.i("CXOGrainProduct","24MP grain export ${result.elapsedMillis}ms ${destination.length()}bytes")
            }
        } finally { preview.bitmap.recycle() }
    }
}
