package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.*
import java.util.zip.InflaterInputStream

class NativeGrainUpscaleTest : InstrumentationTestCase() {
    fun testFullPolachromePreparationAndNode() {
        val assets = instrumentation.context.assets
        val catalog = NativeGrain8Catalog.read(assets.open("grain-catalog.cxograin").use { it.readBytes() })
        val colorLut = NativeGrain8ColorLutFactory.fromQ15Transform(
            LcmsOptimizedQ15Transform.read(assets.open("grain-adobe-to-widegamut.lcmsbin").use { it.readBytes() }),
        )
        val source = InflaterInputStream(assets.open("grain-catalog-sources/1023.rgbz")).use { it.readBytes() }
        val result = assets.open("grain-polachrome-upscale.golden").use {
            verifyGrainUpscale(it, catalog, colorLut, source)
        }
        assertEquals(3_288_627, result.preparedComponents)
        assertEquals(1_048_576, result.nodePixels)
    }
}
