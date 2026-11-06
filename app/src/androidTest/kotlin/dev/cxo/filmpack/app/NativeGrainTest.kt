package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.verifyNativeGrainOracle

class NativeGrainTest : InstrumentationTestCase() {
    fun testPreparedGrainMatchesOriginalLeafKernel() {
        val count = instrumentation.context.assets.open("portra400-kernel.golden").use(::verifyNativeGrainOracle)
        assertEquals(1076220, count)
    }
}
