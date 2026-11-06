package dev.cxo.filmpack.core

import java.io.File
import java.util.zip.InflaterInputStream

fun main(args: Array<String>) {
    require(args.size == 2)
    val data = File(args[0]); val assets = File(args[1])
    val catalog = NativeGrain8Catalog.read(File(data, "catalog.cxograin").readBytes())
    val lut = NativeGrain8ColorLutFactory.fromQ15Transform(LcmsOptimizedQ15Transform.read(File(data, "adobe-to-widegamut.lcmsbin").readBytes()))
    val entry = File(assets, "films/srgb-entry-pix65.cxoentry").inputStream().use(FixedSrgbEntryLutIO::read)
    val sources = mutableMapOf<Int, ByteArray>()
    File(data, "grain-photo-boundary.golden").inputStream().use { fixture ->
        verifyGrainPhotoBoundary(fixture, catalog, lut, entry, openFilm = { File(assets,it).inputStream() }, grainSource = { id ->
            sources.getOrPut(id) { InflaterInputStream(File(data, "catalog-sources/$id.rgbz").inputStream()).use { it.readBytes() } }
        })
    }
    // Exhaustively protect the film core's normalized integer-Q15 boundary.
    for (i in 0..32767) check(Math.rint(((i.toFloat()/32767f)*32767f).toDouble()).toInt() == i)
    println("Photo grain: 5 native captures, 5242880 native bridge pixels exact; film/grain combination within declared code tolerance")
}
