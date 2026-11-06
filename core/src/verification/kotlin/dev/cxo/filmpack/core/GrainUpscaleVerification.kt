package dev.cxo.filmpack.core

import java.io.File
import java.util.zip.InflaterInputStream

fun main(args: Array<String>) {
    require(args.size == 1) { "Expected grain data directory" }
    val data = File(args[0])
    val catalog = NativeGrain8Catalog.read(File(data, "catalog.cxograin").readBytes())
    val colorLut = NativeGrain8ColorLutFactory.fromQ15Transform(
        LcmsOptimizedQ15Transform.read(File(data, "adobe-to-widegamut.lcmsbin").readBytes()),
    )
    val source = InflaterInputStream(File(data, "catalog-sources/1023.rgbz").inputStream()).use { it.readBytes() }
    val result = File(data, "polachrome-upscale.golden").inputStream().use {
        verifyGrainUpscale(it, catalog, colorLut, source)
    }
    println("NativeGrain8Prepare upscale: ${result.preparedComponents} prepared RGB8 components and ${result.nodePixels} full-node Q15 pixels exact")
}
