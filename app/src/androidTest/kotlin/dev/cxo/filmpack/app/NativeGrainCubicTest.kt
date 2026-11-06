package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.verifyGrainCubic

class NativeGrainCubicTest : InstrumentationTestCase() {
    fun testNativeCubicResize() {
        val pixels = instrumentation.context.assets.open("grain-cubic.golden").use(::verifyGrainCubic)
        assertEquals(1_102_346L, pixels)
    }
}
