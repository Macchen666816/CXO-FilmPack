package dev.cxo.filmpack.core

import java.io.Closeable
import java.security.MessageDigest

/** External source-only pack, never an archive of native prepared/output pixels.
 * Catalog/CMM hashes are fixed; each RGB source is checked on demand. No archive
 * member is extracted to a caller-controlled path or retained as a full catalog.
 *
 * iOS port: the pack is a STORED-only zip. It is read through a tiny central-directory
 * parser over [ByteSource] instead of java.util.zip.ZipFile; every size/hash bound of
 * the Android implementation is kept. */
class NativeGrain8Resources private constructor(
    private val zip: MiniZip,
    val catalog: NativeGrain8Catalog,
    private val cmm: ByteArray,
) : Closeable {
    private val definitions = catalog.definitions().associateBy { it.grainEnum }
    private var lut: NativeGrain8ColorLut? = null
    private var closed = false

    fun colorLut(): NativeGrain8ColorLut {
        check(!closed) { "Grain resource pack is closed" }
        return lut ?: NativeGrain8ColorLutFactory.fromQ15Transform(LcmsOptimizedQ15Transform.read(cmm))
            .also { lut = it }
    }

    /** Return one caller-owned 1000-square RGB8 source, bounded before hashing. */
    fun readSource(grainEnum: Int): ByteArray {
        check(!closed) { "Grain resource pack is closed" }
        val definition = requireNotNull(definitions[grainEnum]) { "Unknown grain resource enum" }
        val compressed = readMember(zip,"sources/$grainEnum.rgbz",3_100_000)
        val raw = zlibInflate(compressed, 3_000_001)
        require(raw.size == 3_000_000) { "Grain source has unexpected decoded size" }
        require(resourceDigest(raw) == definition.packedRgbSha256) { "Grain source hash mismatch" }
        return raw
    }

    override fun close() {
        if (!closed) { closed = true; lut = null; zip.close() }
    }

    /** Minimal read-only zip directory: STORED members only, no zip64, no encryption. */
    internal class MiniZip(private val source: ByteSource) {
        class Entry(val name: String, val method: Int, val size: Long, val compressedSize: Long, val headerOffset: Long)
        val entries: List<Entry>
        private val byName: Map<String, Entry>

        init {
            val total = source.size
            val tailLength = minOf(total, 65_557L).toInt()
            val tail = source.read(total - tailLength, tailLength)
            var eocd = -1
            for (i in tailLength - 22 downTo 0) {
                if (le32(tail, i) == 0x06054b50) { eocd = i; break }
            }
            require(eocd >= 0) { "Invalid grain resource pack directory" }
            val count = le16(tail, eocd + 10)
            val directorySize = le32(tail, eocd + 12).toLong() and 0xffffffffL
            val directoryOffset = le32(tail, eocd + 16).toLong() and 0xffffffffL
            require(count in 1..101 && directorySize in 1..1_000_000L && directoryOffset + directorySize <= total) {
                "Invalid grain resource pack directory"
            }
            val dir = source.read(directoryOffset, directorySize.toInt())
            val list = ArrayList<Entry>(count)
            var at = 0
            repeat(count) {
                require(at + 46 <= dir.size && le32(dir, at) == 0x02014b50) { "Invalid grain resource pack directory" }
                val method = le16(dir, at + 10)
                val compressed = le32(dir, at + 20).toLong() and 0xffffffffL
                val size = le32(dir, at + 24).toLong() and 0xffffffffL
                val nameLength = le16(dir, at + 28); val extra = le16(dir, at + 30); val comment = le16(dir, at + 32)
                val offset = le32(dir, at + 42).toLong() and 0xffffffffL
                require(at + 46 + nameLength <= dir.size) { "Invalid grain resource pack directory" }
                list.add(Entry(dir.decodeToString(at + 46, at + 46 + nameLength), method, size, compressed, offset))
                at += 46 + nameLength + extra + comment
            }
            entries = list
            byName = list.associateBy { it.name }
        }

        fun entry(name: String): Entry? = byName[name]

        fun readStored(entry: Entry): ByteArray {
            val header = source.read(entry.headerOffset, 30)
            require(le32(header, 0) == 0x04034b50) { "Invalid grain resource pack member" }
            val dataOffset = entry.headerOffset + 30 + le16(header, 26) + le16(header, 28)
            return source.read(dataOffset, entry.size.toInt())
        }

        fun close() = source.close()

        private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 255) or ((b[i + 1].toInt() and 255) shl 8)
        private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)
    }

    companion object {
        private const val CATALOG_SHA = "5d93fe7b42b13e75a8f2e96c7b56703715070247b01845b7cc0e05076bbcf9fd"
        private const val CMM_SHA = "c683352cbd746d15ef50970bae45609438f51ef053bb5c3520b0cc97a25703e8"
        private const val STORED = 0

        fun open(path: String): NativeGrain8Resources = open(openFileSource(path))

        fun open(source: ByteSource): NativeGrain8Resources {
            require(source.size in 1..160_000_000L) { "Invalid grain resource pack size" }
            val zip = MiniZip(source)
            try {
                val names = mutableSetOf<String>()
                for (entry in zip.entries) {
                    require(names.size < 101 && names.add(entry.name) && !entry.name.endsWith("/") && entry.method == STORED) {
                        "Invalid grain resource pack directory"
                    }
                }
                val catalogBytes = readMember(zip,"catalog.cxograin",65_536)
                require(resourceDigest(catalogBytes) == CATALOG_SHA) { "Grain catalog hash mismatch" }
                val catalog = NativeGrain8Catalog.read(catalogBytes)
                val expected = catalog.definitions().map { "sources/${it.grainEnum}.rgbz" }.toSet() +
                    setOf("catalog.cxograin","adobe-to-widegamut.lcmsbin")
                require(names == expected) { "Grain resource pack file set mismatch" }
                val cmm = readMember(zip,"adobe-to-widegamut.lcmsbin",1_000_000)
                require(resourceDigest(cmm) == CMM_SHA) { "Grain CMM hash mismatch" }
                return NativeGrain8Resources(zip,catalog,cmm)
            } catch (failure: Throwable) {
                zip.close(); throw failure
            }
        }

        private fun readMember(zip: MiniZip, name: String, maximum: Int): ByteArray {
            val entry = requireNotNull(zip.entry(name)) { "Missing grain resource member" }
            require(entry.method == STORED && entry.size in 1..maximum.toLong() && entry.compressedSize == entry.size) {
                "Grain resource member exceeds format bounds"
            }
            return zip.readStored(entry)
        }
    }
}

private fun resourceDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
