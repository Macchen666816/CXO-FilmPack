package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.InputStream

fun verifyGrainRenderingContext(contexts: NativeGrain8RenderingContext, fixture: InputStream): Int =
    DataInputStream(fixture.buffered()).use { input ->
        fun data(n: Int) = ByteArray(n).also(input::readFully)
        fun i32() = Integer.reverseBytes(input.readInt())
        check(String(data(8),Charsets.US_ASCII) == "CXOGCT01" && i32() == 1)
        val count = i32(); check(count == 99)
        repeat(count) {
            val n = i32(); check(n in 1..256); val identifier = String(data(n),Charsets.UTF_8)
            val enum = i32(); val color = i32(); val neutralize = i32()
            check(contexts.definition(identifier)?.nativeEnum == enum)
            val flags = contexts.resolve(identifier)
            check(flags.color == (color == 1) && flags.neutralizeRedBlue == (neutralize == 1)) {
                "Original context flags differ for $identifier"
            }
        }
        check(input.read() == -1); count
    }
