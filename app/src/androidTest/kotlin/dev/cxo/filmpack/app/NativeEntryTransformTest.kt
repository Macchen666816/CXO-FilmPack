package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.FixedSrgbEntryLut
import dev.cxo.filmpack.core.FixedSrgbEntryLutIO
import java.util.Random

class NativeEntryTransformTest : InstrumentationTestCase() {
    fun testExplicitDisableAlwaysAllowsKotlinFallback() {
        assertNull(NativeEntryTransform.create(FloatArray(0), enabled = false))
    }

    fun testProductGridMatchesKotlinBitsAcrossLanesAndPartialRows() {
        val entry = instrumentation.targetContext.assets.open("films/srgb-entry-pix65.cxoentry")
            .buffered().use(FixedSrgbEntryLutIO::read)
        val native = NativeEntryTransform.create(entry.copyGrid())
        assertEquals(NativeEntryTransform.isSupported, native != null)
        if (native == null) return // Unsupported process ABI keeps the core fallback.

        val random = Random(0x43584f454e545259L)
        val edge = intArrayOf(0, 1, 2, 3, 1023, 1024, 32766, 32767, 32768, 65532, 65533, 65534, 65535)
        for (width in intArrayOf(1, 2, 3, 4, 5, 7, 16, 31, 47, 257)) {
            // A partial final row exercises the reference's pixel % width contract.
            val source = ShortArray((width * 3 + 11) * 3) { i ->
                (if (i < edge.size * 3) edge[(i / 3 + i % 3 * 5) % edge.size] else random.nextInt(65536)).toShort()
            }
            for (xOffset in intArrayOf(0, 1, 2, 3, 7, Int.MAX_VALUE)) {
                assertSameBits(entry, native, source, width, xOffset)
            }
        }
    }

    fun testFractionalGridFmaAndHalfEvenQuantizationMatch() {
        if (!NativeEntryTransform.isSupported) return
        val random = Random(0x454e545259464d41L)
        val grid = FloatArray(FixedSrgbEntryLut.GRID_FLOATS) { i ->
            if (i % 4 == 3) 0f else random.nextInt(32767 * 128 + 1).toFloat() / 128f
        }
        // At the origin all interpolation weights are exact: 0.5 -> 0,
        // 1.5 -> 2, and 2.5 -> 2, independently of the caller's lane.
        grid[0] = .5f; grid[1] = 1.5f; grid[2] = 2.5f
        val entry = FixedSrgbEntryLut(grid, FixedSrgbEntryLut.SOURCE_ICC_SHA256,
            FixedSrgbEntryLut.TARGET_ICC_SHA256, 2170, 0x40, 0)
        val native = NativeEntryTransform.create(grid)!!
        for (width in intArrayOf(1, 3, 4, 5, 79)) {
            val source = ShortArray(8195 * 3) { random.nextInt(65536).toShort() }
            source[0] = 0; source[1] = 0; source[2] = 0
            for (offset in 0..3) assertSameBits(entry, native, source, width, offset)
        }
        val result = FloatArray(3)
        native.render(ShortArray(3), result, 1)
        assertEquals(0f.toRawBits(), result[0].toRawBits())
        assertEquals((2f / 32767f).toRawBits(), result[1].toRawBits())
        assertEquals((2f / 32767f).toRawBits(), result[2].toRawBits())
    }

    fun testInvalidTileLeavesDestinationIntact() {
        if (!NativeEntryTransform.isSupported) return
        val native = NativeEntryTransform.create(FloatArray(FixedSrgbEntryLut.GRID_FLOATS))!!
        val destination = FloatArray(6) { .25f }
        val expected = destination.copyOf()
        for (invalid in listOf<() -> Unit>(
            { native.render(ShortArray(5), destination, 1) },
            { native.render(ShortArray(3), destination, 1) },
            { native.render(ShortArray(6), destination, 0) },
            { native.render(ShortArray(6), destination, 2, -1) },
        )) {
            assertTrue(runCatching(invalid).exceptionOrNull() is IllegalArgumentException)
            assertTrue(destination.contentEquals(expected))
        }
        native.render(ShortArray(0), FloatArray(0), 1)
    }

    private fun assertSameBits(entry: FixedSrgbEntryLut, native: NativeEntryTransform,
        source: ShortArray, width: Int, xOffset: Int,
    ) {
        val expected = FloatArray(source.size)
        val actual = FloatArray(source.size)
        entry.renderRgb16ExactSampling0(source, expected, width, xOffset)
        native.render(source, actual, width, xOffset)
        for (i in expected.indices) {
            assertEquals("RGB component $i, width=$width, xOffset=$xOffset", expected[i].toRawBits(), actual[i].toRawBits())
        }
    }
}
