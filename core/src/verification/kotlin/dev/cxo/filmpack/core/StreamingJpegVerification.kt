package dev.cxo.filmpack.core

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt

/** Encodes concrete fixtures and checks independently decoded RGB and upstream entropy bytes. */
fun main(args: Array<String>) {
    require(args.size == 3) { "Expected output directory, source mosaic PNG, pinned jpeg-js encoder.js" }
    val output = File(args[0]).apply { mkdirs() }
    val photograph = requireNotNull(ImageIO.read(File(args[1])))
    val reference = File(args[2]).absoluteFile.also { require(it.isFile) }
    val cases = listOf(
        JpegCase("one-pixel", 1, 1) { _, _ -> 0xff7f33d1.toInt() },
        JpegCase("odd-edges", 13, 9) { x, y -> rgb(x * 19 % 256, y * 31 % 256, (x * 33 + y * 11) % 256) },
        JpegCase("saturated-checker", 39, 27) { x, y ->
            intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(),
                0xffffff00.toInt(), 0xff00ffff.toInt(), 0xffff00ff.toInt())[(x / 8 + y / 8) % 6]
        },
        JpegCase("gradient", 257, 73) { x, y -> rgb(x * 255 / 256, y * 255 / 72, (x + y) * 255 / 328) },
        JpegCase("photo-mosaic", photograph.width, photograph.height) { x, y -> photograph.getRGB(x, y) },
        JpegCase("quality-one", 31, 19, quality = 1) { x, y -> rgb(x * 17 % 256, y * 27 % 256, (x * 31 + y * 7) % 256) },
        JpegCase("quality-three", 31, 19, quality = 3) { x, y -> rgb(x * 17 % 256, y * 27 % 256, (x * 31 + y * 7) % 256) },
        JpegCase("quality-forty-nine", 31, 19, quality = 49) { x, y -> rgb(x * 17 % 256, y * 27 % 256, (x * 31 + y * 7) % 256) },
        JpegCase("quality-hundred", 33, 17, quality = 100) { x, y -> rgb(x * 255 / 32, y * 255 / 16, (x + y) * 255 / 48) },
    ) + (1..8).map { orientation ->
        JpegCase("orientation-$orientation", 13, 9, orientation = orientation) { x, y ->
            if (x < 6) rgb(230, y * 27, 10) else rgb(10, y * 27, 230)
        }
    }
    val rows = cases.map { fixture -> encodeFixture(output, fixture, reference) }
    verifyOwnershipAndExceptions()
    val report = """{
  "scope": "pure Kotlin baseline JPEG; independent JVM ImageIO decode plus fixed jpeg-js entropy reference",
  "reference_commit": "1031ccd7a63c641c16afb2430279d1598530a85c",
  "reference_sha256": "${sha256(reference.readBytes())}",
  "caller_stream_ownership_and_exception_checks": true,
  "rows": [
${rows.joinToString(",\n")}
  ]
}
"""
    File(output, "verification.json").writeText(report)
    println("Streaming JPEG: ${cases.size} fixtures independently decoded; entropy matches jpeg-js; callback/ownership/exception checks passed")
    println(report)
}

private class JpegCase(
    val name: String, val width: Int, val height: Int,
    val quality: Int = 98, val orientation: Int = 1,
    val pixel: (Int, Int) -> Int,
)

private class OwnedJpegStream : ByteArrayOutputStream() {
    var closed = false
    var flushes = 0
    override fun close() { closed = true; super.close() }
    override fun flush() { flushes++; super.flush() }
}

private fun rgb(red: Int, green: Int, blue: Int): Int =
    (255 shl 24) or (red shl 16) or (green shl 8) or blue

private fun encodeFixture(output: File, fixture: JpegCase, reference: File): String {
    val width = fixture.width
    val height = fixture.height
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val raw = ByteArray(width * height * 4)
    for (y in 0 until height) for (x in 0 until width) {
        val value = fixture.pixel(x, y)
        image.setRGB(x, y, value)
        val base = (y * width + x) * 4
        raw[base] = (value ushr 16).toByte()
        raw[base + 1] = (value ushr 8).toByte()
        raw[base + 2] = value.toByte()
        raw[base + 3] = 255.toByte()
    }
    val rawFile = File(output, "${fixture.name}.rgba")
    rawFile.writeBytes(raw)
    check(ImageIO.write(image, "PNG", File(output, "${fixture.name}.input.png")))
    val bytes = OwnedJpegStream()
    var nextRow = 0
    var callbacks = 0
    StreamingJpegWriter.write(bytes, width, height, fixture.quality, fixture.orientation) { top, count, argb ->
        check(top == nextRow && count in 1..8 && top + count <= height)
        check(count == minOf(8, height - top) && argb.size == width * 8)
        // Poison the unfilled final-strip tail. Entropy comparison below checks
        // that padding duplicates the last real row/column, never this tail.
        argb.fill(0xff00ff00.toInt())
        for (row in 0 until count) for (x in 0 until width) argb[row * width + x] = image.getRGB(x, top + row)
        nextRow += count
        callbacks++
    }
    check(nextRow == height && callbacks == (height + 7) / 8)
    check(!bytes.closed && bytes.flushes > 0)
    val encoded = bytes.toByteArray()
    val jpegFile = File(output, "${fixture.name}.jpg").apply { writeBytes(encoded) }
    check(jpegOrientation(encoded) == fixture.orientation)
    val decoded = requireNotNull(ImageIO.read(jpegFile))
    check(decoded.width == width && decoded.height == height)
    var squared = 0.0
    var maximum = 0
    for (y in 0 until height) for (x in 0 until width) {
        val before = image.getRGB(x, y)
        val after = decoded.getRGB(x, y)
        for (shift in intArrayOf(16, 8, 0)) {
            val error = abs(((before ushr shift) and 255) - ((after ushr shift) and 255))
            squared += error.toDouble() * error
            maximum = maxOf(maximum, error)
        }
    }
    val rmse = sqrt(squared / (width.toLong() * height * 3))
    check(rmse < if (fixture.quality < 50) 100.0 else 8.0) {
        "Unexpected color loss for ${fixture.name}: rmse=$rmse max=$maximum"
    }
    val referenceFile = File(output, "${fixture.name}.jpeg-js.jpg")
    val script = "const fs=require('fs');const [s,r,w,h,q,o]=process.argv.slice(1);const e=require(s);fs.writeFileSync(o,e({data:fs.readFileSync(r),width:+w,height:+h},+q).data);"
    val process = ProcessBuilder("node", "-e", script, reference.path, rawFile.absolutePath,
        width.toString(), height.toString(), fixture.quality.toString(), referenceFile.absolutePath)
        .redirectErrorStream(true).start()
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        error("jpeg-js reference timed out for ${fixture.name}")
    }
    val message = process.inputStream.bufferedReader().use { it.readText() }
    check(process.exitValue() == 0) { "jpeg-js failed: $message" }
    val referenceBytes = referenceFile.readBytes()
    check(jpegEntropy(encoded).contentEquals(jpegEntropy(referenceBytes))) {
        "Entropy stream differs from fixed jpeg-js reference for ${fixture.name}"
    }
    return """    {"name":"${fixture.name}","width":$width,"height":$height,"quality":${fixture.quality},"orientation":${fixture.orientation},"callbacks":$callbacks,"jpeg_bytes":${encoded.size},"rmse_codes255":$rmse,"max_abs_codes255":$maximum,"jpeg_sha256":"${sha256(encoded)}","jpeg_js_entropy_equal":true}"""
}

private fun jpegEntropy(bytes: ByteArray): ByteArray {
    var cursor = 2
    while (cursor + 4 <= bytes.size) {
        check((bytes[cursor].toInt() and 255) == 255)
        val marker = bytes[cursor + 1].toInt() and 255
        val length = ((bytes[cursor + 2].toInt() and 255) shl 8) or (bytes[cursor + 3].toInt() and 255)
        if (marker == 0xda) return bytes.copyOfRange(cursor + 2 + length, bytes.size)
        cursor += 2 + length
    }
    error("No JPEG scan")
}

private fun jpegOrientation(bytes: ByteArray): Int {
    var cursor = 2
    while (cursor + 4 <= bytes.size) {
        val marker = bytes[cursor + 1].toInt() and 255
        val length = ((bytes[cursor + 2].toInt() and 255) shl 8) or (bytes[cursor + 3].toInt() and 255)
        if (marker == 0xe1) {
            check(length == 34)
            check(bytes.copyOfRange(cursor + 4, cursor + 10).contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0)))
            return bytes[cursor + 28].toInt() and 255
        }
        if (marker == 0xda) break
        cursor += 2 + length
    }
    error("No orientation segment")
}

private fun verifyOwnershipAndExceptions() {
    val failure = IllegalStateException("intentional row cancellation")
    val target = OwnedJpegStream()
    var callbacks = 0
    try {
        StreamingJpegWriter.write(target, 13, 17) { _, count, rows ->
            callbacks++
            if (callbacks == 2) throw failure
            rows.fill(0xff123456.toInt(), toIndex = 13 * count)
        }
        error("Callback exception was swallowed")
    } catch (actual: IllegalStateException) { check(actual === failure) }
    check(callbacks == 2 && !target.closed)
    val ioFailure = IOException("intentional I/O failure")
    var closed = false
    val broken = object : OutputStream() {
        override fun write(value: Int) { throw ioFailure }
        override fun close() { closed = true }
    }
    try {
        StreamingJpegWriter.write(broken, 1, 1) { _, _, rows -> rows[0] = 0xffaabbcc.toInt() }
        error("I/O exception was swallowed")
    } catch (actual: IOException) { check(actual === ioFailure) }
    check(!closed)
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
