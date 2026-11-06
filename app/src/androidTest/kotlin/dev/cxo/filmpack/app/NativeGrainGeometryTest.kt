package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.*
import java.util.zip.InflaterInputStream

class NativeGrainGeometryTest : InstrumentationTestCase() {
    fun testNativeManualAndFilmFormatGeometry() {
        val assets = instrumentation.context.assets
        val catalog = NativeGrain8Catalog.read(assets.open("grain-catalog.cxograin").use { it.readBytes() })
        val colorLut = NativeGrain8ColorLutFactory.fromQ15Transform(
            LcmsOptimizedQ15Transform.read(assets.open("grain-adobe-to-widegamut.lcmsbin").use { it.readBytes() }),
        )
        val sources = mutableMapOf<Int, ByteArray>()
        val result = assets.open("grain-geometry.golden").use { fixture ->
            verifyGrainGeometry(fixture, catalog, colorLut, source = { grainEnum ->
                sources.getOrPut(grainEnum) {
                    InflaterInputStream(assets.open("grain-catalog-sources/$grainEnum.rgbz")).use { it.readBytes() }
                }
            })
        }
        assertEquals(14, result.records)
        assertEquals(11_630_388L, result.preparedComponents)
        assertEquals(65_502L, result.rowPixels)
    }
}
