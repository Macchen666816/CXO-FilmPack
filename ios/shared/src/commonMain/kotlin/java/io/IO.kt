// Minimal java.io surface so the unmodified CXO core compiles on Kotlin/Native.
// Declared only for non-JVM targets; this module has no JVM target.
package java.io

open class IOException(message: String? = null) : Exception(message)
class EOFException(message: String? = null) : IOException(message)

interface Closeable { fun close() }

abstract class InputStream : Closeable {
    abstract fun read(): Int
    open fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var n = 0
        while (n < len) {
            val c = read()
            if (c < 0) return if (n == 0) -1 else n
            b[off + n] = c.toByte(); n++
        }
        return n
    }
    fun read(b: ByteArray): Int = read(b, 0, b.size)
    fun readBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (true) { val n = read(buf, 0, buf.size); if (n < 0) break; out.write(buf, 0, n) }
        return out.toByteArray()
    }
    /** Kotlin's JVM `buffered()` extension; our streams are already memory-backed. */
    fun buffered(): InputStream = this
    override fun close() {}
}

open class ByteArrayInputStream(private val buf: ByteArray) : InputStream() {
    private var pos = 0
    override fun read(): Int = if (pos < buf.size) buf[pos++].toInt() and 255 else -1
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= buf.size) return -1
        val n = minOf(len, buf.size - pos)
        buf.copyInto(b, off, pos, pos + n); pos += n
        return n
    }
}

/** Big-endian primitives, like java.io.DataInputStream. */
class DataInputStream(private val input: InputStream) : InputStream() {
    override fun read(): Int = input.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)
    fun readFully(b: ByteArray) = readFully(b, 0, b.size)
    fun readFully(b: ByteArray, off: Int, len: Int) {
        var n = 0
        while (n < len) {
            val r = input.read(b, off + n, len - n)
            if (r < 0) throw EOFException()
            n += r
        }
    }
    private fun u8(): Int { val c = input.read(); if (c < 0) throw EOFException(); return c }
    fun readUnsignedByte(): Int = u8()
    fun readByte(): Byte = u8().toByte()
    fun readUnsignedShort(): Int = (u8() shl 8) or u8()
    fun readShort(): Short = readUnsignedShort().toShort()
    fun readInt(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
    fun readLong(): Long = (readInt().toLong() shl 32) or (readInt().toLong() and 0xffffffffL)
    fun readFloat(): Float = Float.fromBits(readInt())
    fun readDouble(): Double = Double.fromBits(readLong())
    override fun close() = input.close()
}

abstract class OutputStream : Closeable {
    abstract fun write(b: Int)
    open fun write(b: ByteArray, off: Int, len: Int) { for (i in 0 until len) write(b[off + i].toInt()) }
    fun write(b: ByteArray) = write(b, 0, b.size)
    open fun flush() {}
    override fun close() { flush() }
}

class ByteArrayOutputStream(initial: Int = 32) : OutputStream() {
    private var buf = ByteArray(maxOf(initial, 32))
    private var count = 0
    private fun ensure(extra: Int) { if (count + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, count + extra)) }
    override fun write(b: Int) { ensure(1); buf[count++] = b.toByte() }
    override fun write(b: ByteArray, off: Int, len: Int) { ensure(len); b.copyInto(buf, count, off, off + len); count += len }
    fun toByteArray(): ByteArray = buf.copyOf(count)
    fun size(): Int = count
}

open class BufferedOutputStream(private val out: OutputStream, size: Int = 8192) : OutputStream() {
    private val buf = ByteArray(size); private var n = 0
    override fun write(b: Int) { if (n == buf.size) flushBuffer(); buf[n++] = b.toByte() }
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len >= buf.size) { flushBuffer(); out.write(b, off, len); return }
        if (len > buf.size - n) flushBuffer()
        b.copyInto(buf, n, off, off + len); n += len
    }
    private fun flushBuffer() { if (n > 0) { out.write(buf, 0, n); n = 0 } }
    override fun flush() { flushBuffer(); out.flush() }
    override fun close() { flush(); out.close() }
}
