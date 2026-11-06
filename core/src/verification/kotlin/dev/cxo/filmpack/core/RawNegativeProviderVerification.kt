package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private const val NEGATIVE_GRID_SV_PRE_TOLERANCE = .01f
private const val NEGATIVE_GRID_SV_REPAIR_TOLERANCE = .002f
private const val NEGATIVE_OUTPUT_Q15_TOLERANCE = 32

private fun npBuffer(file: File) = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
private fun npFloats(file: File, expected: Int): FloatArray {
    val b = npBuffer(file)
    require(b.remaining() == expected * 4) { "${file.name}: expected $expected float32 samples, got ${b.remaining()} bytes" }
    return FloatArray(expected) { b.float }.also { require(it.all(Float::isFinite)) }
}
private fun npShorts(file: File): ShortArray {
    val b = npBuffer(file)
    // The owned 17^3 probe pads its row from 4913 to 4920 pixels.
    require(b.remaining() == 4920 * 3 * 2) { "${file.name}: unexpected padded carrier size" }
    return ShortArray(4913 * 3) { b.short }.also { require(it.all { code -> code >= 0 }) }
}
private fun npMatrix(b: ByteBuffer, offset: Int) = FloatArray(9) { b.getFloat(offset + 4 * it) }
private fun npGrid(file: File): FloatArray {
    val a = npFloats(file, 17 * 91 * 12 * 4)
    return FloatArray(RawHsvGridBaker.GRID_FLOATS).also { out ->
        var at = 0
        for (s in 0..16) for (h in 0..90) for (v in 0..11) {
            val base = ((v * 91 + h) * 17 + s) * 4
            for (c in 0..2) out[at++] = a[base + c]
        }
    }
}
private fun npMax(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size)
    return a.indices.maxOf { abs(a[it] - b[it]) }
}
private fun npGridMetrics(a: FloatArray, b: FloatArray, bw: Boolean): Map<String, Any?> {
    require(a.size == b.size && a.all(Float::isFinite) && b.all(Float::isFinite))
    var sv = 0f; var hue = 0f
    val invalid = ArrayList<Int>()
    var commonValid = 0
    for (i in a.indices step 3) {
        val ai = a[i] == -1000f; val bi = b[i] == -1000f
        if (ai != bi) invalid += i / 3
        if (ai || bi) continue
        commonValid++
        sv = max(sv, max(abs(a[i + 1] - b[i + 1]), abs(a[i + 2] - b[i + 2])))
        val difference = abs(a[i] - b[i]) % 360f
        hue = max(hue, minOf(difference, 360f - difference))
    }
    return linkedMapOf("common_valid_nodes" to commonValid, "sv_max" to sv,
        "circular_hue_max" to if (bw) null else hue,
        "bw_hue_excluded" to bw, "invalid_mask_mismatch_nodes" to invalid,
        "invalid_mask_exact" to invalid.isEmpty())
}
private fun npQ15Metrics(actual: ShortArray, expected: ShortArray): Map<String, Any> {
    require(actual.size == expected.size)
    var peak = 0; var at = 0; var squares = 0.0
    var differing = 0
    for (i in actual.indices) {
        val d = actual[i].toInt() - expected[i].toInt()
        if (abs(d) > peak) { peak = abs(d); at = i }
        if (d != 0) differing++
        squares += d.toDouble() * d
    }
    return linkedMapOf("max_q15" to peak, "rmse_255" to sqrt(squares / actual.size) * 255.0 / 32767.0,
        "components" to actual.size, "different_components" to differing,
        "max_pixel" to at / 3, "max_channel" to at % 3,
        "max_actual" to actual[at].toInt(), "max_expected" to expected[at].toInt(),
        "within_32_q15" to (peak <= NEGATIVE_OUTPUT_Q15_TOLERANCE))
}
private fun npJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"" + buildString { for (c in value) when (c) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
    } } + "\""
    is Boolean -> value.toString()
    is Number -> { require(value.toDouble().isFinite()); value.toString() }
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { npJson(it.key.toString()) + ":" + npJson(it.value) }
    is Iterable<*> -> value.joinToString(",", "[", "]", transform = ::npJson)
    else -> error("Unsupported JSON type: ${value.javaClass}")
}
private data class NpIndex(val token: String, val id: String, val photo: String, val prefix: String, val apply: String, val applyStatus: String)

/** Exhaustive offline diagnostic. Strict failures remain failures; captured inputs never become product assets. */
fun main(args: Array<String>) {
    require(args.size in 5..6) { "Expected local-data, native directory, audited TSV index, report path, static RAW override directory, optional expected Apply count" }
    val local = File(args[0]); val native = File(args[1]); val indexFile = File(args[2]); val output = File(args[3])
    val staticRawDirectory = File(args[4])
    val expectedApplyCount = if (args.size == 6) args[5].toInt().also { require(it == 136) } else 94
    val modes = listOf("captured_inverse_baker", "rebuilt_post_chain", "catalog_curves_captured_context", "static_raw_curves_captured_context", "static_provider_parameters") +
        if (expectedApplyCount == 136) listOf("static_provider_with_color_stage") else emptyList()
    val providers = File(local, "raw-negative-providers/catalog.cxornp").inputStream().use(RawNegativeProviderCatalog::read)
    val lines = indexFile.readLines()
    require(lines.first() == "token\tfilm_id\tphoto\tprovider_prefix\tapply_prefix\tapply_status")
    val index = lines.drop(1).filter(String::isNotBlank).map { line ->
        val fields = line.split('\t'); require(fields.size == 6)
        NpIndex(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5])
    }
    require(index.size == 136 && index.map { it.token }.toSet().size == 68)
    require(index.map { it.token to it.photo }.toSet().size == 136)
    require(index.groupBy { it.token }.all { (_, cases) -> cases.map { it.photo }.toSet() == setOf("p1040758", "p1176257") })
    require(index.count { it.applyStatus == "validated" } == expectedApplyCount)
    val catalog = File(local, "raw-films/catalog.json").readText()
    val rows = Regex("""\{\s*"id"\s*:\s*"([^"]+)"[^}]*}""").findAll(catalog).associate { it.groupValues[1] to it.value }
    fun catalogCurves(id: String): Array<FloatArray> {
        val row = rows.getValue(id)
        val names = Regex(""""curves"\s*:\s*\[([^]]+)]""").find(row)?.groupValues?.get(1)?.let { list ->
            Regex(""""([^"]+)"""").findAll(list).map { it.groupValues[1] }.toList()
        } ?: listOf(Regex(""""curve"\s*:\s*"([^"]+)"""").find(row)!!.groupValues[1])
        return names.map { name ->
            val b = npBuffer(File(local, "raw-films/curves/$name")); require(b.remaining() == 65536)
            FloatArray(32769) { if (it < 32768) b.short.toFloat() else 0f }.also { it[32768] = it[32767] }
        }.toTypedArray()
    }
    val results = ArrayList<Map<String, Any?>>()
    for (entry in index) {
        val result = linkedMapOf<String, Any?>("token" to entry.token, "film_id" to entry.id, "photo" to entry.photo,
            "provider_prefix" to entry.prefix, "native_apply_status" to entry.applyStatus)
        try {
            fun file(suffix: String) = File(native, "${entry.prefix}.$suffix")
            val rendering = npBuffer(file("prepared.rendering-data.bin"))
            val iface = npBuffer(file("prepared.actual-interface.bin"))
            val bw = rendering.get(0).toInt() != 0; val runtimeBw = iface.get(0x1659).toInt() != 0
            val count = when (rendering.getInt(0x78)) { 0 -> 1; 1 -> 3; else -> error("Unknown curve kind") }
            val ev = npFloats(file("argument.ev1714.f32.bin"), 1)[0]
            require(ev == iface.getFloat(0x1714))
            val renderingEv = rendering.getFloat(0x28)
            val pre = npMatrix(rendering, 4); val post = npMatrix(rendering, 0x80)
            val extra = npFloats(file("argument.matrix15c8.f32.bin"), 9)
            val temperatures = Regex(""""WhiteBalanceRawTemperature"\s*:\s*([0-9.eE+-]+)""")
                .findAll(file("json").readText()).map { it.groupValues[1].toFloat() }.toSet()
            require(temperatures.size == 1)
            val temperature = temperatures.single()
            val provider = requireNotNull(providers.film(entry.id))
            val productBake = provider.bake(temperature, ev, extra) { name ->
                File(local, "raw-films/curves/$name").inputStream().use(RawNegativeProviderCatalog::readQ15Curve)
            }
            require(provider.typeBw == bw && provider.runtimeBw == runtimeBw)
            require(productBake.parameters.renderingEv.toRawBits() == renderingEv.toRawBits())
            require(productBake.parameters.postMatrix().contentEquals(post))
            val providerPreError = npMax(productBake.parameters.preMatrix(), pre)
            require(productBake.parameters.preMatrix().indices.all { productBake.parameters.preMatrix()[it].toRawBits() == pre[it].toRawBits() })
            result["static_provider_pre_max"] = providerPreError
            result["static_provider_temperature"] = temperature
            val films = Array(count) { c -> npFloats(file("prepared.rendering-curve-${48 + 24 * c}.f32le.bin"), 32769) }
            val forward = Array(count) { c -> npFloats(file("prepared.raw-one-or-three-6144-curve-$c.f32le.bin"), 32769) }
            val inverse = Array(count) { c -> npFloats(file("prepared.raw-one-or-three-6232-curve-$c.f32le.bin"), 32769) }
            val nodes = npGrid(file("input.grid.f32.bin"))
            val nodeFormula = RawHsvGridBaker.hsvNodes()
            result["node_formula_max"] = npMax(nodes, nodeFormula)
            val header = npBuffer(file("input.header.bin"))
            require(header.remaining() == 64 && header.getLong(32) == 17L && header.getLong(40) == 91L && header.getLong(48) == 12L && header.getLong(56) == 4L)
            val nativePre = npGrid(file("pre-repair.grid.f32.bin"))
            val nativeRepaired = npGrid(file("repaired.grid.f32.bin"))
            val preparedSame = file("repaired.grid.f32.bin").readBytes().contentEquals(file("prepared.interface-storage-grid.bin").readBytes())
            result["prepared_native_rebake_exact"] = preparedSame
            require(preparedSame)
            result["type_bw"] = bw; result["runtime_bw"] = runtimeBw; result["native_curve_count"] = count
            result["ev"] = ev; result["rendering_ev"] = renderingEv
            val builtForward = if (count == 1) arrayOf(NativePostCurves.buildForwardPostCurve(films[0], renderingEv))
                else NativePostCurves.buildChannelForwardPostCurves(films, renderingEv, post, extra)
            val builtInverse = Array(count) { NativePostCurves.inverseComposedPostCurve(builtForward[it]) }
            result["forward_curve_channel_max"] = forward.indices.map { npMax(builtForward[it], forward[it]) }
            result["inverse_curve_channel_max"] = inverse.indices.map { npMax(builtInverse[it], inverse[it]) }
            val productFilms = catalogCurves(entry.id)
            result["catalog_curve_count"] = productFilms.size
            result["catalog_curve_count_matches_native"] = productFilms.size == count
            result["catalog_curve_channel_max"] = films.indices.map { c -> npMax(productFilms[if (productFilms.size == 1) 0 else c], films[c]) }
            val curveExact = productFilms.size == count && films.indices.all { npMax(productFilms[it], films[it]) == 0f }
            result["catalog_curve_exact"] = curveExact
            val staticFilms = if (entry.id == "ColorModeCineMoodyDarkGreen") arrayOf("R", "G", "B").map { channel ->
                val bytes = npBuffer(File(staticRawDirectory, "CineMoodyDarkGreen/CineMoodyDarkGreen.raw.$channel.i16le.bin"))
                require(bytes.remaining() == 65536)
                FloatArray(32769) { if (it < 32768) bytes.short.toFloat() else 0f }.also { it[32768] = it[32767] }
            }.toTypedArray() else productFilms
            val staticExact = staticFilms.size == count && films.indices.all { npMax(staticFilms[it], films[it]) == 0f }
            result["static_raw_curve_exact"] = staticExact
            var source: ShortArray? = null; var sensor: ShortArray? = null; var expected: ShortArray? = null
            var colorStage: RawScaleFilterStage? = null
            if (entry.applyStatus == "validated") {
                require(entry.apply != "-")
                fun applyFile(suffix: String) = File(native, "${entry.apply}.$suffix")
                // These parameter bytes must match despite the probes running in separate processes.
                val applyRendering = npBuffer(applyFile("prepared.rendering-data.bin"))
                val applyIface = npBuffer(applyFile("prepared.actual-interface.bin"))
                val hasColorStage = (applyIface.get(0x18d4).toInt() and 1) != 0
                require(hasColorStage == provider.requiresAdditionalColorStage)
                if (hasColorStage) {
                    require((applyIface.get(0x18dc).toInt() and 1) != 0) { "Native scales/filter intent was not initialized" }
                    val intent = applyIface.getFloat(0x18d8)
                    val staticMatrix = provider.scaleFilterMatrix()
                    require(npMax(staticMatrix, npMatrix(applyIface, 0x18b0)) == 0f) { "Static scales matrix differs from original Prepare" }
                    colorStage = provider.colorStage(intent)
                    result["static_scales_matrix_max"] = 0f
                    result["captured_color_stage_intent"] = intent
                }
                require(npMax(pre, npMatrix(applyRendering, 4)) == 0f && npMax(post, npMatrix(applyRendering, 0x80)) == 0f)
                require(renderingEv == applyRendering.getFloat(0x28) && ev == applyIface.getFloat(0x1714))
                require(runtimeBw == (applyIface.get(0x1659).toInt() != 0))
                require(npMax(npMatrix(iface, 0x17b0), npMatrix(applyIface, 0x17b0)) == 0f)
                require(iface.getFloat(0x1710) == applyIface.getFloat(0x1710))
                require(npMax(extra, npMatrix(applyIface, 0x15c8)) == 0f)
                require(npMax(nativeRepaired, npGrid(applyFile("prepared.interface-storage-grid.bin"))) == 0f)
                for (channel in 0 until count) {
                    require(npMax(forward[channel], npFloats(applyFile("prepared.raw-one-or-three-6144-curve-$channel.f32le.bin"), 32769)) == 0f)
                    require(npMax(inverse[channel], npFloats(applyFile("prepared.raw-one-or-three-6232-curve-$channel.f32le.bin"), 32769)) == 0f)
                }
                source = npShorts(applyFile("grid-input.q15.bin")); sensor = npShorts(applyFile("grid-sensor-output.q15.bin")); expected = npShorts(applyFile("grid-full-output.q15.bin"))
                val working = ShortArray(source.size)
                RawSensorToWorking(npMatrix(iface, 0x17b0), ev, iface.getFloat(0x1710)).renderQ15(source, working)
                result["sensor_transform"] = npQ15Metrics(working, sensor)
                val out = ShortArray(sensor.size)
                RawHsvFilmRenderer(nativeRepaired, forward[0], true, forward.takeIf { it.size == 3 }, runtimeBw).renderQ15(sensor, out)
                result["native_grid_runtime"] = npQ15Metrics(out, expected)
                RawHsvFilmRenderer(nativeRepaired, forward[0], true, forward.takeIf { it.size == 3 }, runtimeBw,
                    colorStage = colorStage).renderQ15(sensor, out)
                result["native_grid_and_color_stage_runtime"] = npQ15Metrics(out, expected)
            }
            val modeResults = linkedMapOf<String, Any?>()
            for (mode in modes) {
                val modeResult = linkedMapOf<String, Any?>()
                try {
                    val modeFilms = when (mode) {
                        "static_provider_parameters", "static_provider_with_color_stage" -> productBake.filmCurves
                        "catalog_curves_captured_context" -> productFilms
                        "static_raw_curves_captured_context" -> staticFilms
                        else -> films
                    }
                    val modeForward = when (mode) {
                        "static_provider_parameters", "static_provider_with_color_stage" -> productBake.forwardCurves
                        "captured_inverse_baker" -> forward
                        "rebuilt_post_chain" -> builtForward
                        else -> if (modeFilms.size == 1) arrayOf(NativePostCurves.buildForwardPostCurve(modeFilms[0], renderingEv))
                            else NativePostCurves.buildChannelForwardPostCurves(modeFilms, renderingEv, post, extra)
                    }
                    val staticProvider = mode.startsWith("static_provider_")
                    val modeInverse = if (staticProvider) productBake.inverseCurves
                        else if (mode == "captured_inverse_baker") inverse
                        else Array(modeForward.size) { NativePostCurves.inverseComposedPostCurve(modeForward[it]) }
                    val preGrid = if (staticProvider) productBake.preRepairGrid else
                        RawHsvGridBaker.bake(RawHsvBakeParameters(ev, renderingEv, pre, post, extra, modeFilms, modeInverse, bw), nodes).grid
                    val repaired = if (staticProvider) productBake.repairedGrid else
                        RawHsvGridBaker.repair(preGrid, skipSaturationClamp = bw)
                    val preMetrics = npGridMetrics(preGrid, nativePre, bw)
                    val repairMetrics = npGridMetrics(repaired, nativeRepaired, bw)
                    modeResult["pre_grid"] = preMetrics; modeResult["repaired_grid"] = repairMetrics
                    val prePass = preMetrics["invalid_mask_exact"] == true && (preMetrics["sv_max"] as Float) < NEGATIVE_GRID_SV_PRE_TOLERANCE
                    val repairPass = (repairMetrics["sv_max"] as Float) < NEGATIVE_GRID_SV_REPAIR_TOLERANCE
                    modeResult["grid_gate_pass"] = prePass && repairPass
                    if (sensor != null && expected != null && source != null) {
                        val renderer = RawHsvFilmRenderer(repaired, modeForward[0], true, modeForward.takeIf { it.size == 3 }, runtimeBw,
                            colorStage = colorStage.takeIf { mode == "static_provider_with_color_stage" })
                        val out = ShortArray(sensor.size); renderer.renderQ15(sensor, out)
                        modeResult["film_output"] = npQ15Metrics(out, expected)
                        val working = ShortArray(source.size)
                        RawSensorToWorking(npMatrix(iface, 0x17b0), ev, iface.getFloat(0x1710)).renderQ15(source, working)
                        renderer.renderQ15(working, working)
                        modeResult["sensor_and_film_output"] = npQ15Metrics(working, expected)
                    }
                    val outputPass = (modeResult["sensor_and_film_output"] as? Map<*, *>)?.get("within_32_q15") != false
                    val sourcePass = when (mode) {
                        "catalog_curves_captured_context" -> curveExact
                        "static_raw_curves_captured_context" -> staticExact
                        "static_provider_parameters", "static_provider_with_color_stage" -> modeFilms.size == count && films.indices.all { npMax(modeFilms[it], films[it]) == 0f }
                        else -> true
                    }
                    modeResult["strict_gate_pass"] = prePass && repairPass && outputPass && sourcePass
                } catch (t: Exception) { modeResult["error"] = "${t.javaClass.simpleName}: ${t.message}"; modeResult["strict_gate_pass"] = false }
                modeResults[mode] = modeResult
            }
            result["modes"] = modeResults
            result["status"] = "measured"
        } catch (t: Exception) { result["status"] = "error"; result["error"] = "${t.javaClass.simpleName}: ${t.message}" }
        results += result
    }
    val summary = linkedMapOf<String, Any?>("contexts" to results.size, "context_errors" to results.count { it["status"] == "error" },
        "apply_contexts" to results.count { it.containsKey("native_grid_runtime") },
        "catalog_curve_exact_contexts" to results.count { it["catalog_curve_exact"] == true },
        "static_raw_curve_exact_contexts" to results.count { it["static_raw_curve_exact"] == true })
    for (mode in modes) {
        summary["${mode}_strict_pass_contexts"] = results.count { ((it["modes"] as? Map<*, *>)?.get(mode) as? Map<*, *>)?.get("strict_gate_pass") == true }
    }
    val requiredMode = if (expectedApplyCount == 136) "static_provider_with_color_stage" else "static_provider_parameters"
    val completePass = results.size == 136 && summary["context_errors"] == 0 && summary["apply_contexts"] == expectedApplyCount &&
        summary["${requiredMode}_strict_pass_contexts"] == 136 && results.all { r ->
            r["node_formula_max"] == 0f && r["prepared_native_rebake_exact"] == true &&
                (r["sensor_transform"] as? Map<*, *>)?.get("within_32_q15") != false &&
                (r["native_grid_and_color_stage_runtime"] as? Map<*, *>)?.get("within_32_q15") != false
        }
    fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    val report = linkedMapOf<String, Any?>("schema" to "cxo-negative-provider-kotlin-batch-v3", "android_execution" to false,
        "scope" to "68 identities x 2 S1RII WB anchors; static_provider_parameters rebuilds provider target-fidelity/pre/post/rendering-EV from static assets; sensor EV/extra matrix remain captured caller inputs; Q15 outputs exclude ICC/JPEG",
        "index" to indexFile.absolutePath,
        "index_sha256" to sha256(indexFile), "catalog_sha256" to sha256(File(local, "raw-films/catalog.json")),
        "static_provider_catalog_sha256" to sha256(File(local, "raw-negative-providers/catalog.cxornp")),
        "static_raw_override_manifest_sha256" to sha256(File(staticRawDirectory, "CineMoodyDarkGreen/manifest.json")),
        "thresholds" to mapOf("pre_valid_sv_max_exclusive" to NEGATIVE_GRID_SV_PRE_TOLERANCE,
            "repaired_valid_sv_max_exclusive" to NEGATIVE_GRID_SV_REPAIR_TOLERANCE, "invalid_mask_mismatches" to 0, "output_max_q15_inclusive" to NEGATIVE_OUTPUT_Q15_TOLERANCE),
        "hue_policy" to "Circular hue reported for color, excluded for BW; hue remains diagnostic because near-neutral outputs are ill-conditioned; no full grid equivalence claimed",
        "summary" to summary, "strict_all_contexts_pass" to completePass,
        "coverage_limits" to listOf("${136 - expectedApplyCount} Prepare-only cases have no full Apply oracle", "Static provider mode still receives sensor EV/extra matrix from captured engine context", "With-color-stage mode rebuilds the static scales matrix but receives captured scene intent; its independent estimation is not verified here", "Catalog source mismatches remain explicit", "No Android device, photo, ICC or JPEG verification in this executable"),
        "results" to results)
    output.parentFile.mkdirs(); output.writeText(npJson(report) + "\n")
    println("RAW negative provider exhaustive diagnostic: ${npJson(summary)}; strict_all_contexts_pass=$completePass; report=${output.absolutePath}")
    check(completePass) { "Unresolved native differences remain; report saved for all contexts. Do not treat this diagnostic as product parity." }
}
