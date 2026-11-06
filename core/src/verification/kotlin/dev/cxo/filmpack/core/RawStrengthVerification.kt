package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

private data class StrengthFilm(
    val token: String, val id: String, val hundred: String, val rgb: Boolean,
    val strengths: List<Int> = listOf(0, 50, 100, 150, 200), val camera: String = "s9", val version: String? = null,
)
private val strengthFilms = listOf(
    StrengthFilm("KodakEliteColor400", "ColorModeArgentiqueKodakEliteColor400", "s9-minimum-kodakelitecolor400-v1", false),
    StrengthFilm("Ekta100VS", "ColorModeArgentiqueEkta100VS", "specialized-s9-ekta100vs-v1", false),
    StrengthFilm("KodakHIE", "ColorModeArgentiqueKodakHIE", "rgb-specialized-s9-kodakhie-v1", true),
    StrengthFilm("CineTealAndPink", "ColorModeCineTealAndPink", "rgb-specialized-s9-cinetealandpink-v1", true),
    StrengthFilm("KodakTriX400", "ColorModeArgentiqueKodakTriX400", "s9-minimum-kodaktrix400-v1", false,
        listOf(0, 50, 100), version = "v3"),
    StrengthFilm("FujiSuperia200E6", "ColorModeArgentiqueFujiSuperia200E6", "s9-minimum-fujisuperia200e6-v1", false,
        listOf(0, 50, 100), version = "v3"),
    StrengthFilm("KodakEliteColor400", "ColorModeArgentiqueKodakEliteColor400", "strength-s1rii-kodakelitecolor400-50-v3", false,
        listOf(50), camera = "s1rii", version = "v3"),
)
private fun strengthJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    is Number -> value.toString().also { require(value.toDouble().isFinite()) }
    is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { strengthJson(it.key.toString()) + ":" + strengthJson(it.value) }
    is Iterable<*> -> value.joinToString(",", "[", "]", transform = ::strengthJson)
    else -> error("Unsupported JSON value")
}
private fun strengthMetrics(actual: ShortArray, expected: ShortArray): Map<String, Any> {
    require(actual.size == expected.size)
    var peak = 0; var squares = 0.0; var different = 0
    for (i in actual.indices) {
        val difference = actual[i].toInt() - expected[i].toInt()
        peak = maxOf(peak, abs(difference)); squares += difference.toDouble() * difference
        if (difference != 0) different++
    }
    return linkedMapOf("max_q15" to peak, "rmse_q15" to sqrt(squares / actual.size),
        "different_components" to different, "components" to actual.size)
}

/** Original-engine synthetic RAW Apply oracles. Captures remain verification inputs;
 * product matrices, curves and grids are rebuilt from the shipped static catalog.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "Expected local-data, native directory, report" }
    val local = File(args[0]); val native = File(args[1]); val output = File(args[2])
    val hashes = linkedMapOf<String, String>()
    fun bytes(file: File) = file.readBytes().also { data ->
        hashes[file.absolutePath] = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun buffer(file: File) = ByteBuffer.wrap(bytes(file)).order(ByteOrder.LITTLE_ENDIAN)
    fun floats(file: File, count: Int): FloatArray {
        val b = buffer(file); require(b.remaining() == count * 4)
        return FloatArray(count) { b.float }.also { require(it.all(Float::isFinite)) }
    }
    fun carrier(file: File): ShortArray {
        val b = buffer(file); require(b.remaining() == 4920 * 3 * 2)
        return ShortArray(4913 * 3) { b.short }.also { require(it.all { code -> code >= 0 }) }
    }
    fun matrix(b: ByteBuffer, offset: Int) = FloatArray(9) { b.getFloat(offset + 4 * it) }
    fun grid(file: File): FloatArray {
        val packed = floats(file, 17 * 91 * 12 * 4)
        return FloatArray(RawHsvGridBaker.GRID_FLOATS).also { result ->
            var at = 0
            for (s in 0..16) for (h in 0..90) for (v in 0..11) {
                val base = ((v * 91 + h) * 17 + s) * 4
                for (c in 0..2) result[at++] = packed[base + c]
            }
        }
    }
    fun exact(actual: FloatArray, expected: FloatArray, label: String) {
        check(actual.size == expected.size && actual.indices.all { actual[it].toRawBits() == expected[it].toRawBits() }) {
            "$label differs; max ${actual.indices.maxOf { abs(actual[it] - expected[it]) }}"
        }
    }
    fun rejected(block: () -> Unit) { check(runCatching(block).isFailure) { "Invalid intensity accepted" } }
    val catalogs = listOf("raw-negative-providers", "raw-static-providers", "raw-special-providers").map { name ->
        bytes(File(local, "$name/catalog.cxornp")).inputStream().use(RawNegativeProviderCatalog::read)
    }
    val results = arrayListOf<Map<String, Any?>>()
    for (film in strengthFilms) {
        val profileFile = File(local, "raw-profile/${film.camera}-fidelity.json")
        bytes(profileFile)
        val (profile, bias) = s9Profile(profileFile)
        val provider = requireNotNull(catalogs.firstNotNullOfOrNull { it.film(film.id) }) { "Missing ${film.id}" }
        val hundredPrefix = "small-grid-${film.hundred}"
        fun baseFile(suffix: String) = File(native, "$hundredPrefix.$suffix")
        val baseIface = buffer(baseFile("prepared.actual-interface.bin"))
        val temperatureText = bytes(baseFile("json")).toString(Charsets.UTF_8)
        val temperature = Regex("\"WhiteBalanceRawTemperature\"\\s*:\\s*([-+0-9.eE]+)")
            .find(temperatureText)!!.groupValues[1].toFloat()
        check(bias.toRawBits() == baseIface.getFloat(0x1714).toRawBits())
        exact(profile.sensorToWorking(temperature), matrix(baseIface, 0x17b0), "Static source matrix")
        val extra = profile.engineExtraMatrix()
        exact(extra, matrix(baseIface, 0x15c8), "Static extra matrix")
        val readCurve: (String) -> FloatArray = { name ->
            bytes(File(local, "raw-films/curves/$name")).inputStream().use(RawNegativeProviderCatalog::readQ15Curve)
        }
        val bake = if (!film.rgb) provider.bake(temperature, bias, extra, readCurve) else null
        val rgbProduct = if (film.rgb) RawRgbFilmRenderer(
            RawRgbGridBaker.bake(provider.rgbContext(temperature, bias, extra, readCurve).bakeParameters()), provider.runtimeBw,
        ) else null
        val expectedHundred = carrier(baseFile("grid-full-output.q15.bin"))
        val sensorHundred = carrier(baseFile("grid-sensor-output.q15.bin"))
        val nativeGridHundred = bytes(baseFile("prepared.interface-storage-grid.bin"))
        for (intensity in film.strengths) {
            val version = film.version ?: if (intensity == 50 || film.token == "KodakEliteColor400") "v1" else "v2"
            val prefix = if (intensity == 100) hundredPrefix else
                "small-grid-strength-${film.camera}-${film.token.lowercase()}-$intensity-$version"
            fun file(suffix: String) = File(native, "$prefix.$suffix")
            val row = linkedMapOf<String, Any?>("camera" to film.camera, "film" to film.token, "intensity" to intensity, "rgb_grid" to film.rgb,
                "prefix" to prefix)
            try {
                val iface = buffer(file("prepared.actual-interface.bin"))
                val sensor = carrier(file("grid-sensor-output.q15.bin"))
                val expected = carrier(file("grid-full-output.q15.bin"))
                check(sensor.contentEquals(sensorHundred)) { "Sensor input changed with intensity" }
                check(bytes(file("prepared.interface-storage-grid.bin")).contentEquals(nativeGridHundred)) {
                    "Grid changed with intensity; strength-independent cache assumption invalid"
                }
                if (intensity >= 100) check(expected.contentEquals(expectedHundred)) { "Native intensity cap changed" }
                val hasStage = (iface.get(0x18d4).toInt() and 1) != 0
                check(hasStage == (provider.requiresAdditionalColorStage && intensity != 0))
                val intent = if (hasStage) iface.getFloat(0x18d8) else 0f
                val nativeStage = if (hasStage) RawScaleFilterStage(matrix(iface, 0x18b0), intent) else null
                if (hasStage) exact(provider.scaleFilterMatrix(intensity), matrix(iface, 0x18b0), "Intensity scales matrix")
                val productStage = provider.colorStage(intent, intensity)
                check((productStage != null) == hasStage)
                val outputCodes = ShortArray(sensor.size)
                val render: (ShortArray, ShortArray, Int) -> Unit
                val defaultRender: (ShortArray, ShortArray) -> Unit
                val productRender: (ShortArray, ShortArray, Int) -> Unit
                if (film.rgb) {
                    check(!hasStage)
                    val renderer = RawRgbFilmRenderer(floats(file("prepared.interface-storage-grid.bin"), RawRgbGridBaker.GRID_FLOATS), provider.runtimeBw)
                    render = { input, out, strength -> renderer.renderQ15(input, out, strength) }
                    defaultRender = { input, out -> renderer.renderQ15(input, out) }
                    productRender = { input, out, strength -> requireNotNull(rgbProduct).renderQ15(input, out, strength) }
                } else {
                    val header = buffer(file("prepared.raw-one-or-three-6144.header.bin"))
                    val count = when (header.getInt(72)) { 0 -> 1; 1 -> 3; else -> error("Unknown postcurve kind") }
                    val curves = Array(count) { floats(file("prepared.raw-one-or-three-6144-curve-$it.f32le.bin"), 32769) }
                    val renderer = RawHsvFilmRenderer(grid(file("prepared.interface-storage-grid.bin")), curves[0], true,
                        curves.takeIf { count == 3 }, provider.runtimeBw, nativeStage)
                    val productRenderer = requireNotNull(bake).renderer(productStage)
                    render = { input, out, strength -> renderer.renderQ15(input, out, strength) }
                    defaultRender = { input, out -> renderer.renderQ15(input, out) }
                    productRender = { input, out, strength -> productRenderer.renderQ15(input, out, strength) }
                }
                render(sensor, outputCodes, intensity)
                val metric = strengthMetrics(outputCodes, expected)
                row["captured_parameters_renderer"] = metric
                check((metric["max_q15"] as Int) <= 1) { "Native-parameter renderer exceeds 1 Q15: $metric" }
                val inPlace = sensor.copyOf(); render(inPlace, inPlace, intensity)
                check(inPlace.contentEquals(outputCodes)) { "In-place differs" }
                row["in_place_exact"] = true
                if (intensity == 100) {
                    val defaults = ShortArray(sensor.size); defaultRender(sensor, defaults)
                    check(defaults.contentEquals(outputCodes)) { "Default100 differs from explicit100" }
                    row["default_100_exact"] = true
                }
                rejected { render(sensor, outputCodes, -1) }; rejected { render(sensor, outputCodes, 201) }
                row["invalid_intensity_rejected"] = true
                productRender(sensor, outputCodes, intensity)
                val productMetric = strengthMetrics(outputCodes, expected)
                row["static_product_bake"] = productMetric
                check((productMetric["max_q15"] as Int) <= 1) { "Rebuilt product exceeds 1 Q15 gate: $productMetric" }
                row["pass"] = true
            } catch (failure: Throwable) {
                row["pass"] = false
                row["error"] = "${failure.javaClass.simpleName}: ${failure.message}"
            }
            results += row
            println("RAW strength ${film.token} $intensity: ${row["pass"]} ${row["error"] ?: row["captured_parameters_renderer"]}")
        }
        rejected { provider.scaleFilterMatrix(-1) }; rejected { provider.scaleFilterMatrix(201) }
        rejected { provider.colorStage(0f, -1) }; rejected { provider.colorStage(0f, 201) }
    }
    check(results.size == 27)
    output.parentFile.mkdirs()
    output.writeText(strengthJson(linkedMapOf("schema" to "cxo-kotlin-raw-strength-v1",
        "scope" to "S9/S1RII synthetic RAW renderer: native parameter and rebuilt static-provider comparisons; not Android UI or full RAW decode",
        "fitted_parameters" to 0, "native_renderer_max_q15_gate" to 1, "static_product_max_q15_gate" to 1,
        "cases" to results.size, "passed" to results.count { it["pass"] == true },
        "results" to results, "source_sha256" to hashes)) + "\n")
    check(results.all { it["pass"] == true }) { "RAW strength verification failed; see ${output.absolutePath}" }
    println("RAW strength: 27/27 original-engine contexts passed; native renderer <=1 Q15, static product <=1 Q15")
}
