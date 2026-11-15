package dev.cxo.filmpack.core

/** Random-access read-only bytes (a file on iOS); keeps the 124 MB grain pack out of memory. */
interface ByteSource {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
    fun close()
}

/** zlib `uncompress` of a whole buffer into at most [maxSize] bytes; platform-provided. */
expect fun zlibInflate(source: ByteArray, maxSize: Int): ByteArray

/** Opens a regular file as a [ByteSource]; platform-provided. */
expect fun openFileSource(path: String): ByteSource
