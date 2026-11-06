package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private fun staticJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
    is Number -> value.toString().also { require(value.toDouble().isFinite()) }
    is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { staticJson(it.key.toString()) + ":" + staticJson(it.value) }
    is Iterable<*> -> value.joinToString(",", "[", "]", transform = ::staticJson)
    else -> error("Unsupported JSON ${value.javaClass}")
}

private fun staticMax(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size)
    return a.indices.maxOf { abs(a[it] - b[it]) }
}

private fun staticExact(actual: FloatArray, expected: FloatArray, name: String) {
    require(actual.size == expected.size)
    check(actual.indices.all { actual[it].toRawBits() == expected[it].toRawBits() }) {
        "$name differs, max ${staticMax(actual, expected)}"
    }
}

private fun staticGridMetrics(actual: FloatArray, expected: FloatArray, bw: Boolean): Map<String, Any?> {
    require(actual.size == expected.size && actual.all(Float::isFinite) && expected.all(Float::isFinite))
    var sv = 0f; var hue = 0f; var invalid = 0; var common = 0
    for (i in actual.indices step 3) {
        val ai = actual[i] == -1000f; val bi = expected[i] == -1000f
        if (ai != bi) invalid++
        if (ai || bi) continue
        common++
        sv = max(sv, max(abs(actual[i + 1] - expected[i + 1]), abs(actual[i + 2] - expected[i + 2])))
        val delta = abs(actual[i] - expected[i]) % 360f
        hue = max(hue, minOf(delta, 360f - delta))
    }
    return linkedMapOf("common_valid_nodes" to common, "sv_max" to sv,
        "circular_hue_max" to if (bw) null else hue, "bw_hue_excluded" to bw,
        "invalid_mask_mismatch_nodes" to invalid, "invalid_mask_exact" to (invalid == 0))
}

private fun staticOutputMetrics(actual: ShortArray, expected: ShortArray): Map<String, Any> {
    require(actual.size == expected.size)
    var peak = 0; var squared = 0.0; var different = 0
    for (i in actual.indices) {
        val delta = actual[i].toInt() - expected[i].toInt()
        peak = max(peak, abs(delta)); squared += delta.toDouble() * delta
        if (delta != 0) different++
    }
    return linkedMapOf("max_q15" to peak, "rmse_255" to sqrt(squared / actual.size) * 255.0 / 32767.0,
        "different_components" to different, "components" to actual.size, "within_32_q15" to (peak <= 32))
}

/** One static production path: source-camera profile, provider parameters/curves,
 * bake/repair, sensor and selected-film Apply. Captured values are comparison or
 * runtime scene inputs only; no captured matrix/grid replaces a static provider.
 */
fun main(args: Array<String>) {
    require(args.size == 4) { "Expected local-data, native directory, audited TSV index, report" }
    val local = File(args[0]); val native = File(args[1]); val index = File(args[2]); val report = File(args[3])
    val sources = linkedMapOf<String, String>()
    fun bytes(file: File): ByteArray = file.readBytes().also { data ->
        sources[file.absolutePath] = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun buffer(file: File) = ByteBuffer.wrap(bytes(file)).order(ByteOrder.LITTLE_ENDIAN)
    fun floats(file: File, count: Int): FloatArray {
        val input = buffer(file); require(input.remaining() == count * 4)
        return FloatArray(count) { input.float }.also { require(it.all(Float::isFinite)) }
    }
    fun matrix(input: ByteBuffer, offset: Int) = FloatArray(9) { input.getFloat(offset + 4 * it) }
    fun grid(file: File): FloatArray {
        val packed = floats(file, 17 * 91 * 12 * 4)
        return FloatArray(RawHsvGridBaker.GRID_FLOATS).also { result ->
            var at = 0
            for (s in 0..16) for (h in 0..90) for (v in 0..11) {
                val start = ((v * 91 + h) * 17 + s) * 4
                for (c in 0..2) result[at++] = packed[start + c]
            }
        }
    }
    fun carrier(file: File): ShortArray {
        val input = buffer(file); require(input.remaining() == 4920 * 3 * 2)
        return ShortArray(4913 * 3) { input.short }.also { require(it.all { code -> code >= 0 }) }
    }
    fun catalog(path: String) = File(local, path).let { file ->
        bytes(file).inputStream().use(RawNegativeProviderCatalog::read)
    }
    val negative = catalog("raw-negative-providers/catalog.cxornp")
    val fixed = catalog("raw-static-providers/catalog.cxornp")
    check(negative.ids.size == 68 && fixed.ids.size == 75 && negative.ids.intersect(fixed.ids).isEmpty())
    val profiles = mapOf("S1RII" to s9Profile(File(local, "raw-profile/s1rii-fidelity.json")),
        "S9" to s9Profile(File(local, "raw-profile/s9-fidelity.json")))
    profiles.keys.forEach { camera -> bytes(File(local, "raw-profile/${if (camera == "S9") "s9" else "s1rii"}-fidelity.json")) }
    val lines = index.readLines(); bytes(index)
    require(lines.first() == "camera\ttoken\tid\tprovider_prefix\tapply_prefix\ttemp")
    val entries = lines.drop(1).filter(String::isNotBlank).map { it.split('\t').also { row -> require(row.size == 6) } }
    require(entries.size >= 8 && entries.map { it[3] to it[4] }.toSet().size == entries.size)
    val results = entries.map { row ->
        val camera = row[0]; val token = row[1]; val id = row[2]; val temperature = row[5].toFloat()
        val result = linkedMapOf<String, Any?>("camera" to camera, "token" to token, "film_id" to id,
            "temperature" to temperature, "provider_prefix" to row[3], "apply_prefix" to row[4])
        try {
            fun providerFile(suffix: String) = File(native, "${row[3]}.$suffix")
            fun applyFile(suffix: String) = File(native, "${row[4]}.$suffix")
            val provider = requireNotNull(negative.film(id) ?: fixed.film(id)) { "Missing static provider $id" }
            val (profile, bias) = requireNotNull(profiles[camera]) { "Unknown source camera $camera" }
            val rendering = buffer(providerFile("prepared.rendering-data.bin"))
            val iface = buffer(providerFile("prepared.actual-interface.bin"))
            val applyRendering = buffer(applyFile("prepared.rendering-data.bin"))
            val applyIface = buffer(applyFile("prepared.actual-interface.bin"))
            val nativeEv = iface.getFloat(0x1714)
            check(nativeEv.toRawBits() == bias.toRawBits() && applyIface.getFloat(0x1714).toRawBits() == bias.toRawBits())
            val sensorMatrix = profile.sensorToWorking(temperature)
            val extraMatrix = profile.engineExtraMatrix()
            staticExact(sensorMatrix, matrix(iface, 0x17b0), "provider sensor matrix")
            staticExact(sensorMatrix, matrix(applyIface, 0x17b0), "Apply sensor matrix")
            staticExact(extraMatrix, matrix(iface, 0x15c8), "provider engine extra matrix")
            staticExact(extraMatrix, matrix(applyIface, 0x15c8), "Apply engine extra matrix")
            result["sensor_matrix_max"] = 0f; result["engine_extra_matrix_max"] = 0f
            val bake = provider.bake(temperature, bias, extraMatrix) { name ->
                bytes(File(local, "raw-films/curves/$name")).inputStream().use(RawNegativeProviderCatalog::readQ15Curve)
            }
            val count = when (rendering.getInt(0x78)) { 0 -> 1; 1 -> 3; else -> error("Unknown native curve kind") }
            check(provider.typeBw == (rendering.get(0).toInt() != 0))
            check(provider.runtimeBw == (iface.get(0x1659).toInt() != 0))
            check(provider.runtimeBw == (applyIface.get(0x1659).toInt() != 0))
            check(bake.filmCurves.size == count)
            check(bake.parameters.renderingEv.toRawBits() == rendering.getFloat(0x28).toRawBits())
            check(bake.parameters.renderingEv.toRawBits() == applyRendering.getFloat(0x28).toRawBits())
            staticExact(bake.parameters.preMatrix(), matrix(rendering, 4), "static provider pre")
            staticExact(bake.parameters.preMatrix(), matrix(applyRendering, 4), "Apply static provider pre")
            staticExact(bake.parameters.postMatrix(), matrix(rendering, 0x80), "static provider post")
            staticExact(bake.parameters.postMatrix(), matrix(applyRendering, 0x80), "Apply static provider post")
            for (c in 0 until count) {
                val suffix = "prepared.rendering-curve-${48 + 24 * c}.f32le.bin"
                staticExact(bake.filmCurves[c], floats(providerFile(suffix), 32769), "static curve $c")
                staticExact(bake.filmCurves[c], floats(applyFile(suffix), 32769), "Apply static curve $c")
            }
            result["static_provider_pre_max"] = 0f; result["static_provider_post_max"] = 0f
            result["static_rendering_ev_exact"] = true; result["static_curve_max"] = 0f
            result["type_bw"] = provider.typeBw; result["runtime_bw"] = provider.runtimeBw
            result["curve_count"] = count; result["camera_bias_ev"] = bias
            val hasStage = (applyIface.get(0x18d4).toInt() and 1) != 0
            check(hasStage == provider.requiresAdditionalColorStage)
            val colorStage = if (hasStage) {
                check((applyIface.get(0x18dc).toInt() and 1) != 0) { "Native intent optional absent" }
                staticExact(provider.scaleFilterMatrix(), matrix(applyIface, 0x18b0), "static scales")
                val intent = applyIface.getFloat(0x18d8)
                result["captured_runtime_scene_intent"] = intent
                result["static_scales_matrix_max"] = 0f
                provider.colorStage(intent)
            } else null
            result["scales_stage"] = hasStage
            val nativePre = grid(providerFile("pre-repair.grid.f32.bin"))
            val nativeRepaired = grid(providerFile("repaired.grid.f32.bin"))
            staticExact(nativeRepaired, grid(applyFile("prepared.interface-storage-grid.bin")), "independent Apply native grid")
            val pre = staticGridMetrics(bake.preRepairGrid, nativePre, provider.typeBw)
            val repaired = staticGridMetrics(bake.repairedGrid, nativeRepaired, provider.typeBw)
            result["pre_grid"] = pre; result["repaired_grid"] = repaired
            val gridPass = pre["invalid_mask_exact"] == true && (pre["sv_max"] as Float) < .01f &&
                (repaired["sv_max"] as Float) < .002f
            val source = carrier(applyFile("grid-input.q15.bin"))
            val nativeSensor = carrier(applyFile("grid-sensor-output.q15.bin"))
            val expected = carrier(applyFile("grid-full-output.q15.bin"))
            val working = ShortArray(source.size)
            // Ordinary Execute may set a scene-dependent highlight value; in the
            // S9 captures it can differ from the initial provider Prepare value.
            val highlight = applyIface.getFloat(0x1710)
            RawSensorToWorking(sensorMatrix, bias, highlight).renderQ15(source, working)
            val sensorMetrics = staticOutputMetrics(working, nativeSensor)
            val renderer = bake.renderer(colorStage)
            val film = ShortArray(source.size); renderer.renderQ15(nativeSensor, film)
            val filmMetrics = staticOutputMetrics(film, expected)
            renderer.renderQ15(working, working)
            val fullMetrics = staticOutputMetrics(working, expected)
            result["runtime_highlight_control"] = highlight
            result["sensor_output"] = sensorMetrics; result["film_output"] = filmMetrics
            result["sensor_and_film_output"] = fullMetrics; result["grid_gate_pass"] = gridPass
            result["success"] = gridPass && sensorMetrics["within_32_q15"] == true &&
                filmMetrics["within_32_q15"] == true && fullMetrics["within_32_q15"] == true
        } catch (t: Exception) {
            result["success"] = false; result["error"] = "${t.javaClass.simpleName}: ${t.message}"
        }
        result
    }
    val success = results.all { it["success"] == true }
    val summary = linkedMapOf<String, Any?>("success" to success, "contexts" to results.size,
        "passed" to results.count { it["success"] == true }, "source_cameras" to entries.map { it[0] }.toSet().toList(),
        "negative_catalog_count" to negative.ids.size, "fixed_catalog_count" to fixed.ids.size,
        "scales_contexts" to results.count { it["scales_stage"] == true }, "valid_pixels_per_context" to 4913,
        "carrier_pixels" to 4920, "required_max_q15" to 32, "required_pre_grid_sv" to .01f,
        "required_repaired_grid_sv" to .002f, "rows" to results, "source_sha256" to sources)
    report.parentFile.mkdirs(); report.writeText(staticJson(summary) + "\n")
    println("Static RAW providers: ${results.count { it["success"] == true }}/${results.size}; profile/provider exact + grid gates + <=32 Q15")
    check(success) { "Static RAW provider regressions failed; see ${report.absolutePath}" }
}
