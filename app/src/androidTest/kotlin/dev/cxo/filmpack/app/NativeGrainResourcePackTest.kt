package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.*
import java.io.File

class NativeGrainResourcePackTest : InstrumentationTestCase() {
    private fun pack() = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
        "grain-resource-verification.cxograinpack")

    fun testAllExternalSourcesPrepareAndMatchNativeRows() {
        val result = instrumentation.context.assets.open("grain-catalog-validation.golden").use {
            verifyGrainResourcePack(pack(),it)
        }
        assertEquals(99,result.entries)
        assertEquals(29_341_200L,result.preparedComponents)
        assertEquals(2_097_711L,result.sampledNodePixels)
    }

    fun testMalformedPackBoundsAndLoaderPreview() {
        val directory = File(instrumentation.targetContext.cacheDir,"grain-pack-malformed")
        try { assertEquals(8,verifyGrainResourcePackFailures(pack(),directory)) }
        finally { directory.deleteRecursively() }
        NativeGrain8Resources.open(pack()).use { resources ->
            val loader = PhotoGrainLoader.fromResources(resources)
            val result = instrumentation.context.assets.open("grain-preview-geometry.golden").use {
                verifyGrainPreviewGeometry(it,requireNotNull(resources.catalog["KodakPortra400"]),
                    resources.readSource(1263),resources.colorLut()) { case ->
                    loader.prepare(PhotoGrainRequest(case.sourceWidth,case.sourceHeight,1,"KodakPortra400",
                        case.format,case.manual,100,case.seed,case.color,case.neutralize,case.level)).kernel
                }
            }
            assertEquals(1_486_848L,result.pixels)
        }
    }
}
