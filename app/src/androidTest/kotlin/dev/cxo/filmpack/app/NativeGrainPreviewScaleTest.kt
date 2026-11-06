package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.*
import java.util.zip.InflaterInputStream

class NativeGrainPreviewScaleTest : InstrumentationTestCase() {
    fun testNativePreviewScalePreparationAndCoordinates() {
        val assets = instrumentation.context.assets
        val catalog = NativeGrain8Catalog.read(assets.open("grain-catalog.cxograin").use { it.readBytes() })
        val definition = requireNotNull(catalog["KodakPortra400"])
        val source = InflaterInputStream(assets.open("grain-catalog-sources/${definition.grainEnum}.rgbz")).use { it.readBytes() }
        val lut = NativeGrain8ColorLutFactory.fromQ15Transform(
            LcmsOptimizedQ15Transform.read(assets.open("grain-adobe-to-widegamut.lcmsbin").use { it.readBytes() }),
        )
        val pixels = assets.open("grain-preview-scale.golden").use {
            verifyGrainPreviewScale(it,definition,source,lut)
        }
        assertEquals(327_680L,pixels)
    }

    fun testAutoSizingOddExtentsAndNativeNonzeroRoi() {
        val assets = instrumentation.context.assets
        val catalog = NativeGrain8Catalog.read(assets.open("grain-catalog.cxograin").use { it.readBytes() })
        val definition = requireNotNull(catalog["KodakPortra400"])
        val source = InflaterInputStream(assets.open("grain-catalog-sources/${definition.grainEnum}.rgbz")).use { it.readBytes() }
        val lut = NativeGrain8ColorLutFactory.fromQ15Transform(
            LcmsOptimizedQ15Transform.read(assets.open("grain-adobe-to-widegamut.lcmsbin").use { it.readBytes() }),
        )
        val result = assets.open("grain-preview-geometry.golden").use {
            val loader = PhotoGrainLoader(catalog,lut) { source }
            verifyGrainPreviewGeometry(it,definition,source,lut) { case ->
                loader.prepare(PhotoGrainRequest(case.sourceWidth,case.sourceHeight,1,definition.identifier,
                    case.format,case.manual,100,case.seed,case.color,case.neutralize,case.level)).kernel
            }
        }
        assertEquals(7,result.records)
        assertEquals(907_869L,result.preparedComponents)
        assertEquals(1_486_848L,result.pixels)
    }
}
