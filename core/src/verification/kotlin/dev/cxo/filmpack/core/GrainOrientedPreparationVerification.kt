package dev.cxo.filmpack.core

import java.io.File

fun main(args: Array<String>) {
    require(args.size == 1) { "Expected grain data directory" }
    val data = File(args[0])
    val lut = NativeGrain8ColorLutFactory.fromQ15Transform(
        LcmsOptimizedQ15Transform.read(File(data, "adobe-to-widegamut.lcmsbin").readBytes()),
    )
    val pixels = File(data, "grain-oriented-preparation.golden").inputStream().use {
        verifyGrainOrientedPreparation(it, lut)
    }
    println("NativeGrain8Prepare orientation: 3 full preparations and $pixels full-node pixels exact")
}
