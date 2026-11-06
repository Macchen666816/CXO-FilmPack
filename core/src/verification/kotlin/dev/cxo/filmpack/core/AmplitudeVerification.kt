package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File

private class AmplitudeInput(stream: java.io.InputStream) : AutoCloseable {
    private val input = DataInputStream(stream.buffered())
    fun bytes(count: Int) = ByteArray(count).also(input::readFully)
    fun i32() = Integer.reverseBytes(input.readInt())
    fun eof() = input.read() == -1
    override fun close() = input.close()
}

fun main(args: Array<String>) {
    require(args.size == 1) { "Expected amplitude golden path" }
    var cases = 0
    AmplitudeInput(File(args[0]).inputStream()).use { input ->
        check(String(input.bytes(8), Charsets.US_ASCII) == "CXOGAMP1")
        check(input.i32() == 1)
        repeat(input.i32()) { index ->
            val table = input.bytes(256)
            val coefficient = input.i32()
            val intensity = input.i32()
            val expected = IntArray(256) { input.i32() }
            val argument = table.copyOf()
            val actual = NativeGrain8Amplitude.build(argument, coefficient, intensity)
            check(actual.contentEquals(expected)) { "Amplitude mismatch at case $index" }
            check(argument.contentEquals(table)) { "Amplitude input mutated at case $index" }
            cases++
        }
        check(input.eof())
    }
    val table = ByteArray(256) { (it xor 0xa5).toByte() }
    check(NativeGrain8Amplitude.build(table, 60, 0).contentEquals(IntArray(256)))
    val invalid = listOf<() -> Unit>(
        { NativeGrain8Amplitude.build(ByteArray(255), 1, 1) },
        { NativeGrain8Amplitude.build(ByteArray(257), 1, 1) },
        { NativeGrain8Amplitude.build(table, 0, 1) },
        { NativeGrain8Amplitude.build(table, 61, 1) },
        { NativeGrain8Amplitude.build(table, 1, -1) },
        { NativeGrain8Amplitude.build(table, 1, 201) },
    )
    invalid.forEach { check(runCatching(it).isFailure) }
    println("NativeGrain8Amplitude: $cases native amplitude cases exact; zero, input immutability and rejection checks passed")
}
