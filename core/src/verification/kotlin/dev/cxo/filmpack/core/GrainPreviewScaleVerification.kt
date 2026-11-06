package dev.cxo.filmpack.core
import java.io.File
import java.util.zip.InflaterInputStream
fun main(args: Array<String>) {
    val data = File(args.single())
    val catalog = NativeGrain8Catalog.read(File(data,"catalog.cxograin").readBytes())
    val definition = requireNotNull(catalog["KodakPortra400"])
    val source = InflaterInputStream(File(data,"catalog-sources/${definition.grainEnum}.rgbz").inputStream()).use { it.readBytes() }
    val lut = NativeGrain8ColorLutFactory.fromQ15Transform(LcmsOptimizedQ15Transform.read(File(data,"adobe-to-widegamut.lcmsbin").readBytes()))
    val pixels = File(data,"grain-preview-scale.golden").inputStream().use { verifyGrainPreviewScale(it,definition,source,lut) }
    println("Preview grain scales1/2: 84375 prepared components and $pixels full-node pixels exact, including nonzero tile recomposition")
    val expanded = File(data,"grain-preview-geometry.golden").inputStream().use { verifyGrainPreviewGeometry(it,definition,source,lut) }
    check(expanded.records == 7 && expanded.pixels == 1_486_848L)
    println("Expanded native previews: $expanded; auto full-source sizing, odd floor extents and actual nonzero ROI exact")
}
