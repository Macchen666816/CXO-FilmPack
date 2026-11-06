package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File

/** Synthetic branch coverage; no original FilmPack pixels or added film acceptance. */
internal fun verifyHsvBranches(directory: File) {
    val input = DataInputStream(File(directory, "branches.golden").inputStream().buffered())
    fun int() = Integer.reverseBytes(input.readInt())
    input.use {
        val magic = ByteArray(8); input.readFully(magic)
        check(magic.contentEquals("CXOBRG01".toByteArray(Charsets.US_ASCII)))
        val count = int(); val n = int(); check(count == 8 && n == 2430)
        val source = FloatArray(n * 3) { Float.fromBits(int()) }
        repeat(count) { case ->
            val length = int(); check(length in 1..160)
            val name = ByteArray(length); input.readFully(name)
            val bytes = File(directory, name.toString(Charsets.US_ASCII)).readBytes()
            val film = HsvFilmLutIO.read(bytes.inputStream())
            check(film.jpegToneMode == case / 4)
            check(film.repairBwType == (case / 2 % 2 == 1))
            check(film.runtimeBw == (case % 2 == 1))
            val expected = FloatArray(n * 3) { Float.fromBits(int()) }
            val actual = source.copyOf(); film.renderRgb(actual, actual)
            val one = FloatArray(3)
            for (i in actual.indices) check(actual[i].toRawBits() == expected[i].toRawBits()) {
                "Synthetic HSV branch $case mismatch at $i: ${actual[i]} vs ${expected[i]}"
            }
            for (i in 0 until n step 17) {
                film.sample(source[i * 3], source[i * 3 + 1], source[i * 3 + 2], one)
                for (c in 0..2) check(one[c].toRawBits() == expected[i * 3 + c].toRawBits())
            }
            if (film.runtimeBw) for (i in actual.indices step 3) {
                check(actual[i] == actual[i + 1] && actual[i] == actual[i + 2])
            }
            fun fails(bad: ByteArray) = check(runCatching { HsvFilmLutIO.read(bad.inputStream()) }.isFailure)
            for (offset in intArrayOf(28, 32, 36)) {
                val bad = bytes.copyOf(); bad[offset] = 2; fails(bad)
            }
            fails(bytes.copyOf(bytes.size - 1)); fails(bytes + byteArrayOf(0))
        }
        check(input.read() == -1)
        println("""{"synthetic_hsv_branch_cases":$count,"samples_per_case":$n,"float_components":${count * n * 3},"unequal_float_components":0,"in_place_and_sample_checks":"passed","new_native_heldout_validations":0}""")
    }
}
