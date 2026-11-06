package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.LcmsOptimizedQ15Transform
import dev.cxo.filmpack.core.NativeGrain8ColorLutFactory
import dev.cxo.filmpack.core.NativeGrain8Prepare
import dev.cxo.filmpack.core.verifyGrainEndToEnd
import dev.cxo.filmpack.core.verifyMultifilmGrainPreparation
import java.io.DataInputStream

class NativeGrainPreparationTest : InstrumentationTestCase() {
    fun testSixAdditionalFilmSourcesMatchNativePreparation() {
        val assets = instrumentation.context.assets
        val colorLut = NativeGrain8ColorLutFactory.fromQ15Transform(
            LcmsOptimizedQ15Transform.read(assets.open("grain-adobe-to-widegamut.lcmsbin").use { it.readBytes() }),
        )
        val count = assets.open("grain-multifilm-preparation.golden").use {
            verifyMultifilmGrainPreparation(it, colorLut)
        }
        assertEquals(6, count)
    }

    fun testPreparationAndOverlayMatchFullNativeNodes() {
        val assets = instrumentation.context.assets
        val colorLut = NativeGrain8ColorLutFactory.fromQ15Transform(
            LcmsOptimizedQ15Transform.read(assets.open("grain-adobe-to-widegamut.lcmsbin").use { it.readBytes() }),
        )
        val pixels = assets.open("grain-end-to-end.golden").use { verifyGrainEndToEnd(it, colorLut) }
        assertEquals(4 * 1024 * 1024, pixels)
    }

    fun testSourceIccToFullNativePreparation() {
        val assets = instrumentation.context.assets
        val colorLut = NativeGrain8ColorLutFactory.fromQ15Transform(
            LcmsOptimizedQ15Transform.read(assets.open("grain-adobe-to-widegamut.lcmsbin").use { it.readBytes() }),
        )
        var cases = 0
        DataInputStream(assets.open("grain-preparation.golden").buffered()).use { input ->
            fun bytes(n: Int) = ByteArray(n).also(input::readFully)
            fun i32() = Integer.reverseBytes(input.readInt())
            assertEquals("CXOGPRE1", String(bytes(8), Charsets.US_ASCII))
            assertEquals(1, i32())
            val count = i32()
            val source = bytes(1000 * 1000 * 3); val before = source.copyOf()
            repeat(count) { record ->
                val scale = Float.fromBits(i32()); val seed = i32().toLong() and 0xFFFFFFFFL
                val size = i32(); val period = i32(); val byteCount = i32()
                val expected = bytes(byteCount)
                val actual = NativeGrain8Prepare.prepare(source, scale, colorLut, seed)
                assertEquals(size, actual.size); assertEquals(period, actual.period)
                assertTrue("Native preparation record $record differs", actual.bytes.contentEquals(expected))
                assertTrue("Source was changed", source.contentEquals(before))
                cases++
            }
            assertEquals(-1, input.read())
        }
        assertEquals(4, cases)
    }
}
