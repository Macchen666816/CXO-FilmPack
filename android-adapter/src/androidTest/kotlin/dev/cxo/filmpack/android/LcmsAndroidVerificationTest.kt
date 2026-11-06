package dev.cxo.filmpack.android

import android.test.AndroidTestCase
import android.util.Log
import dev.cxo.filmpack.core.LcmsOptimizedQ15Transform
import java.io.DataInputStream

/** Run the complete independent JVM fixture on Android's actual runtime. */
@Suppress("DEPRECATION")
class LcmsAndroidVerificationTest : AndroidTestCase() {
    fun testLcmsFixtureBitExactly() = verifyFixture("adobe-to-srgb.lcmsbin", "validation.golden")

    fun testLcmsSourceProfileFixtureBitExactly() = verifyFixture("adobe-to-source-srgb.lcmsbin", "source-validation.golden")

    private fun verifyFixture(resource: String, fixture: String) {
        val assets = context.assets
        val transform = assets.open("lcms-2170/$resource").use {
            LcmsOptimizedQ15Transform.read(it.readBytes())
        }
        DataInputStream(assets.open("lcms-2170/$fixture").buffered()).use { data ->
            val magic = ByteArray(8).also { data.readFully(it) }
            check(magic.contentEquals("CXOLCMV1".toByteArray(Charsets.US_ASCII)))
            fun int() = Integer.reverseBytes(data.readInt())
            fun short() = java.lang.Short.reverseBytes(data.readShort())
            fun float() = Float.fromBits(int())
            val nativeRows = int()
            val independentRows = int()
            check(nativeRows == 5516 && independentRows == 201473)
            repeat(nativeRows) { row ->
                val input = FloatArray(3) { float() }
                val expected = FloatArray(3) { float() }
                val inputQ15 = ShortArray(3) { short() }
                val expectedQ15 = ShortArray(3) { short() }
                val actualQ15 = transform.transform(inputQ15)
                val actual = transform.convert(input)
                check(actualQ15.contentEquals(expectedQ15)) { "Q15 mismatch row $row" }
                for (channel in 0..2) {
                    check(actual[channel].toRawBits() == expected[channel].toRawBits()) {
                        "Float bits mismatch row $row channel $channel"
                    }
                }
            }
            repeat(independentRows) { row ->
                val input = IntArray(3) { short().toInt() and 65535 }
                val expected = IntArray(3) { short().toInt() and 65535 }
                val actual = transform.transformUnsigned16(input)
                check(actual.contentEquals(expected)) { "RGB16 mismatch row $row" }
            }
            check(data.read() == -1) { "Unexpected fixture trailing bytes" }
            Log.i("LcmsAndroidVerification", "resource=$resource native_float_q15_rows=$nativeRows independent_rgb16_rows=$independentRows all_components_exact=true")
        }
    }
}
