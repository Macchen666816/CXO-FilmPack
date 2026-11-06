package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private fun bwJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    is Number -> value.toString().also { require(value.toDouble().isFinite()) }
    is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { bwJson(it.key.toString()) + ":" + bwJson(it.value) }
    is Iterable<*> -> value.joinToString(",", "[", "]", transform = ::bwJson)
    else -> error("Unsupported BW report value")
}

private fun bwExact(actual: FloatArray, expected: FloatArray, name: String) {
    require(actual.size == expected.size)
    check(actual.indices.all { actual[it].toRawBits() == expected[it].toRawBits() }) {
        "$name differs by ${actual.indices.maxOf { abs(actual[it] - expected[it]) }}"
    }
}

private fun bwMetrics(actual: ShortArray, expected: ShortArray): Map<String, Any> {
    require(actual.size == expected.size)
    var peak = 0; var sum = 0.0
    for (i in actual.indices) {
        val delta = actual[i].toInt() - expected[i].toInt()
        peak = max(peak, abs(delta)); sum += delta.toDouble() * delta
    }
    return linkedMapOf("max_q15" to peak, "rmse_255" to sqrt(sum / actual.size) * 255.0 / 32767,
        "within_32_q15" to (peak <= 32))
}

private fun bwGridMetrics(actual: FloatArray, expected: FloatArray): Map<String, Any> {
    require(actual.size == expected.size && actual.all(Float::isFinite) && expected.all(Float::isFinite))
    var mask = 0; var peak = 0f
    for (i in actual.indices step 3) {
        val ai = actual[i] == -1000f; val ei = expected[i] == -1000f
        if (ai != ei) mask++
        if (ai || ei) continue
        peak = max(peak, max(abs(actual[i + 1] - expected[i + 1]), abs(actual[i + 2] - expected[i + 2])))
    }
    return linkedMapOf("invalid_mask_mismatch_nodes" to mask, "sv_max" to peak, "bw_hue_excluded" to true)
}

/** Static fixture has calibration/film inputs only; original binaries are oracles. */
fun main(args: Array<String>) {
    require(args.size == 3) { "Expected static fixture, native directory, report" }
    val native = File(args[1]); val report = File(args[2])
    fun buffer(file: File) = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    fun floats(file: File, count: Int): FloatArray {
        val b = buffer(file); require(b.remaining() == count * 4)
        return FloatArray(count) { b.float }
    }
    fun matrix(b: ByteBuffer, offset: Int) = FloatArray(9) { b.getFloat(offset + 4 * it) }
    fun grid(file: File): FloatArray {
        val packed = floats(file, 17 * 91 * 12 * 4)
        return FloatArray(RawHsvGridBaker.GRID_FLOATS).also { result ->
            var at = 0
            for (s in 0..16) for (h in 0..90) for (v in 0..11) {
                val p = ((v * 91 + h) * 17 + s) * 4
                for (c in 0..2) result[at++] = packed[p + c]
            }
        }
    }
    fun carrier(file: File): ShortArray {
        val b = buffer(file); require(b.remaining() == 4920 * 3 * 2)
        return ShortArray(4913 * 3) { b.short }.also { check(it.all { code -> code >= 0 }) }
    }
    val rows = DataInputStream(File(args[0]).inputStream().buffered()).use { data ->
        fun int() = Integer.reverseBytes(data.readInt())
        fun float() = Float.fromBits(int())
        fun string(): String = ByteArray(int().also { require(it in 1..1024) }).also(data::readFully).toString(Charsets.UTF_8)
        fun array() = FloatArray(int().also { require(it in 1..32769) }) { float() }
        val magic = ByteArray(8).also(data::readFully); check(magic.contentEquals("CXOBW001".toByteArray()))
        List(int().also { require(it == 6) }) {
            val camera = string(); val id = string(); val providerPrefix = string(); val applyPrefix = string()
            val temperature = float(); val bias = float()
            val fidelityLow = float(); val fidelityHigh = float(); val tungsten = array(); val daylight = array()
            val jpegLow = float(); val jpegHigh = float(); val jpegTungsten = array(); val jpegDaylight = array()
            val useCameraMatrix = int().also { require(it == 0 || it == 1) } != 0
            val filmCurve = array(); val toneCurve = array(); val post = array()
            val row = linkedMapOf<String, Any?>("camera" to camera, "film_id" to id,
                "temperature" to temperature, "provider_prefix" to providerPrefix, "apply_prefix" to applyPrefix)
            try {
                fun provider(suffix: String) = File(native, "$providerPrefix.$suffix")
                fun apply(suffix: String) = File(native, "$applyPrefix.$suffix")
                val sourceProfile = RawFidelityProfile(tungsten, daylight, fidelityLow, fidelityHigh)
                val sourceFidelity = RawTargetFidelityProfile(tungsten, daylight, fidelityLow, fidelityHigh).matrixAt(temperature)
                val jpeg = RawTargetFidelityProfile(jpegTungsten, jpegDaylight, jpegLow, jpegHigh).matrixAt(temperature)
                val engineExtra = sourceProfile.engineExtraMatrix()
                val sourceSensor = sourceProfile.sensorToWorking(temperature)
                val bw = RawCameraBwProvider(id, filmCurve, post, useCameraMatrix)
                bwExact(bw.providerMatrix(jpeg, sourceFidelity), floats(provider("provider.param5-targetM1.f32.bin"), 9), "BW provider M1")
                val bake = bw.bake(bias, engineExtra, toneCurve, jpeg, sourceFidelity)
                val iface = buffer(apply("prepared.actual-interface.bin"))
                val rendering = buffer(apply("prepared.rendering-data.bin"))
                check(rendering.get(0).toInt() != 0 && iface.get(0x1659).toInt() != 0 && bake.runtimeBw)
                check(iface.get(0x18d4).toInt() == 0) { "Unexpected BW scales stage" }
                check(iface.getFloat(0x1714).toRawBits() == bias.toRawBits())
                check(rendering.getFloat(0x28).toRawBits() == bake.parameters.renderingEv.toRawBits())
                bwExact(sourceSensor, matrix(iface, 0x17b0), "BW source sensor")
                bwExact(engineExtra, matrix(iface, 0x15c8), "BW engine extra")
                bwExact(bake.parameters.preMatrix(), matrix(rendering, 4), "BW rendering pre")
                bwExact(bake.parameters.postMatrix(), matrix(rendering, 0x80), "BW rendering post")
                bwExact(bake.filmCurves[0], floats(apply("prepared.rendering-curve-48.f32le.bin"), 32769), "BW composed film curve")
                val preGrid = bwGridMetrics(bake.preRepairGrid, grid(provider("pre-repair.grid.f32.bin")))
                val repaired = bwGridMetrics(bake.repairedGrid, grid(apply("prepared.interface-storage-grid.bin")))
                val gridPass = preGrid["invalid_mask_mismatch_nodes"] == 0 && (preGrid["sv_max"] as Float) < .01f &&
                    (repaired["sv_max"] as Float) < .002f
                val input = carrier(apply("grid-input.q15.bin")); val nativeSensor = carrier(apply("grid-sensor-output.q15.bin"))
                val expected = carrier(apply("grid-full-output.q15.bin")); val work = ShortArray(input.size)
                RawSensorToWorking(sourceSensor, bias, iface.getFloat(0x1710)).renderQ15(input, work)
                val sensorMetrics = bwMetrics(work, nativeSensor)
                val renderer = bake.renderer(); val film = ShortArray(input.size)
                renderer.renderQ15(nativeSensor, film); val filmMetrics = bwMetrics(film, expected)
                renderer.renderQ15(work, work); val fullMetrics = bwMetrics(work, expected)
                row["static_m1_pre_post_curve_exact"] = true; row["static_sensor_extra_exact"] = true
                row["pre_grid"] = preGrid; row["repaired_grid"] = repaired
                row["sensor_output"] = sensorMetrics; row["film_output"] = filmMetrics; row["full_output"] = fullMetrics
                row["success"] = gridPass && sensorMetrics["within_32_q15"] == true &&
                    filmMetrics["within_32_q15"] == true && fullMetrics["within_32_q15"] == true
            } catch (t: Exception) {
                row["success"] = false; row["error"] = "${t.javaClass.simpleName}: ${t.message}"
            }
            row
        }.also { check(data.read() == -1) }
    }
    val passed = rows.count { it["success"] == true }
    report.parentFile.mkdirs(); report.writeText(bwJson(linkedMapOf("success" to (passed == rows.size),
        "contexts" to rows.size, "passed" to passed, "required_max_q15" to 32, "rows" to rows)) + "\n")
    println("Camera BW static providers: $passed/${rows.size}; exact matrices/curves + native full Apply gate")
    check(passed == rows.size) { "Camera BW provider regression failed: ${report.absolutePath}" }
}
