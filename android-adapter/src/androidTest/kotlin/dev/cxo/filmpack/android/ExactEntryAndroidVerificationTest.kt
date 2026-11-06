package dev.cxo.filmpack.android

import android.test.AndroidTestCase
import android.util.Log
import dev.cxo.filmpack.core.FixedSrgbEntryLut
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Android-runtime check of the recovered sampling0 entry worker. */
@Suppress("DEPRECATION")
class ExactEntryAndroidVerificationTest : AndroidTestCase() {
    fun testSampling0EntryFixtureBitExactly() {
        fun bytes(name: String) = context.assets.open("entry-exact/$name").use { it.readBytes() }
        val gridBytes = bytes("grid.f32le")
        val grid = FloatArray(gridBytes.size / 4)
        ByteBuffer.wrap(gridBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(grid)
        check(grid.size == FixedSrgbEntryLut.GRID_FLOATS)
        val sourceBytes = bytes("root.q15le")
        val expectedBytes = bytes("native.q15le")
        check(sourceBytes.size == 3_145_728 * 2 && expectedBytes.size == sourceBytes.size)
        val source = ShortArray(sourceBytes.size / 2)
        val expected = ShortArray(expectedBytes.size / 2)
        ByteBuffer.wrap(sourceBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(source)
        ByteBuffer.wrap(expectedBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(expected)
        val carrier = ShortArray(source.size) { (source[it].toInt() shl 1).toShort() }
        val entry = FixedSrgbEntryLut(
            grid, FixedSrgbEntryLut.SOURCE_ICC_SHA256, FixedSrgbEntryLut.TARGET_ICC_SHA256,
            2170, 0x40, 0,
        )
        val actual = FloatArray(source.size)
        entry.renderRgb16ExactSampling0(carrier, actual, 1024)
        var unequal = 0
        var maxAbs = 0
        for (i in actual.indices) {
            val q15 = Math.rint(actual[i].toDouble() * 32767.0).toInt().coerceIn(-32768, 32767)
            val delta = q15 - expected[i].toInt()
            if (delta != 0) unequal++
            maxAbs = maxOf(maxAbs, abs(delta))
        }
        check(unequal == 0) { "sampling0 mismatch components=$unequal maxAbs=$maxAbs" }
        Log.i("ExactEntryAndroidVerification", "components=${actual.size} unequal_q15_components=$unequal max_abs_q15=$maxAbs exact=true")
    }
}
