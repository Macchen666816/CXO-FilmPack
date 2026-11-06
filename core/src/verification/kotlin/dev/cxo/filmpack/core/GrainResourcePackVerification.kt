package dev.cxo.filmpack.core

import java.io.File
import java.nio.file.Files

fun main(args: Array<String>) {
    check(args.size == 2)
    val pack = File(args[0]); val root = File(args[1])
    val result = File(root,"catalog-validation.golden").inputStream().use { verifyGrainResourcePack(pack,it) }
    check(result.entries == 99 && result.preparedComponents == 29_341_200L && result.sampledNodePixels == 2_097_711L)
    val directory = Files.createTempDirectory("cxo-grain-pack-verification-").toFile()
    val failures = verifyGrainResourcePackFailures(pack,directory)
    println("External grain pack: $result; $failures bounded-parser/closed-resource rejection checks passed")
    directory.deleteRecursively()
}
