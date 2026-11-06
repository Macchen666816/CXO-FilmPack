package dev.cxo.filmpack.core

import java.io.File

fun main(args: Array<String>) {
    val directory = File(args.single())
    val bytes = File(directory,"no-toning-context.cxoctx").readBytes()
    val contexts = NativeGrain8RenderingContext.read(bytes)
    val count = File(directory,"grain-context.golden").inputStream().use { verifyGrainRenderingContext(contexts,it) }
    check(contexts.size == 153)
    for (unknown in listOf("Original","unknown","KodakPortra400 "))
        check(runCatching { contexts.resolve(unknown) }.exceptionOrNull() is IllegalArgumentException)
    for (toning in listOf(-1,1,7)) check(runCatching { contexts.resolve("KodakPortra400",toning) }.exceptionOrNull() is IllegalArgumentException)
    check(runCatching { NativeGrain8RenderingContext.read(bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }) }.exceptionOrNull() is IllegalArgumentException)
    println("Grain rendering context: 153 source definitions, $count original active flag pairs exact; unknown/toning/hash rejection passed")
}
