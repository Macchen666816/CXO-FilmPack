package dev.cxo.filmpack.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.*
import java.io.File
import java.util.zip.InflaterInputStream

class PhotoGrainPreviewTest : InstrumentationTestCase() {
    private fun fixture(name: String) = File(instrumentation.targetContext.cacheDir,name).also { file ->
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
    }

    fun testOddPreviewRetainsSourceGridAndExportRequiresFullResolutionLayer() {
        val context = instrumentation.targetContext
        val engine = PhotoProcessor(context)
        val file = File(context.cacheDir,"grain-odd-preview.png")
        val bitmap = Bitmap.createBitmap(1537,1025,Bitmap.Config.ARGB_8888)
        val row = IntArray(bitmap.width)
        try {
            for (y in 0 until bitmap.height) {
                for (x in row.indices) row[x] = Color.rgb(x%256,y%256,(x+y)%256)
                bitmap.setPixels(row,0,row.size,0,y,row.size,1)
            }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
        val preview = engine.decodeGrainPreview(Uri.fromFile(file),maxEdge=1000)
        try {
            assertEquals(1537,preview.sourceWidth); assertEquals(1025,preview.sourceHeight)
            assertEquals(1,preview.invocationScale)
            assertEquals(768,preview.bitmap.width); assertEquals(512,preview.bitmap.height)
            val assets = instrumentation.context.assets
            val catalog = NativeGrain8Catalog.read(assets.open("grain-catalog.cxograin").use { it.readBytes() })
            val lut = NativeGrain8ColorLutFactory.fromQ15Transform(LcmsOptimizedQ15Transform.read(
                assets.open("grain-adobe-to-widegamut.lcmsbin").use { it.readBytes() }))
            val loader = PhotoGrainLoader(catalog,lut) { definition ->
                InflaterInputStream(assets.open("grain-catalog-sources/${definition.grainEnum}.rgbz")).use { it.readBytes() }
            }
            val request = preview.request("KodakPortra400",NativeGrain8Geometry.Format.SMALL,1f,100,17,true,false)
            assertEquals(1537,request.sourceWidth); assertEquals(1,request.invocationScale)
            val grain = loader.prepare(request)
            assertEquals(768,grain.width); assertEquals(512,grain.height)
            val film = engine.films.first { it.token == "KodakPortra400" }
            val rendered = engine.render(preview.bitmap,film,{}, { false },100,grain)
            try { assertEquals(768,rendered.width); assertEquals(512,rendered.height) }
            finally { rendered.recycle() }
            val output = File(context.cacheDir,"grain-odd-preview-export.jpg")
            output.delete()
            val failure = runCatching { engine.export(Uri.fromFile(file),film,Uri.fromFile(output),{}, { false },100,grain) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertFalse(output.exists())
            val full = loader.prepare(request.copy(invocationScale=0))
            val result = engine.export(Uri.fromFile(file),film,Uri.fromFile(output),{}, { false },100,full)
            assertFalse(result.resized); assertEquals(1537,result.width); assertEquals(1025,result.height)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFile(output.path,bounds)
            assertEquals(1537,bounds.outWidth); assertEquals(1025,bounds.outHeight)
        } finally { preview.bitmap.recycle() }
    }

    fun testBoundedLargePreviewAndUnsupportedMetadata() {
        val engine = PhotoProcessor(instrumentation.targetContext)
        val file = fixture("large-photo-24mp.jpg")
        val preview = engine.decodeGrainPreview(Uri.fromFile(file))
        try {
            assertEquals(6000,preview.sourceWidth); assertEquals(4000,preview.sourceHeight)
            assertEquals(2,preview.invocationScale)
            assertEquals(1500,preview.bitmap.width); assertEquals(1000,preview.bitmap.height)
        } finally { preview.bitmap.recycle() }
        assertTrue(runCatching { engine.decodeGrainPreview(Uri.fromFile(file),maxEdge=1000) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { engine.decodeGrainPreview(Uri.fromFile(file),maxPixels=4_000_001) }.exceptionOrNull() is IllegalArgumentException)
        val oriented = engine.decodeGrainPreview(Uri.fromFile(fixture("orientation6.jpg")))
        try {
            assertEquals(80,oriented.sourceWidth)
            assertEquals(40,oriented.sourceHeight)
            assertEquals(6,oriented.sourceExifOrientation)
            assertEquals(40,oriented.bitmap.width)
            assertEquals(80,oriented.bitmap.height)
        } finally { oriented.bitmap.recycle() }
    }
}
