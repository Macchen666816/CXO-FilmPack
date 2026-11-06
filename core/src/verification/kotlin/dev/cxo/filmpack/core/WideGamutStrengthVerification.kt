package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Independent predictions: source codes + static parameters only, no native images. */
fun main(args: Array<String>) {
    require(args.size in 2..3) { "Expected local-data root, prediction output and optional provider-BW directory" }
    val local = File(args[0])
    val output = File(args[1]).also { it.mkdirs() }
    val entry = File(local, "srgb-entry-pix65.cxoentry").inputStream().use { FixedSrgbEntryLutIO.read(it) }
    val bases = File(local, "film-rgb-base/catalog.cxowbs").inputStream().use { FilmRgbBaseScaleCatalog.read(it) }
    val source = DataInputStream(File(local, "shared-catalog/catalog.golden").inputStream()).use { input ->
        val magic = ByteArray(8).also { input.readFully(it) }
        check(magic.contentEquals("CXOCAG01".toByteArray()))
        Integer.reverseBytes(input.readInt())
        check(Integer.reverseBytes(input.readInt()) >= 4096)
        ShortArray(4096 * 3) { java.lang.Short.reverseBytes(input.readShort()) }
    }
    val portraDir = File(local, "shared-catalog")
    val portraCatalog = File(portraDir, "catalog.cxocat").inputStream().use { SharedFilmCatalog.read(it) }
    val portra = portraCatalog.load("ColorModeArgentiqueKodakPortra400") { File(portraDir, it).inputStream() }
    fun loadHsv(group: String, name: String): HsvFilmLut {
        val directory = File(local, group)
        val catalog = File(directory, "catalog.cxocat").inputStream().use { SharedFilmCatalog.read(it) }
        return catalog.load("ColorModeArgentique$name") { File(directory, it).inputStream() }
    }
    val fixedHsv = mutableMapOf("KodakPortra400" to portra,
        "FujiSuperia200E6" to loadHsv("threecurve-catalog", "FujiSuperia200E6"),
        "KodakTriX400" to loadHsv("bw-catalog", "KodakTriX400"))
    val names = mutableListOf("KodakEIR", "KodakPortra400", "Astia", "FujiSuperia200E6", "KodakTriX400")
    val providerNames = listOf("KodakHIEFiltre", "RolleiIR400")
    val providerDirectory = if (args.size == 3) File(args[2]) else File(local, "dynamic-provider-bw-catalog")
    val providerCatalog = DynamicProviderBwFilmCatalog.create()
    for (name in providerNames) {
        fixedHsv[name] = providerCatalog.load("ColorModeArgentique$name") { File(providerDirectory, it).inputStream() }.film
        check(fixedHsv.getValue(name).runtimeBw && fixedHsv.getValue(name).repairBwType && fixedHsv.getValue(name).jpegToneMode == 1)
        names += name
    }
    val rgbDir = File(local, "rgb-catalog")
    val rgbCatalog = File(rgbDir, "catalog.cxocat").inputStream().use { RgbFilmCatalog.read(it) }
    val eir = rgbCatalog.load("ColorModeArgentiqueKodakEIR") { File(rgbDir, it).inputStream() }
    val directDir = File(local, "dynamic-direct-catalog")
    val astia = DynamicDirectFilmCatalog.create().load("ColorModeArgentiqueAstia") { File(directDir, it).inputStream() }
    var comparisons = 0
    for (name in names) {
        val base = if (name == "Astia" || name in providerNames) null
            else requireNotNull(bases.find("ColorModeArgentique$name"))
        for (intensity in listOf(0, 25, 50, 100, 200)) {
            val strength = minOf(intensity.toFloat() / 100f, 1f)
            // The native WB base interpolation continues above 100 independently.
            val balanced = ShortArray(source.size)
            val wb = if (base == null) NativeWhiteBalanceRgb.fromGains(1f,1f,1f)
                else NativeWhiteBalanceRgb.create(base, intensity = intensity)
            wb.applySrgb16Carrier(source, balanced)
            val internal = FloatArray(source.size)
            entry.renderRgb16(balanced, internal)
            val rendered = FloatArray(source.size)
            fun render(src: FloatArray, dst: FloatArray) {
                when (name) {
                    "KodakEIR" -> eir.renderRgb(src, dst, strength)
                    "Astia" -> astia.film.renderRgb(src, dst, strength)
                    else -> fixedHsv.getValue(name).renderRgb(src, dst, strength)
                }
            }
            render(internal, rendered)
            val inPlace = internal.copyOf()
            render(inPlace, inPlace)
            check(rendered.all { it.isFinite() && it in 0f..1f })
            check(rendered.indices.all { rendered[it].toRawBits() == inPlace[it].toRawBits() })
            if (base != null) {
                val piped = FloatArray(source.size)
                if (name == "KodakEIR") {
                    FixedSrgbRgbFilmPipeline.withWideGamutIntensity(entry, eir, base, intensity)
                        .renderRgb16(source, piped)
                } else {
                    FixedSrgbFilmPipeline.withWideGamutIntensity(entry, fixedHsv.getValue(name), base, intensity)
                        .renderRgb16(source, piped)
                }
                check(piped.indices.all { piped[it].toRawBits() == rendered[it].toRawBits() })
            }
            val sample = FloatArray(3)
            for (index in intArrayOf(0, 127, 4095)) {
                val i = index * 3
                when (name) {
                    "KodakEIR" -> eir.sample(internal[i], internal[i+1], internal[i+2], sample, strength = strength)
                    "Astia" -> astia.film.sample(internal[i], internal[i+1], internal[i+2], sample, strength = strength)
                    else -> fixedHsv.getValue(name).sample(internal[i], internal[i+1], internal[i+2], sample, strength = strength)
                }
                repeat(3) { check(sample[it].toRawBits() == rendered[i+it].toRawBits()) }
            }
            DataOutputStream(File(output, "$name-$intensity.f32").outputStream()).use { out ->
                rendered.forEach { out.writeInt(Integer.reverseBytes(it.toRawBits())) }
            }
            comparisons++
        }
    }
    for (invalid in floatArrayOf(-0.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
        check(runCatching { portra.renderRgb(floatArrayOf(.1f,.2f,.3f), FloatArray(3), invalid) }.isFailure)
        check(runCatching { eir.sample(.1f,.2f,.3f, FloatArray(3), strength = invalid) }.isFailure)
    }
    println("{\"widegamut_strength_cases\":$comparisons,\"source_pixels_per_case\":4096," +
        "\"sample_bulk_alias\":\"passed\",\"native_images_read\":false}")
}
