package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File
import java.io.InputStream

fun verifyGrainOrientation(stream: InputStream): Long {
    var pixels = 0L
    DataInputStream(stream.buffered()).use { input ->
        fun i32() = Integer.reverseBytes(input.readInt())
        fun bytes(n: Int) = ByteArray(n).also(input::readFully)
        check(String(bytes(8), Charsets.US_ASCII) == "CXOGORI1")
        check(i32() == 1)
        val count = i32(); check(count == 32)
        repeat(count) { record ->
            val sw = i32(); val sh = i32(); val orientation = i32(); val dw = i32(); val dh = i32()
            val source = bytes(sw * sh * 3); val unchanged = source.copyOf()
            val expected = bytes(dw * dh * 3)
            val actual = NativeGrain8Orientation.apply(source, sw, sh, orientation)
            check(actual.width == dw && actual.height == dh)
            check(actual.bytes.contentEquals(expected)) { "Native orientation mismatch record=$record" }
            check(source.contentEquals(unchanged) && actual.bytes !== source)
            val grain = NativeGrain8Orientation.rotateForGrain(source, sw, sh, orientation)
            val pureRotation = intArrayOf(0, 0, 2, 0, 5, 5, 7, 7)[orientation]
            val expectedGrain = NativeGrain8Orientation.apply(source, sw, sh, pureRotation)
            check(grain.bytes.contentEquals(expectedGrain.bytes))
            check(grain.width == expectedGrain.width && grain.height == expectedGrain.height)
            pixels += dw.toLong() * dh
        }
        check(input.read() == -1)
    }
    listOf("None", "HorizontalFlip", "180CW", "VerticalFlip", "90CWAndHorizontalFlip", "90CW", "270CWAndHorizontalFlip", "270CW")
        .forEachIndexed { i, value -> check(NativeGrain8Orientation.fromOutputParameter(value) == i) }
    listOf(
        { NativeGrain8Orientation.apply(ByteArray(0), 0, 1, 0) },
        { NativeGrain8Orientation.apply(ByteArray(3), Int.MAX_VALUE, Int.MAX_VALUE, 0) },
        { NativeGrain8Orientation.apply(ByteArray(2), 1, 1, 0) },
        { NativeGrain8Orientation.apply(ByteArray(3), 1, 1, 8) },
        { NativeGrain8Orientation.counterClockwise(ByteArray(3), 1, 1, -1) },
        { NativeGrain8Orientation.fromOutputParameter("90cw") },
        { NativeGrain8Orientation.fromNativeExifValue(0) },
    ).forEach { check(runCatching(it).exceptionOrNull() is IllegalArgumentException) }
    return pixels
}

fun main(args: Array<String>) {
    require(args.size == 1) { "Expected grain-orientation.golden path" }
    val pixels = File(args[0]).inputStream().use(::verifyGrainOrientation)
    println("NativeGrain8Orientation: 32 native records, $pixels pixels exact at all 8 native orientations")
}
