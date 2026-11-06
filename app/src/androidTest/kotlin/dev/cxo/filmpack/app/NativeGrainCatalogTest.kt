package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.LcmsOptimizedQ15Transform
import dev.cxo.filmpack.core.NativeGrain8Catalog
import dev.cxo.filmpack.core.NativeGrain8ColorLutFactory
import dev.cxo.filmpack.core.verifyGrainCatalog
import java.util.zip.InflaterInputStream

class NativeGrainCatalogTest : InstrumentationTestCase() {
    fun testAllSourceGrainDefinitionsAndNativeRows() {
        val assets = instrumentation.context.assets
        val catalog = NativeGrain8Catalog.read(assets.open("grain-catalog.cxograin").use { it.readBytes() })
        val colorLut = NativeGrain8ColorLutFactory.fromQ15Transform(
            LcmsOptimizedQ15Transform.read(assets.open("grain-adobe-to-widegamut.lcmsbin").use { it.readBytes() }),
        )
        val result = assets.open("grain-catalog-validation.golden").use { golden ->
            verifyGrainCatalog(catalog, colorLut, golden, source = { grainEnum ->
                InflaterInputStream(assets.open("grain-catalog-sources/$grainEnum.rgbz")).use { it.readBytes() }
            })
        }
        assertEquals(99, result.entries)
        assertTrue(result.preparedComponents > 29_000_000L)
        assertEquals(99L * 21L * 1009L, result.sampledNodePixels)
        assertNull(catalog["Original"]); assertNull(catalog["Astia"])
    }
}
