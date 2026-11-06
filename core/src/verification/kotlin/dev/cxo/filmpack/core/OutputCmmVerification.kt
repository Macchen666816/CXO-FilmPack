package dev.cxo.filmpack.core
import java.io.File
fun main(args: Array<String>) {
    require(args.size == 2)
    val transform = FixedWideGamutToSrgb.read(File(args[0]).readBytes())
    val rows = File(args[1]).inputStream().use { verifyOutputCmm(it, transform) }
    println("Output CMM: $rows independent LCMS Q15-to-RGB16 rows exact, normalized bits, bulk/in-place and invalid input checks passed")
}
