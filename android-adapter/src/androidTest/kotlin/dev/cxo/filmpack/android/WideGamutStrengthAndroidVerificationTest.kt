package dev.cxo.filmpack.android

import android.test.AndroidTestCase
import dev.cxo.filmpack.core.*
import java.io.DataInputStream

/** Device/JVM parity for independent WB interpolation and internal film strength. */
class WideGamutStrengthAndroidVerificationTest : AndroidTestCase() {
    fun testSevenConditionalFilmsAtFiveIntensitiesMatchJvmBits() {
        val assets = context.assets
        val root = "fixed-film"
        val entry = assets.open("$root/srgb-entry-pix65.cxoentry").use { FixedSrgbEntryLutIO.read(it) }
        val bases = assets.open("$root/film-rgb-base/catalog.cxowbs").use { FilmRgbBaseScaleCatalog.read(it) }
        val source = DataInputStream(assets.open("$root/film-rgb-base/prefixed-catalog.golden")).use { input ->
            val magic = ByteArray(8).also { input.readFully(it) }
            check(String(magic, Charsets.US_ASCII) == "CXOCAG01")
            check(Integer.reverseBytes(input.readInt()) == 147)
            check(Integer.reverseBytes(input.readInt()) == 4096)
            ShortArray(4096*3) { java.lang.Short.reverseBytes(input.readShort()) }
        }
        val direct = DynamicDirectFilmCatalog.create().load("ColorModeArgentiqueAstia") {
            assets.open("$root/dynamic-direct-catalog/$it")
        }.film
        val provider = DynamicProviderBwFilmCatalog.create()
        val eir = assets.open("$root/rgb-catalog/catalog.cxocat").use { RgbFilmCatalog.read(it) }
            .load("ColorModeArgentiqueKodakEIR") { assets.open("$root/rgb-catalog/$it") }
        fun hsv(group: String, name: String): HsvFilmLut =
            assets.open("$root/$group/catalog.cxocat").use { SharedFilmCatalog.read(it) }
                .load("ColorModeArgentique$name") { assets.open("$root/$group/$it") }
        val films = mapOf(
            "Astia" to direct,
            "KodakPortra400" to hsv("shared-catalog", "KodakPortra400"),
            "FujiSuperia200E6" to hsv("threecurve-catalog", "FujiSuperia200E6"),
            "KodakTriX400" to hsv("bw-catalog", "KodakTriX400"),
            "KodakHIEFiltre" to provider.load("ColorModeArgentiqueKodakHIEFiltre") {
                assets.open("$root/dynamic-provider-bw-catalog/$it") }.film,
            "RolleiIR400" to provider.load("ColorModeArgentiqueRolleiIR400") {
                assets.open("$root/dynamic-provider-bw-catalog/$it") }.film,
        )
        var components = 0
        for (name in films.keys + "KodakEIR") for (intensity in listOf(0,25,50,100,200)) {
            val balanced = ShortArray(source.size)
            val base = bases.find("ColorModeArgentique$name")
            val wb = if (base != null) NativeWhiteBalanceRgb.create(base, intensity = intensity)
                else NativeWhiteBalanceRgb.fromGains(1f,1f,1f)
            wb.applySrgb16Carrier(source, balanced)
            val internal = FloatArray(source.size)
            entry.renderRgb16(balanced, internal)
            val out = FloatArray(source.size)
            val strength = minOf(intensity/100f,1f)
            if (name == "KodakEIR") eir.renderRgb(internal,out,strength)
            else films.getValue(name).renderRgb(internal,out,strength)
            DataInputStream(assets.open("$root/strength/$name-$intensity.f32")).use { input ->
                for (i in out.indices) {
                    val expected = Integer.reverseBytes(input.readInt())
                    check(out[i].toRawBits() == expected) { "$name intensity=$intensity component=$i" }
                    components++
                }
                check(input.read() == -1)
            }
        }
        check(components == 35*4096*3)
    }
}
