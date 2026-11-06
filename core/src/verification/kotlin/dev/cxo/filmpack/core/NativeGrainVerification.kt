package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.InputStream

/** Fixtures are actual Engine+0x5e14e8 calls, not Kotlin-generated expectations. */
fun verifyNativeGrainOracle(stream: InputStream): Int {
    DataInputStream(stream.buffered()).use { input ->
        fun i32() = Integer.reverseBytes(input.readInt())
        fun shorts(count: Int) = ShortArray(count) { java.lang.Short.reverseBytes(input.readShort()) }
        val magic = ByteArray(8).also(input::readFully)
        check(String(magic, Charsets.US_ASCII) == "CXOGRN01")
        val cases = i32(); val pw = i32(); val ph = i32(); val period = i32()
        check(cases in 1..100 && pw in 1..4096 && ph in 1..4096)
        val patch = ByteArray(pw * ph * 3).also(input::readFully)
        val amplitude = IntArray(256) { i32() }
        var pixels = 0
        repeat(cases) { case ->
            val width = i32(); val height = i32(); val x = i32(); val y = i32()
            val color = i32() == 1; val neutral = i32() == 1
            check(width in 1..4096 && height in 1..4096)
            val source = shorts(width * height * 3); val expected = shorts(source.size)
            val patchArgument = patch.copyOf(); val mapArgument = amplitude.copyOf()
            val kernel = NativeGrain8Patch(patchArgument,pw,ph,period,mapArgument,color,neutral)
            // Constructor owns its inputs; later caller changes must not alter the prepared instance.
            patchArgument.fill(0); mapArgument.fill(0)
            val actual = ShortArray(source.size)
            kernel.renderRgbQ15(source,actual,width,x,y)
            check(actual.contentEquals(expected)) { "Native oracle mismatch, case $case" }
            val inPlace = source.copyOf()
            kernel.renderRgbQ15(inPlace,inPlace,width,x,y)
            check(inPlace.contentEquals(expected)) { "In-place mismatch, case $case" }
            // Split in both axes, crossing the patch period and using nonzero origins.
            val tiled = ShortArray(source.size)
            for (row in 0 until height) {
                var col = 0
                while (col < width) {
                    val count = minOf(31,width-col)
                    val start = (row*width+col)*3
                    val tile = source.copyOfRange(start,start+count*3)
                    kernel.renderRgbQ15(tile,tile,count,x+col,y+row)
                    tile.copyInto(tiled,start)
                    col += count
                }
            }
            check(tiled.contentEquals(expected)) { "Tile phase mismatch, case $case" }
            val bad = source.copyOf().also { it[it.lastIndex] = (-1).toShort() }
            val untouched = ShortArray(bad.size) { 1234 }
            check(runCatching { kernel.renderRgbQ15(bad,untouched,width,x,y) }.exceptionOrNull() is IllegalArgumentException)
            check(untouched.all { it.toInt() == 1234 }) { "Invalid input partially wrote output" }
            pixels += width*height
        }
        check(input.read() == -1)
        return pixels
    }
}

fun main(args: Array<String>) {
    val path = args.single()
    val pixels = java.io.File(path).inputStream().use(::verifyNativeGrainOracle)
    println("NativeGrain8Patch: $pixels native-oracle pixels exact; in-place, tiled phase, immutable preparation and rejection checks passed")
}
