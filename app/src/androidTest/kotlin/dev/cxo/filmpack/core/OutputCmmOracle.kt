package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.InputStream

fun verifyOutputCmm(fixture: InputStream, transform: FixedWideGamutToSrgb): Int = DataInputStream(fixture.buffered()).use { input ->
    fun bytes(n: Int) = ByteArray(n).also(input::readFully)
    fun i32() = Integer.reverseBytes(input.readInt())
    fun i16() = java.lang.Short.reverseBytes(input.readShort())
    check(String(bytes(8), Charsets.US_ASCII) == "CXOO16V1")
    check(i32() == 1)
    val rows = i32(); check(rows == 132768)
    val source = FloatArray(rows*3); val expected = FloatArray(rows*3)
    repeat(rows) { row ->
        repeat(3) { c -> source[row*3+c] = i16().toInt().coerceAtLeast(0).toFloat()/32767f }
        val codes = IntArray(3) { i16().toInt() and 65535 }
        repeat(3) { c ->
            expected[row*3+c] = Float.fromBits(i32())
            check(expected[row*3+c].toRawBits() == (codes[c].toFloat()/65535f).toRawBits())
        }
    }
    check(input.read() == -1)
    val output = FloatArray(source.size)
    transform.render(source,output)
    output.indices.forEach { i -> check(output[i].toRawBits() == expected[i].toRawBits()) { "Output CMM differs at $i" } }
    transform.render(source,source)
    check(source.contentEquals(output))
    val sentinel = floatArrayOf(.1f,.2f,.3f)
    val snapshot = sentinel.copyOf()
    listOf(Float.NaN,Float.POSITIVE_INFINITY,-.01f,1.01f).forEach { bad ->
        check(runCatching { transform.render(floatArrayOf(.5f,bad,.5f),sentinel) }.isFailure)
        check(sentinel.contentEquals(snapshot))
    }
    rows
}
