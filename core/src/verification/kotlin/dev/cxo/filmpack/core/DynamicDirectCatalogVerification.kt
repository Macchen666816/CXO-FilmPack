package dev.cxo.filmpack.core

import java.io.File
import kotlin.math.abs

/** JVM-only checks for the isolated five-entry direct HSV review catalog. */
fun main(args: Array<String>) {
    require(args.size == 3) { "Expected dynamic-direct directory, entry asset, fixed RGB base catalog" }
    val directory = File(args[0])
    val manifest = File(directory, "manifest.json").readText()
    check(manifest.contains("cxo-dynamic-direct-hsv-review-v1"))
    check(manifest.contains("production_catalog_entries_added"))
    val entry = FixedSrgbEntryLutIO.read(File(args[1]).inputStream())
    val fixed = FilmRgbBaseScaleCatalog.read(File(args[2]).inputStream())
    val catalog = DynamicDirectFilmCatalog.create()
    check(catalog.presets.size == 5)
    check(catalog.presets.all { !it.feature5Enabled && it.jpegToneMode == 0 && !it.runtimeBw && it.curveKind == 0 })
    check(catalog.presets.map { it.renderingType }.toSet() == setOf(1000, 1001, 1002, 1003, 1004))
    check(catalog.find("ColorModeArgentiqueKodakEkta100VS") == null)
    check(fixed.find("ColorModeArgentiqueKodakEkta100VS")?.renderingType == 1008)
    check(fixed.find("ColorModeArgentiqueEkta100VS") == null)

    val probeInput = intArrayOf(0, 1, 32768, 65535, 12345, 54321)
    var loaded = 0
    for (preset in catalog.presets) {
        check(catalog.find(preset.id) === preset)
        val data = catalog.load(preset.id) { File(directory, it).inputStream() }
        check(data.preset === preset)
        check(data.film.jpegToneMode == 0 && !data.film.runtimeBw && !data.film.repairBwType)
        check(data.film.postCurveChannels == 1)
        val gains = preset.providerGains
        check(gains.size == 3 && gains.all { it.isFinite() && it >= 0f })
        val filmProbe = FloatArray(3)
        data.film.sample(0f, 0f, 0f, filmProbe)
        check(filmProbe.all { it.isFinite() && it in 0f..1f })
        val pipeline = data.pipeline(entry)
        val output = FloatArray(3)
        for (r in probeInput) {
            pipeline.sampleSrgb16(r, (r * 13) and 65535, (r * 29) and 65535, output)
            check(output.all { it.isFinite() && it in 0f..1f })
        }
        val source = ShortArray(15) { (it * 4097 - 16384).toShort() }
        val rendered = FloatArray(source.size)
        pipeline.renderRgb16(source, rendered)
        check(rendered.all { it.isFinite() && it in 0f..1f })
        check(abs(gains[0] - preset.providerRed) == 0f)
        loaded++
    }
    check(loaded == 5)
    check(catalog.find("unknown") == null)
    println("{\"dynamic_direct_presets\":$loaded,\"context\":\"feature5-0/tc-0/runtimeBW-0/curveKind-0\",\"provider_and_hash_checks\":\"passed\",\"pipeline_finite_range\":\"passed\",\"fixed_147_untouched\":true,\"native_execution\":false,\"android_execution\":false}")
}
