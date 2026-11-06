package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.InputStream

data class GrainPreviewGeometryResult(val records: Int, val preparedComponents: Long, val pixels: Long)
data class GrainPreviewGeometryCase(
    val sourceWidth: Int, val sourceHeight: Int, val level: Int,
    val format: NativeGrain8Geometry.Format, val manual: Float, val seed: Long,
    val color: Boolean, val neutralize: Boolean,
)

fun verifyGrainPreviewGeometry(fixture: InputStream, definition: NativeGrain8Catalog.Definition,
                              source: ByteArray, lut: NativeGrain8ColorLut,
                              productKernel: ((GrainPreviewGeometryCase) -> NativeGrain8Patch)? = null): GrainPreviewGeometryResult =
    DataInputStream(fixture.buffered()).use { input ->
        fun bytes(n: Int) = ByteArray(n).also(input::readFully)
        fun i32() = Integer.reverseBytes(input.readInt())
        fun q15(n: Int) = ShortArray(n) { java.lang.Short.reverseBytes(input.readShort()) }
        check(String(bytes(8),Charsets.US_ASCII) == "CXOGPG02"); check(i32() == 2)
        val records = i32(); check(records == 7)
        var components = 0L; var pixels = 0L
        repeat(records) { record ->
            val level = i32(); val enum = i32(); val seed = i32().toLong() and 0xFFFFFFFFL
            val sourceWidth = i32(); val sourceHeight = i32(); val format = NativeGrain8Geometry.Format.entries[i32()]
            val manual = Float.fromBits(i32()); val nativeSize = i32()
            val canvasWidth = i32(); val canvasHeight = i32(); val originX = i32(); val originY = i32()
            val width = i32(); val height = i32(); val factor = Float.fromBits(i32()); val size = i32(); val period = i32()
            val color = i32() != 0; val neutralize = i32() != 0; val byteCount = i32()
            check(enum == definition.grainEnum && canvasWidth == sourceWidth shr level && canvasHeight == sourceHeight shr level)
            check(originX >= 0 && originY >= 0 && originX + width <= canvasWidth && originY + height <= canvasHeight)
            check(byteCount == size*size*3)
            val expectedPatch = bytes(byteCount); val expectedAmplitude = IntArray(256) { i32() }
            val nodeInput = q15(width*height*3); val expected = q15(nodeInput.size)
            val geometry = NativeGrain8Geometry.resolve(enum,format,sourceWidth,sourceHeight,manual,level)
            check(geometry.nativeSize == nativeSize && geometry.scale.toRawBits() == factor.toRawBits() &&
                geometry.size == size && geometry.period == period) { "Preview geometry differs at record $record" }
            val prepared = definition.prepare(source,geometry.scale,lut,seed)
            check(prepared.bytes.contentEquals(expectedPatch)) { "Preview prepared patch differs at record $record" }
            val amplitude = definition.amplitude(100); check(amplitude.contentEquals(expectedAmplitude))
            val kernel = productKernel?.invoke(GrainPreviewGeometryCase(sourceWidth,sourceHeight,level,format,manual,seed,color,neutralize))
                ?: NativeGrain8Patch(prepared.bytes,size,size,period,amplitude,color,neutralize)
            val actual = ShortArray(nodeInput.size)
            kernel.renderRgbQ15(nodeInput,actual,width,originX,originY)
            check(actual.contentEquals(expected)) { "Preview node differs at record $record" }
            // Also recompose irregular tiles using the current-level absolute origin.
            for (top in 0 until height step 13) for (left in 0 until width step 127) {
                val w = minOf(127,width-left); val h = minOf(13,height-top); val tile = ShortArray(w*h*3)
                repeat(h) { row -> nodeInput.copyInto(tile,row*w*3,((top+row)*width+left)*3,((top+row)*width+left+w)*3) }
                kernel.renderRgbQ15(tile,tile,w,originX+left,originY+top)
                repeat(h) { row -> for (i in 0 until w*3) check(tile[row*w*3+i] == expected[((top+row)*width+left)*3+i]) }
            }
            components += byteCount; pixels += width.toLong()*height
        }
        check(input.read() == -1)
        GrainPreviewGeometryResult(records,components,pixels)
    }
