package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.*

class NativeGrainOrientationTest : InstrumentationTestCase() {
    fun testAllNativeOrientationLeaves() {
        val pixels = instrumentation.context.assets.open("grain-orientation.golden").use(::verifyGrainOrientation)
        assertEquals(832L, pixels)
    }

    fun testOrientedPreparationAndFullNativeNodes() {
        val assets = instrumentation.context.assets
        val lut = NativeGrain8ColorLutFactory.fromQ15Transform(
            LcmsOptimizedQ15Transform.read(assets.open("grain-adobe-to-widegamut.lcmsbin").use { it.readBytes() }),
        )
        val pixels = assets.open("grain-oriented-preparation.golden").use {
            verifyGrainOrientedPreparation(it, lut)
        }
        assertEquals(3_145_728L, pixels)
    }
}
