package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream

fun main(args: Array<String>) {
    require(args.size == 2) { "Expected grain data and native evidence directories" }
    val data = File(args[0]); val native = File(args[1])
    val catalog = NativeGrain8Catalog.read(File(data, "catalog.cxograin").readBytes())
    val colorLut = NativeGrain8ColorLutFactory.fromQ15Transform(
        LcmsOptimizedQ15Transform.read(File(data, "adobe-to-widegamut.lcmsbin").readBytes()),
    )
    val sources = mutableMapOf<Int, ByteArray>()
    var fullPixels = 0L
    val result = File(data, "grain-geometry.golden").inputStream().use { fixture ->
        verifyGrainGeometry(fixture, catalog, colorLut, source = { grainEnum ->
            sources.getOrPut(grainEnum) {
                InflaterInputStream(File(data, "catalog-sources/$grainEnum.rgbz").inputStream()).use { it.readBytes() }
            }
        }, fullNode = { label, width, height, kernel ->
            val directory = File(native, label.substringBefore('/'))
            val prefix = "native-render-${label.substringAfter('/')}.tif"
            val input = directory.listFiles()!!.single {
                it.name.startsWith("$prefix.execute-node-") && it.name.endsWith("-input-native.q15-rgb.bin")
            }
            fun q15(file: File): ShortArray {
                val raw = file.readBytes(); check(raw.size == width * height * 3 * 2)
                val view = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return ShortArray(view.remaining()).also { view.get(it) }
            }
            val pixels = q15(input)
            val expected = q15(File(directory, "$prefix.grain-node-after-compute.q15-rgb.bin"))
            kernel.renderRgbQ15(pixels, pixels, width)
            check(pixels.contentEquals(expected)) { "Full geometry node differs: $label" }
            fullPixels += width.toLong() * height
        })
    }
    listOf(0f, Float.NaN, Float.POSITIVE_INFINITY, 0.001f, Float.MAX_VALUE).forEach { invalid ->
        check(runCatching { NativeGrain8Geometry.resolve(1263, NativeGrain8Geometry.Format.MANUAL, 1024, 1024, invalid) }.isFailure)
    }
    println("NativeGrain8Geometry: ${result.records} records; ${result.preparedComponents} prepared components, ${result.rowPixels} row pixels and $fullPixels full-node pixels exact")
}
