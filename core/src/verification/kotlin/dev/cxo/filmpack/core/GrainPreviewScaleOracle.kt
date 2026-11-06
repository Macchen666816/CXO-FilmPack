package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.InputStream

fun verifyGrainPreviewScale(fixture: InputStream, definition: NativeGrain8Catalog.Definition, source: ByteArray, lut: NativeGrain8ColorLut): Long = DataInputStream(fixture.buffered()).use { input ->
    fun bytes(n: Int) = ByteArray(n).also(input::readFully)
    fun i32() = Integer.reverseBytes(input.readInt())
    fun q15(n: Int) = ShortArray(n) { java.lang.Short.reverseBytes(input.readShort()) }
    check(String(bytes(8), Charsets.US_ASCII) == "CXOGPS01"); check(i32() == 1)
    val records = i32(); check(records == 2)
    var pixels = 0L
    repeat(records) {
        val level = i32(); val grainEnum = i32(); val seed = i32().toLong() and 0xFFFFFFFFL
        val width = i32(); val height = i32(); val scale = Float.fromBits(i32())
        val size = i32(); val period = i32(); val color = i32() != 0; val neutral = i32() != 0; val byteCount = i32()
        val expectedPatch = bytes(byteCount); val nodeInput = q15(width*height*3); val nodeOutput = q15(nodeInput.size)
        check(grainEnum == definition.grainEnum && width == 1024 shr level && height == width)
        val geometry = NativeGrain8Geometry.resolve(grainEnum,NativeGrain8Geometry.Format.MANUAL,1024,1024,1f,level)
        check(geometry.scale.toRawBits() == scale.toRawBits() && geometry.size == size && geometry.period == period)
        val prepared = definition.prepare(source,geometry.scale,lut,seed)
        check(prepared.bytes.contentEquals(expectedPatch)) { "Preview patch differs at level$level" }
        val kernel = NativeGrain8Patch(prepared.bytes,size,size,period,definition.amplitude(100),color,neutral)
        val output = ShortArray(nodeInput.size); kernel.renderRgbQ15(nodeInput,output,width)
        check(output.contentEquals(nodeOutput)) { "Preview full node differs at level$level" }
        // Nonzero tile origins use current-level global coordinates.
        for (top in 0 until height step 13) for (left in 0 until width step 127) {
            val w = minOf(127,width-left); val h = minOf(13,height-top); val tile = ShortArray(w*h*3)
            repeat(h) { row -> nodeInput.copyInto(tile,row*w*3,((top+row)*width+left)*3,((top+row)*width+left+w)*3) }
            kernel.renderRgbQ15(tile,tile,w,left,top)
            repeat(h) { row -> for (i in 0 until w*3) check(tile[row*w*3+i] == nodeOutput[((top+row)*width+left)*3+i]) }
        }
        pixels += width.toLong()*height
    }
    check(input.read() == -1); pixels
}
