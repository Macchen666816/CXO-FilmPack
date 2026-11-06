package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val TUNGSTEN = floatArrayOf(
    0.8313598758f, 0.2920885017f, -0.1234483774f,
    -0.3254226160f, 1.5039939078f, -0.1785712917f,
    0.0543762373f, -0.6441084571f, 1.5897322198f,
)

private val DAYLIGHT = floatArrayOf(
    1.0648800882f, 0.0420348410f, -0.1069149292f,
    -0.2246234103f, 1.5862176287f, -0.3615942185f,
    0.0265898779f, -0.4437068768f, 1.4171169989f,
)

private data class Capture(
    val name: String,
    val temperature: Float,
    val tint: Float,
    val file: String,
    val expected: FloatArray,
)

private fun readMatrix(path: String): FloatArray {
    val bytes = File(path).readBytes()
    check(bytes.size >= 0x17b0 + 36) { "$path: interface dump is too short" }
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    input.position(0x17b0)
    return FloatArray(9) { input.float }
}

private fun checkExact(actual: FloatArray, expected: FloatArray, name: String) {
    check(actual.size == 9 && expected.size == 9)
    for (i in actual.indices) {
        check(actual[i].toRawBits() == expected[i].toRawBits()) {
            "$name component $i differs: ${actual[i]} vs ${expected[i]}"
        }
    }
}

private fun expectFailure(block: () -> Unit) {
    check(runCatching(block).isFailure) { "expected argument rejection" }
}

fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: "/Users/chenyixuan/ReverseWork/DxOFilmPack/evidence/raw-fidelity-20261103")
    val captures = listOf(
        Capture(
            "P1040758",
            4199f,
            12.795665740966797f,
            "native/dynamic-compute-s1rii-p1040758-elite-rawpost.tif.after.actual-interface.bin",
            floatArrayOf(
                0.7870025634765625f, 0.281951904296875f, -0.0689544677734375f,
                -0.135284423828125f, 1.3944244384765625f, -0.2591400146484375f,
                0.0472869873046875f, -0.4303131103515625f, 1.383026123046875f,
            ),
        ),
        Capture(
            "P1176257",
            5485f,
            -2.126598358154297f,
            "native/dynamic-compute-s1rii-original-raw-context-root.tif.after.actual-interface.bin",
            floatArrayOf(
                0.8571624755859375f, 0.217529296875f, -0.0746917724609375f,
                -0.0970458984375f, 1.4120330810546875f, -0.3149871826171875f,
                0.0412139892578125f, -0.3666229248046875f, 1.325408935546875f,
            ),
        ),
    )
    val profile = RawFidelityProfile(TUNGSTEN, DAYLIGHT, 3425f, 4600f)
    captures.forEach { capture ->
        val expected = readMatrix(root.resolve(capture.file).path)
        checkExact(expected, capture.expected, "${capture.name} fixture")
        val actual = profile.sensorToWorking(capture.temperature)
        checkExact(actual, expected, capture.name)
        check(capture.tint.isFinite())
        check(actual.all { it.isFinite() })
        println("${capture.name}: 9/9 Float32 components exact; temperature=${capture.temperature} tint=${capture.tint}")
    }

    checkExact(profile.sensorToWorking(3425f), profile.sensorToWorking(2000f), "Tungsten endpoint")
    checkExact(profile.sensorToWorking(4600f), profile.sensorToWorking(5485f), "Daylight endpoint")

    val tungstenInput = TUNGSTEN.copyOf()
    val daylightInput = DAYLIGHT.copyOf()
    val isolated = RawFidelityProfile(tungstenInput, daylightInput, 3425f, 4600f)
    tungstenInput.fill(Float.NaN); daylightInput.fill(Float.NaN)
    checkExact(isolated.sensorToWorking(4199f), profile.sensorToWorking(4199f), "copied parameters")
    val returned = profile.sensorToWorking(4199f)
    returned[0] = Float.NaN
    check(profile.sensorToWorking(4199f)[0].isFinite())

    val feature5Enabled = RawFidelityProfile(TUNGSTEN, DAYLIGHT, 3425f, 4600f, true)
    check(!feature5Enabled.sensorToWorking(4199f).contentEquals(profile.sensorToWorking(4199f)))
    expectFailure { RawFidelityProfile(FloatArray(8), DAYLIGHT, 3425f, 4600f) }
    expectFailure { RawFidelityProfile(TUNGSTEN, DAYLIGHT, Float.NaN, 4600f) }
    expectFailure { RawFidelityProfile(TUNGSTEN, DAYLIGHT, 4600f, 3425f) }
    expectFailure { profile.sensorToWorking(Float.NaN) }
    expectFailure { profile.sensorToWorking(Float.POSITIVE_INFINITY) }
    println("RAW fidelity profile verification passed: 2 captured matrices, endpoints, feature-5 branch, and defensive checks")
}
