package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.*
import java.util.zip.InflaterInputStream

class NativeGrainPhotoBoundaryTest : InstrumentationTestCase() {
    fun testNativePhotoColorToGrainBoundary() {
        val assets = instrumentation.context.assets
        val model = instrumentation.targetContext.assets
        val catalog = NativeGrain8Catalog.read(assets.open("grain-catalog.cxograin").use { it.readBytes() })
        val lut = NativeGrain8ColorLutFactory.fromQ15Transform(LcmsOptimizedQ15Transform.read(
            assets.open("grain-adobe-to-widegamut.lcmsbin").use { it.readBytes() }))
        val entry = model.open("films/srgb-entry-pix65.cxoentry").use(FixedSrgbEntryLutIO::read)
        val sources = mutableMapOf<Int, ByteArray>()
        val rows = assets.open("grain-photo-boundary.golden").use { fixture ->
            verifyGrainPhotoBoundary(fixture,catalog,lut,entry,openFilm = model::open,grainSource = { id ->
                sources.getOrPut(id) { InflaterInputStream(assets.open("grain-catalog-sources/$id.rgbz")).use { it.readBytes() } }
            })
        }
        assertEquals(5,rows.size)
    }

    fun testNormalizedBoundaryAndInvalidInput() {
        for (i in 0..32767) assertEquals(i,Math.rint(((i.toFloat()/32767f)*32767f).toDouble()).toInt())
        val kernel = NativeGrain8Patch(byteArrayOf(127,127,127),1,1,1,IntArray(256))
        val destination = floatArrayOf(.2f,.3f,.4f)
        val snapshot = destination.copyOf()
        for (bad in listOf(Float.NaN,Float.POSITIVE_INFINITY,-.01f,1.01f)) {
            assertTrue(runCatching { kernel.renderNormalizedRgb(floatArrayOf(.5f,bad,.5f),destination,1) }.isFailure)
            assertTrue(destination.contentEquals(snapshot))
        }
        assertTrue(runCatching { kernel.renderNormalizedRgb(floatArrayOf(.5f,.5f,.5f),destination,1,-1,0) }.isFailure)
        assertTrue(destination.contentEquals(snapshot))
    }
}
