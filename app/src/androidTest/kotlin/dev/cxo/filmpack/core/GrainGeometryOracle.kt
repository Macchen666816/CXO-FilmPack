package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.InputStream

data class GrainGeometryVerificationResult(val records: Int, val preparedComponents: Long, val rowPixels: Long)

fun verifyGrainGeometry(
    stream: InputStream,
    catalog: NativeGrain8Catalog,
    colorLut: NativeGrain8ColorLut,
    source: (Int) -> ByteArray,
    fullNode: ((String, Int, Int, NativeGrain8Patch) -> Unit)? = null,
): GrainGeometryVerificationResult = DataInputStream(stream.buffered()).use { input ->
    fun bytes(n: Int) = ByteArray(n).also(input::readFully)
    fun i32() = Integer.reverseBytes(input.readInt())
    fun shorts(n: Int): ShortArray {
        val raw = bytes(n * 2)
        return ShortArray(n) { i ->
            ((raw[i * 2].toInt() and 255) or (raw[i * 2 + 1].toInt() shl 8)).toShort()
        }
    }
    check(String(bytes(8), Charsets.US_ASCII) == "CXOGGE01" && i32() == 1)
    val count = i32(); check(count == 14)
    val definitions = catalog.definitions().associateBy { it.grainEnum }
    var components = 0L; var rowPixels = 0L
    repeat(count) {
        val nameLength = i32(); check(nameLength in 1..256)
        val label = String(bytes(nameLength), Charsets.US_ASCII)
        val grainEnum = i32(); val formatValue = i32(); val manualSize = Float.fromBits(i32())
        val width = i32(); val height = i32(); val nativeSize = i32(); val scale = Float.fromBits(i32())
        val size = i32(); val period = i32()
        check(width in 1..4096 && height in 1..4096 && size in 2..1200)
        val format = NativeGrain8Geometry.Format.entries.single { it.nativeValue == formatValue }
        check(NativeGrain8Geometry.Format.fromToken(format.token) == format)
        val geometry = NativeGrain8Geometry.resolve(grainEnum, format, width, height, manualSize)
        check(geometry.nativeSize == nativeSize && geometry.scale.toRawBits() == scale.toRawBits() &&
            geometry.size == size && geometry.period == period) { "Grain geometry differs: $label" }
        val expected = bytes(size * size * 3)
        val expectedAmplitude = IntArray(256) { i32() }
        val definition = checkNotNull(definitions[grainEnum])
        val prepared = definition.prepare(source(grainEnum), geometry.scale, colorLut)
        check(prepared.size == size && prepared.period == period && prepared.bytes.contentEquals(expected)) {
            "Geometry preparation differs: $label"
        }
        val amplitude = definition.amplitude(100)
        check(amplitude.contentEquals(expectedAmplitude))
        val kernel = NativeGrain8Patch(prepared.bytes, size, size, period, amplitude)
        val rows = i32(); check(rows == 3)
        repeat(rows) {
            val x = i32(); val y = i32(); val tileWidth = i32()
            check(x >= 0 && y in 0 until height && tileWidth > 0 && x + tileWidth <= width)
            val pixels = shorts(tileWidth * 3); val expectedPixels = shorts(tileWidth * 3)
            kernel.renderRgbQ15(pixels, pixels, tileWidth, x, y)
            check(pixels.contentEquals(expectedPixels)) { "Geometry node row differs: $label at ($x,$y)" }
            rowPixels += tileWidth
        }
        fullNode?.invoke(label, width, height, kernel)
        components += expected.size
    }
    check(input.read() == -1)
    GrainGeometryVerificationResult(count, components, rowPixels)
}
