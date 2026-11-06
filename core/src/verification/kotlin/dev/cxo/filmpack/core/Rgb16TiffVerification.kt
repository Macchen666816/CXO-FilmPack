package dev.cxo.filmpack.core

import java.io.ByteArrayOutputStream
import java.io.File

/** Small structural and byte-order verification for the dependency-free TIFF writer. */
fun main(args: Array<String>) {
    val pixels = shortArrayOf(
        0x0000, 0x1234, 0x8000.toShort(),
        0xffff.toShort(), 0xabcd.toShort(), 0x0001,
    )
    val icc = byteArrayOf(1, 2, 3, 4, 5)
    val output = ByteArrayOutputStream()
    Rgb16TiffWriter.write(output, 2, 1, pixels = pixels, iccProfile = icc)
    val bytes = output.toByteArray()
    check(bytes.copyOfRange(0, 4).contentEquals(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0)))
    check(u32(bytes, 4) == 8L)
    val ifd = u32(bytes, 4).toInt()
    val count = u16(bytes, ifd)
    check(count == 15) { "RGB + ICC should have 15 IFD entries, got $count" }
    val tags = linkedMapOf<Int, Tag>()
    repeat(count) { index ->
        val offset = ifd + 2 + index * 12
        val tag = u16(bytes, offset)
        tags[tag] = Tag(u16(bytes, offset + 2), u32(bytes, offset + 4), u32(bytes, offset + 8))
    }
    check(tags[256]?.value == 2L && tags[257]?.value == 1L)
    check(tags[258]?.type == 3 && tags[258]?.count == 3L)
    val bits = tags.getValue(258)
    check(u16(bytes, bits.value.toInt()) == 16)
    check(u16(bytes, bits.value.toInt() + 2) == 16)
    check(u16(bytes, bits.value.toInt() + 4) == 16)
    val strip = tags.getValue(273).value.toInt()
    check(tags.getValue(279).value == 12L)
    val expectedPixelBytes = byteArrayOf(
        0, 0, 0x34, 0x12, 0, 0x80.toByte(),
        0xff.toByte(), 0xff.toByte(), 0xcd.toByte(), 0xab.toByte(), 1, 0,
    )
    check(bytes.copyOfRange(strip, strip + expectedPixelBytes.size).contentEquals(expectedPixelBytes))
    val iccTag = tags.getValue(34675)
    check(iccTag.type == 7 && iccTag.count == icc.size.toLong())
    check(bytes.copyOfRange(iccTag.value.toInt(), iccTag.value.toInt() + icc.size).contentEquals(icc)) {
        "ICC payload mismatch at ${iccTag.value}; actual=${bytes.copyOfRange(iccTag.value.toInt(), iccTag.value.toInt() + icc.size).joinToString()}"
    }
    if (args.isNotEmpty()) {
        require(args.size in 1..2) { "Expected TIFF output path and optional real ICC path" }
        File(args[0]).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        val realIcc = if (args.size == 2) File(args[1]).readBytes() else icc
        File(args[0] + ".rgba.tif").outputStream().use { external ->
            Rgb16TiffWriter.write(external, 2, 1, channels = 4, iccProfile = realIcc) { _, row ->
                shortArrayOf(0x1234, 0x8000.toShort(), 0xffff.toShort(), 0x0102,
                    0x3456, 0x789a, 0xbcde.toShort(), 0xffff.toShort()).copyInto(row)
            }
        }
    }

    val rgba = ByteArrayOutputStream()
    Rgb16TiffWriter.write(rgba, 1, 1, channels = 4, rows = { _, row -> row.fill(0x7fff) })
    val rgbaBytes = rgba.toByteArray()
    val rgbaIfd = u32(rgbaBytes, 4).toInt()
    check(u16(rgbaBytes, rgbaIfd) == 15) { "RGBA without ICC should have 15 IFD entries" }

    val rgbaIcc = byteArrayOf(9, 8, 7, 6)
    val rgbaWithIcc = ByteArrayOutputStream()
    Rgb16TiffWriter.write(
        rgbaWithIcc,
        1,
        1,
        channels = 4,
        iccProfile = rgbaIcc,
    ) { _, row -> row.fill(0x2468) }
    val rgbaIccBytes = rgbaWithIcc.toByteArray()
    val rgbaIccIfd = u32(rgbaIccBytes, 4).toInt()
    val rgbaIccCount = u16(rgbaIccBytes, rgbaIccIfd)
    check(rgbaIccCount == 16) { "RGBA + ICC should have 16 IFD entries, got $rgbaIccCount" }
    val rgbaIccTags = linkedMapOf<Int, Tag>()
    repeat(rgbaIccCount) { index ->
        val offset = rgbaIccIfd + 2 + index * 12
        rgbaIccTags[u16(rgbaIccBytes, offset)] = Tag(
            u16(rgbaIccBytes, offset + 2),
            u32(rgbaIccBytes, offset + 4),
            u32(rgbaIccBytes, offset + 8),
        )
    }
    check(rgbaIccTags.getValue(277).value == 4L)
    check(rgbaIccTags.getValue(338).value == 2L)
    val rgbaIccTag = rgbaIccTags.getValue(34675)
    check(rgbaIccTag.type == 7 && rgbaIccTag.count == rgbaIcc.size.toLong())
    check(rgbaIccTag.value == 0x06070809L) { "Four-byte ICC payload must be inline" }
    val shortSoftware = ByteArrayOutputStream()
    Rgb16TiffWriter.write(shortSoftware, 1, 1, pixels = shortArrayOf(1,2,3), software = "CX")
    val shortSoftwareBytes = shortSoftware.toByteArray()
    val softwareTag = (0 until u16(shortSoftwareBytes, 8)).map { 10 + it * 12 }
        .first { u16(shortSoftwareBytes, it) == 305 }
    check(u32(shortSoftwareBytes, softwareTag + 8) == 0x5843L)
    println("{\"rgb16_tiff\":\"passed\",\"rgb_bytes\":${bytes.size},\"rgba_bytes\":${rgbaBytes.size}}")
}

private data class Tag(val type: Int, val count: Long, val value: Long)

private fun u16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

private fun u32(bytes: ByteArray, offset: Int): Long =
    (u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16))
