package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream

fun main(args: Array<String>) {
    require(args.size == 4) { "Expected grain data directory, CMM resource, catalog golden and native directory" }
    val data = File(args[0])
    val bytes = File(data, "catalog.cxograin").readBytes()
    val catalog = NativeGrain8Catalog.read(bytes)
    check(catalog.size == 99)
    check(listOf("Original", "Astia", "Provia", "Velvia", "Ekta100VS", "Kod64").all { catalog[it] == null })
    val exposed = catalog.definitions().also { (it as MutableList).clear() }
    check(exposed.isEmpty() && catalog.definitions().size == 99)
    val first = catalog.definitions().first()
    check(runCatching { first.prepare(ByteArray(3_000_000), 0.3f,
        NativeGrain8ColorLut(FloatArray(NativeGrain8ColorLut.GRID_FLOATS))) }.isFailure)
    listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0), bytes.copyOf().also { it[8] = 2 },
        bytes.copyOf().also { it[12] = 98 }).forEach {
        check(runCatching { NativeGrain8Catalog.read(it) }.isFailure)
    }
    val colorLut = NativeGrain8ColorLutFactory.fromQ15Transform(
        LcmsOptimizedQ15Transform.read(File(args[1]).readBytes()),
    )
    var fullPixels = 0L
    fun q15(file: File): ShortArray {
        val raw = file.readBytes(); check(raw.size == 1024 * 1024 * 3 * 2)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also { buffer.get(it) }
    }
    val result = File(args[2]).inputStream().use { golden ->
        verifyGrainCatalog(catalog, colorLut, golden, source = { grainEnum ->
            InflaterInputStream(File(data, "catalog-sources/$grainEnum.rgbz").inputStream()).use { it.readBytes() }
        }, fullNode = { label, kernel ->
            val prefix = File(args[3], "native-render-$label.tif")
            val inputs = File(args[3]).listFiles()!!.filter {
                it.name.startsWith(prefix.name + ".execute-node-") && it.name.endsWith("-input-native.q15-rgb.bin")
            }
            check(inputs.size == 1)
            val source = q15(inputs.single())
            val expected = q15(File(prefix.path + ".grain-node-after-compute.q15-rgb.bin"))
            kernel.renderRgbQ15(source, source, 1024)
            check(source.contentEquals(expected)) { "Full catalog node differs: $label" }
            fullPixels += 1024 * 1024
        })
    }
    println("NativeGrain8Catalog: ${result.entries} entries; ${result.preparedComponents} prepared RGB8 components, ${result.sampledNodePixels} row pixels and $fullPixels full-node pixels exact")
}
