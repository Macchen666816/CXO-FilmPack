package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private fun specialJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
    is Number -> value.toString().also { require(value.toDouble().isFinite()) }
    is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { specialJson(it.key.toString()) + ":" + specialJson(it.value) }
    is Iterable<*> -> value.joinToString(",", "[", "]", transform = ::specialJson)
    else -> error("Unsupported JSON ${value.javaClass}")
}

private fun specialMax(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size)
    return a.indices.maxOf { abs(a[it] - b[it]) }
}

private fun specialExact(actual: FloatArray, expected: FloatArray, name: String) {
    require(actual.size == expected.size)
    check(actual.indices.all { actual[it].toRawBits() == expected[it].toRawBits() }) {
        "$name differs, max ${specialMax(actual, expected)}"
    }
}

private fun specialGridMetrics(actual: FloatArray, expected: FloatArray, bw: Boolean): Map<String, Any?> {
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

private fun specialOutputMetrics(actual: ShortArray, expected: ShortArray): Map<String, Any> {
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

/** Production static special8 catalog against independent original-engine oracles.
 * The only captured runtime inputs are highlight and scales/filter scene intent;
 * source camera matrices, provider parameters, curves and baked grids are rebuilt.
 */
fun main(args: Array<String>) {
    require(args.size == 5) { "Expected local-data, native directory, HSV7 index, RGB9 index, report" }
    val local = File(args[0]); val native = File(args[1]); val report = File(args[4])
    val sources = linkedMapOf<String, String>()
    var rowSources: MutableMap<String, String>? = null
    fun bytes(file: File): ByteArray = file.readBytes().also { data ->
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        sources[file.absolutePath] = digest
        rowSources?.put(file.absolutePath, digest)
    }
    fun buffer(file: File) = ByteBuffer.wrap(bytes(file)).order(ByteOrder.LITTLE_ENDIAN)
    fun floats(file: File, count: Int): FloatArray {
        val b = buffer(file); require(b.remaining() == count * 4)
        return FloatArray(count) { b.float }.also { require(it.all(Float::isFinite)) }
    }
    fun matrix(b: ByteBuffer, offset: Int) = FloatArray(9) { b.getFloat(offset + 4 * it) }
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
        val b = buffer(file); require(b.remaining() == 4920 * 3 * 2)
        return ShortArray(4913 * 3) { b.short }.also { require(it.all { v -> v >= 0 }) }
    }
    fun entries(file: File): List<List<String>> {
        val lines = bytes(file).toString(Charsets.UTF_8).lineSequence().toList()
        require(lines.first().trimEnd() == "camera\ttoken\tid\tprovider_prefix\tapply_prefix\ttemp")
        return lines.drop(1).filter(String::isNotBlank).map { line ->
            line.trimEnd('\r').split('\t').also { require(it.size == 6) }
        }
    }
    val catalogFile = File(local, "raw-special-providers/catalog.cxornp")
    val catalog = bytes(catalogFile).inputStream().use(RawNegativeProviderCatalog::read)
    check(catalog.ids.size == 8)
    val profileFiles = mapOf("S9" to File(local, "raw-profile/s9-fidelity.json"),
        "S1RII" to File(local, "raw-profile/s1rii-fidelity.json"))
    val profiles = profileFiles.mapValues { (_, file) -> bytes(file); s9Profile(file) }
    val hsvAll = entries(File(args[2]))
    val hsv = hsvAll.filter { it[1] !in setOf("KodakHIEFiltre", "RolleiIR400") }
    val rgb = entries(File(args[3]))
    check(hsvAll.size == 7 && hsv.size == 5 && rgb.size == 9)
    check(hsv.map { it[1] }.toSet() == setOf("Astia", "Provia", "Velvia", "Ekta100VS", "Kod64"))
    check(rgb.map { it[1] }.toSet() == setOf("KodakEIR", "KodakHIE", "CineTealAndPink"))
    val indexed = hsv.map { "HSV" to it } + rgb.map { "RGB" to it }
    check(indexed.map { it.second[4] }.toSet().size == 14)
    val results = indexed.map { (kind, row) ->
        val camera = row[0]; val token = row[1]; val id = row[2]; val temperature = row[5].toFloat()
        val result = linkedMapOf<String, Any?>("kind" to kind, "camera" to camera, "token" to token,
            "film_id" to id, "temperature" to temperature, "provider_prefix" to row[3], "apply_prefix" to row[4])
        val hashes = linkedMapOf<String, String>()
        hashes[catalogFile.absolutePath] = requireNotNull(sources[catalogFile.absolutePath])
        val profileFile = requireNotNull(profileFiles[camera])
        hashes[profileFile.absolutePath] = requireNotNull(sources[profileFile.absolutePath])
        rowSources = hashes
        try {
            fun applyFile(suffix: String) = File(native, "${row[4]}.$suffix")
            fun providerFile(suffix: String) = File(native, "${row[3]}.$suffix")
            val provider = requireNotNull(catalog.film(id)) { "Missing static special provider $id" }
            val (profile, bias) = requireNotNull(profiles[camera])
            val applyRd = buffer(applyFile("prepared.rendering-data.bin"))
            val applyIface = buffer(applyFile("prepared.actual-interface.bin"))
            val oraclePairs = mutableListOf("Apply" to (applyRd to applyIface))
            if (row[3].isNotBlank()) oraclePairs += "Provider" to
                (buffer(providerFile("prepared.rendering-data.bin")) to buffer(providerFile("prepared.actual-interface.bin")))
            val sensorMatrix = profile.sensorToWorking(temperature)
            val extraMatrix = profile.engineExtraMatrix()
            val parameters = provider.parameters(temperature, bias)
            val curves = provider.curveNames.map { name ->
                bytes(File(local, "raw-films/curves/$name")).inputStream().use(RawNegativeProviderCatalog::readQ15Curve)
            }.toTypedArray()
            val readCurve: (String) -> FloatArray = { name ->
                val index = provider.curveNames.indexOf(name)
                require(index >= 0); curves[index].copyOf()
            }
            for ((label, pair) in oraclePairs) {
                val (rd, iface) = pair
                check(iface.getFloat(0x1714).toRawBits() == bias.toRawBits()) { "$label camera EV" }
                specialExact(sensorMatrix, matrix(iface, 0x17b0), "$label source sensor matrix")
                specialExact(extraMatrix, matrix(iface, 0x15c8), "$label source extra matrix")
                check(parameters.renderingEv.toRawBits() == rd.getFloat(0x28).toRawBits()) { "$label rendering EV" }
                specialExact(parameters.preMatrix(), matrix(rd, 4), "$label static provider pre")
                specialExact(parameters.postMatrix(), matrix(rd, 0x80), "$label static provider post")
                check(provider.typeBw == (rd.get(0).toInt() != 0)) { "$label type BW" }
                check(provider.runtimeBw == (iface.get(0x1659).toInt() != 0)) { "$label runtime BW" }
                val curveCount = when (rd.getInt(0x78)) { 0 -> 1; 1 -> 3; else -> error("Unknown curve kind") }
                check(curves.size == curveCount)
                for (c in curves.indices) {
                    val suffix = "prepared.rendering-curve-${48 + 24 * c}.f32le.bin"
                    specialExact(curves[c], floats(if (label == "Apply") applyFile(suffix) else providerFile(suffix), 32769), "$label static curve $c")
                }
            }
            result["sensor_matrix_exact"] = true; result["engine_extra_matrix_exact"] = true
            result["static_pre_post_rendering_ev_curves_exact"] = true
            result["camera_bias_ev"] = bias; result["curve_count"] = curves.size
            result["runtime_bw"] = provider.runtimeBw; result["type_bw"] = provider.typeBw
            val hasStage = (applyIface.get(0x18d4).toInt() and 1) != 0
            check(hasStage == provider.requiresAdditionalColorStage) { "Scale/filter stage flag" }
            val stage = if (hasStage) {
                check((applyIface.get(0x18dc).toInt() and 1) != 0) { "Scale/filter scene intent absent" }
                specialExact(provider.scaleFilterMatrix(), matrix(applyIface, 0x18b0), "static scales/filter matrix")
                val intent = applyIface.getFloat(0x18d8)
                result["captured_runtime_scene_intent"] = intent
                result["static_scales_matrix_exact"] = true
                provider.colorStage(intent)
            } else null
            result["scales_stage"] = hasStage
            val source = carrier(applyFile("grid-input.q15.bin"))
            val nativeSensor = carrier(applyFile("grid-sensor-output.q15.bin"))
            val expected = carrier(applyFile("grid-full-output.q15.bin"))
            val highlight = applyIface.getFloat(0x1710)
            val working = ShortArray(source.size)
            RawSensorToWorking(sensorMatrix, bias, highlight).renderQ15(source, working)
            val sensorMetrics = specialOutputMetrics(working, nativeSensor)
            result["captured_runtime_highlight"] = highlight
            result["sensor_output"] = sensorMetrics
            val film = ShortArray(source.size)
            val full = working.copyOf()
            val gridPass: Boolean
            if (kind == "HSV") {
                check(row[3].isNotBlank())
                val bake = provider.bake(temperature, bias, extraMatrix, readCurve)
                specialExact(bake.parameters.preMatrix(), parameters.preMatrix(), "bake provider pre")
                specialExact(bake.parameters.postMatrix(), parameters.postMatrix(), "bake provider post")
                check(bake.parameters.renderingEv.toRawBits() == parameters.renderingEv.toRawBits())
                for (c in curves.indices) specialExact(bake.filmCurves[c], curves[c], "bake curve $c")
                val nativePre = grid(providerFile("pre-repair.grid.f32.bin"))
                val nativeRepaired = grid(providerFile("repaired.grid.f32.bin"))
                specialExact(nativeRepaired, grid(applyFile("prepared.interface-storage-grid.bin")), "independent prepared grid")
                val pre = specialGridMetrics(bake.preRepairGrid, nativePre, provider.typeBw)
                val repaired = specialGridMetrics(bake.repairedGrid, nativeRepaired, provider.typeBw)
                result["pre_grid"] = pre; result["repaired_grid"] = repaired
                gridPass = pre["invalid_mask_exact"] == true && (pre["sv_max"] as Float) < .01f &&
                    (repaired["sv_max"] as Float) < .002f
                val renderer = bake.renderer(stage)
                renderer.renderQ15(nativeSensor, film); renderer.renderQ15(full, full)
            } else {
                check(!hasStage)
                val context = provider.rgbContext(temperature, bias, extraMatrix, readCurve)
                val bakeParameters = context.bakeParameters()
                specialExact(bakeParameters.preMatrix, parameters.preMatrix(), "RGB context pre")
                specialExact(bakeParameters.postMatrix, parameters.postMatrix(), "RGB context post")
                specialExact(bakeParameters.extraMatrix, extraMatrix, "RGB context extra")
                check(context.ev.toRawBits() == bias.toRawBits() && context.renderingEv.toRawBits() == parameters.renderingEv.toRawBits())
                check(context.runtimeBw == provider.runtimeBw)
                for (c in curves.indices) specialExact(bakeParameters.filmCurves[c], curves[c], "RGB context curve $c")
                val baked = RawRgbGridBaker.bake(bakeParameters)
                val nativeGrid = floats(applyFile("prepared.interface-storage-grid.bin"), RawRgbGridBaker.GRID_FLOATS)
                val maximum = specialMax(baked, nativeGrid)
                result["rgb_grid_max"] = maximum
                gridPass = maximum <= 1e-4f && baked.all(Float::isFinite)
                if (row[3].isNotBlank()) {
                    specialExact(floats(providerFile("direct.grid.f32.bin"), RawRgbGridBaker.GRID_FLOATS), nativeGrid,
                        "original direct rebake vs independent prepared grid")
                    result["native_direct_rebake_exact"] = true
                }
                val renderer = RawRgbFilmRenderer(baked, context.runtimeBw)
                renderer.renderQ15(nativeSensor, film); renderer.renderQ15(full, full)
            }
            val filmMetrics = specialOutputMetrics(film, expected)
            val fullMetrics = specialOutputMetrics(full, expected)
            result["film_output"] = filmMetrics; result["sensor_and_film_output"] = fullMetrics
            result["grid_gate_pass"] = gridPass
            val sensorMax = sensorMetrics["max_q15"] as Int
            val filmMax = filmMetrics["max_q15"] as Int
            val fullMax = fullMetrics["max_q15"] as Int
            result["success"] = gridPass && sensorMax <= 1 &&
                filmMax <= (if (kind == "RGB") 2 else 32) && fullMax <= (if (kind == "RGB") 7 else 32)
        } catch (t: Exception) {
            result["success"] = false; result["error"] = "${t.javaClass.simpleName}: ${t.message}"
        }
        rowSources = null
        result["source_sha256"] = hashes
        result
    }
    val success = results.all { it["success"] == true }
    val summary = linkedMapOf<String, Any?>("success" to success, "contexts" to results.size,
        "passed" to results.count { it["success"] == true }, "static_catalog_count" to catalog.ids.size,
        "hsv_contexts" to hsv.size, "rgb_contexts" to rgb.size,
        "scales_contexts" to results.count { it["scales_stage"] == true }, "valid_pixels_per_context" to 4913,
        "native_runtime_highlight_and_scales_intent_matched_inputs" to true,
        "whole_photo_raw_decoder_or_auto_intent_parity_claimed" to false,
        "required_hsv_pre_sv" to .01f, "required_hsv_repaired_sv" to .002f,
        "required_hsv_film_full_max_q15" to 32, "required_rgb_grid_max" to 1e-4f,
        "required_rgb_film_max_q15" to 2, "required_rgb_full_max_q15" to 7,
        "rows" to results, "source_sha256" to sources)
    report.parentFile.mkdirs(); report.writeText(specialJson(summary) + "\n")
    println("Special static RAW providers: ${results.count { it["success"] == true }}/${results.size}; actual catalog parameters/bake/rgbContext")
    check(success) { "Special static RAW provider verification failed; see ${report.absolutePath}" }
}
