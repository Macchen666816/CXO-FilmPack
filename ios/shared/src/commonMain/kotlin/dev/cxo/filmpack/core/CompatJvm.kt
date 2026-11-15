package dev.cxo.filmpack.core

import java.io.Closeable
import kotlin.math.pow
import kotlin.math.round

// Same-package stand-ins for the handful of java.lang statics the core uses, so the
// numerical code stays byte-for-byte what the Android build runs.

/** Minimal charset stand-ins: the core only ever uses UTF-8 and US-ASCII. */
internal class Charset(val name: String)
internal object Charsets {
    val UTF_8 = Charset("UTF-8")
    val US_ASCII = Charset("US-ASCII")
    val ISO_8859_1 = Charset("ISO-8859-1")
}

internal fun ByteArray.inputStream(): java.io.ByteArrayInputStream = java.io.ByteArrayInputStream(this)

internal object Integer {
    fun reverseBytes(i: Int): Int =
        (i ushr 24) or ((i shr 8) and 0xff00) or ((i shl 8) and 0xff0000) or (i shl 24)
    fun numberOfLeadingZeros(i: Int): Int = i.countLeadingZeroBits()
}

internal fun reverseBytesShort(s: Short): Short {
    val i = s.toInt()
    return (((i and 0xff) shl 8) or ((i shr 8) and 0xff)).toShort()
}

internal fun reverseBytesLong(l: Long): Long =
    (Integer.reverseBytes(l.toInt()).toLong() shl 32) or (Integer.reverseBytes((l ushr 32).toInt()).toLong() and 0xffffffffL)

internal object Math {
    /** Round half to even, exactly java.lang.Math.rint. */
    fun rint(x: Double): Double = round(x)

    fun multiplyExact(a: Int, b: Int): Int {
        val r = a.toLong() * b.toLong()
        if (r != r.toInt().toLong()) throw ArithmeticException("integer overflow")
        return r.toInt()
    }

    fun nextUp(f: Float): Float {
        if (f.isNaN() || f == Float.POSITIVE_INFINITY) return f
        if (f == 0f) return Float.fromBits(1)
        val bits = f.toRawBits()
        return Float.fromBits(if (f > 0f) bits + 1 else bits - 1)
    }
    fun nextDown(f: Float): Float = -nextUp(-f)

    /** x * 2^n for an exactly representable scale, as java.lang.Math.scalb(double,int). */
    fun scalb(x: Double, n: Int): Double = x * 2.0.pow(n)
}

internal inline fun <T : Closeable?, R> T.use(block: (T) -> R): R {
    var failure: Throwable? = null
    try {
        return block(this)
    } catch (t: Throwable) {
        failure = t; throw t
    } finally {
        if (this != null) {
            if (failure == null) close() else try { close() } catch (_: Throwable) {}
        }
    }
}

// JVM-only String constructors / ByteArray.toString(Charset) used by the asset readers.
internal fun String(bytes: ByteArray, charset: Charset): String = decode(bytes, 0, bytes.size, charset)
internal fun String(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String = decode(bytes, offset, length, charset)
internal fun ByteArray.toString(charset: Charset): String = decode(this, 0, size, charset)

private fun decode(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String =
    if (charset == Charsets.US_ASCII) {
        buildString(length) { for (i in offset until offset + length) append(if (bytes[i] >= 0) bytes[i].toInt().toChar() else '�') }
    } else bytes.decodeToString(offset, offset + length)

internal fun String.toByteArray(charset: Charset): ByteArray =
    if (charset == Charsets.US_ASCII) ByteArray(length) { if (this[it].code < 128) this[it].code.toByte() else '?'.code.toByte() }
    else encodeToByteArray()
