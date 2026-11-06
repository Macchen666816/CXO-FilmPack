package dev.cxo.filmpack.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

fun verifyGrainResourcePack(pack: File, golden: InputStream): GrainCatalogVerificationResult =
    NativeGrain8Resources.open(pack).use { resources ->
        verifyGrainCatalog(resources.catalog,resources.colorLut(),golden,resources::readSource)
    }

/** Small, independently written malformed packs exercise the actual parser bounds. */
fun verifyGrainResourcePackFailures(pack: File, directory: File): Int {
    check(directory.mkdirs() || directory.isDirectory)
    val catalog: ByteArray; val cmm: ByteArray
    ZipFile(pack).use { zip ->
        catalog = zip.getInputStream(zip.getEntry("catalog.cxograin")).use { it.readBytes() }
        cmm = zip.getInputStream(zip.getEntry("adobe-to-widegamut.lcmsbin")).use { it.readBytes() }
    }
    val definitions = NativeGrain8Catalog.read(catalog).definitions()
    fun compressed(size: Int): ByteArray = ByteArrayOutputStream().also { output ->
        DeflaterOutputStream(output).use { it.write(ByteArray(size)) }
    }.toByteArray()
    fun write(name: String, source: ByteArray, badCatalog: Boolean = false, badCmm: Boolean = false,
              extra: Boolean = false, missing: Boolean = false): File {
        val file = File(directory,"$name.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path).apply {
                    method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                    crc = CRC32().also { it.update(bytes) }.value
                })
                zip.write(bytes); zip.closeEntry()
            }
            entry("catalog.cxograin",catalog.copyOf().also { if (badCatalog) it[0] = 0 })
            entry("adobe-to-widegamut.lcmsbin",cmm.copyOf().also { if (badCmm) it[0] = 0 })
            for ((index,definition) in definitions.withIndex()) if (!missing || index != 0)
                entry("sources/${definition.grainEnum}.rgbz",source)
            if (extra) entry("../unrelated",byteArrayOf(1))
        }
        return file
    }
    fun rejected(file: File, message: String, readSource: Boolean = false) {
        val failure = runCatching {
            NativeGrain8Resources.open(file).use { if (readSource) it.readSource(definitions.first().grainEnum) }
        }.exceptionOrNull()
        check(failure is IllegalArgumentException && failure.message?.contains(message) == true) {
            "Wrong rejection for ${file.name}: $failure"
        }
    }
    val validLengthWrongHash = compressed(3_000_000)
    rejected(write("catalog-pin",validLengthWrongHash,badCatalog=true),"catalog hash mismatch")
    rejected(write("cmm-pin",validLengthWrongHash,badCmm=true),"CMM hash mismatch")
    rejected(write("missing-entry",validLengthWrongHash,missing=true),"file set mismatch")
    rejected(write("extra-entry",validLengthWrongHash,extra=true),"directory")
    rejected(write("decoded-overflow",compressed(3_000_001)),"exceeds expected decoded size",true)
    rejected(write("source-pin",validLengthWrongHash),"source hash mismatch",true)
    val resources = NativeGrain8Resources.open(pack); resources.close()
    check(runCatching { resources.readSource(definitions.first().grainEnum) }.exceptionOrNull() is IllegalStateException)
    check(runCatching { resources.colorLut() }.exceptionOrNull() is IllegalStateException)
    return 8
}
