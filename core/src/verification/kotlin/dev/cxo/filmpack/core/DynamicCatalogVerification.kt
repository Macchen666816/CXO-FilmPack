package dev.cxo.filmpack.core

import java.io.ByteArrayInputStream
import java.io.File

/** Offline verification of recovered branch/curve/matrix assets. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Expected dynamic catalog directory" }
    val directory = File(args[0])
    val manifest = File(directory, "catalog.cxocat").readBytes()
    val catalog = DynamicFilmCatalog.read(ByteArrayInputStream(manifest))
    check(catalog.presets.size == 7)
    check(catalog.presets.count { it.hasDirectBranch } == 5)
    check(catalog.find("ColorModeArgentiqueKodakHIEFiltre")?.hasDirectBranch == false)
    check(catalog.find("ColorModeArgentiqueRolleiIR400")?.hasDirectBranch == false)
    var curves = 0
    for (preset in catalog.presets) {
        check(catalog.find(preset.id) === preset)
        val loaded = catalog.load(preset.id) { File(directory, it).inputStream() }
        check(loaded.preset === preset)
        check(loaded.rawCurve.size == 32768 && loaded.composedCurve.size == 32768)
        check(loaded.rawCurve.all { it.toInt() in 0..32767 })
        check(loaded.composedCurve.all { it.toInt() in 0..32767 })
        check(preset.filmB.size == 9 && preset.filmB.all { it.isFinite() })
        val mutatedB = preset.filmB; mutatedB[0] = Float.NaN
        check(preset.filmB[0].isFinite())
        val mutatedRaw = loaded.rawCurve; mutatedRaw[0] = -1
        check(loaded.rawCurve[0].toInt() in 0..32767)
        val mutatedComposed = loaded.composedCurve; mutatedComposed[0] = -1
        check(loaded.composedCurve[0].toInt() in 0..32767)
        curves += loaded.rawCurve.size + loaded.composedCurve.size
    }
    check(curves == 7 * 65536)
    check(catalog.find("unknown") == null)
    val first = catalog.presets.first()
    val raw = File(directory, first.rawCurveFile).readBytes()
    val changed = raw.copyOf(); changed[100] = (changed[100].toInt() xor 1).toByte()
    check(runCatching {
        catalog.load(first.id) { if (it == first.rawCurveFile) ByteArrayInputStream(changed) else File(directory, it).inputStream() }
    }.isFailure)
    check(runCatching { DynamicFilmCatalog.read((manifest + byteArrayOf(0)).inputStream()) }.isFailure)
    println("{\"dynamic_presets\":${catalog.presets.size},\"direct_branches\":${catalog.presets.count { it.hasDirectBranch }}," +
        "\"curve_samples\":32768,\"curve_components\":$curves,\"catalog_hash_and_asset_checks\":\"passed\"," +
        "\"native_execution\":false,\"android_execution\":false}")
}
