@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.cxo.filmpack.core

import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.CPointer
import platform.posix.FILE
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.pread
import platform.zlib.Z_OK
import platform.zlib.uncompress

actual fun zlibInflate(source: ByteArray, maxSize: Int): ByteArray = memScoped {
    val out = ByteArray(maxSize)
    val length = alloc<ULongVar>()
    length.value = maxSize.convert()
    val status = out.usePinned { o ->
        source.usePinned { s ->
            uncompress(o.addressOf(0).reinterpret(), length.ptr, s.addressOf(0).reinterpret(), source.size.convert())
        }
    }
    require(status == Z_OK) { "zlib inflate failed ($status)" }
    out.copyOf(length.value.toInt())
}

private class FileByteSource(path: String) : ByteSource {
    private val file: CPointer<FILE> = requireNotNull(fopen(path, "rb")) { "Cannot open $path" }
    private val fd: Int = fileno(file)
    override val size: Long

    init {
        fseek(file, 0, SEEK_END)
        size = ftell(file).toLong()
        require(size >= 0) { "Cannot stat $path" }
        fseek(file, 0, SEEK_SET)
    }

    // pread is positional, so concurrent readers never disturb each other.
    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= size) { "Read outside file" }
        if (length == 0) return ByteArray(0)
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = out.usePinned { pinned ->
                pread(fd, pinned.addressOf(done), (length - done).convert(), offset + done)
            }.toInt()
            require(n > 0) { "Short read" }
            done += n
        }
        return out
    }

    override fun close() { fclose(file) }
}

actual fun openFileSource(path: String): ByteSource = FileByteSource(path)
