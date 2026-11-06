package dev.cxo.filmpack.android

import android.test.AndroidTestCase
import android.util.Log
import dev.cxo.filmpack.core.FilmRgbBaseScaleCatalog
import dev.cxo.filmpack.core.FixedSrgbEntryLutIO
import dev.cxo.filmpack.core.FixedSrgbFilmPipeline
import dev.cxo.filmpack.core.FixedSrgbRgbFilmPipeline
import dev.cxo.filmpack.core.NativeWhiteBalanceRgb
import dev.cxo.filmpack.core.RgbFilmCatalog
import dev.cxo.filmpack.core.SharedFilmCatalog
import java.io.DataInputStream

/**
 * Replays the fixed 147-film JVM prediction fixture on Android's runtime.
 *
 * The staged assets contain recovered parameter-derived film resources and
 * static base scales. This is a JVM-versus-Android consistency test; the
 * separately retained native catalog batch remains the original-engine
 * comparison evidence.
 */
@Suppress("DEPRECATION")
class FixedCatalogAndroidVerificationTest : AndroidTestCase() {
    fun testFixedCatalogPipelinesBitExactly() {
        val assets = context.assets
        val root = "fixed-film"
        val bases = assets.open("$root/film-rgb-base/catalog.cxowbs").use {
            FilmRgbBaseScaleCatalog.read(it)
        }
        check(bases.presets.size == 147 && bases.presets.count { !it.isIdentity } == 27)
        val entry = assets.open("$root/srgb-entry-pix65.cxoentry").use { FixedSrgbEntryLutIO.read(it) }

        DataInputStream(assets.open("$root/film-rgb-base/prefixed-catalog.golden").buffered()).use { data ->
            fun int() = Integer.reverseBytes(data.readInt())
            fun short() = java.lang.Short.reverseBytes(data.readShort())
            fun nextExpected(id: String, values: FloatArray) {
                val idLength = int()
                check(idLength in 1..160)
                val fixtureId = ByteArray(idLength).also { data.readFully(it) }.toString(Charsets.US_ASCII)
                check(fixtureId == id) { "Fixture/catalog ID order mismatch: $fixtureId != $id" }
                for (component in values.indices) {
                    val expected = int()
                    check(values[component].toRawBits() == expected) {
                        "Android fixed-film mismatch $id component $component"
                    }
                }
            }

            val magic = ByteArray(8).also { data.readFully(it) }
            check(magic.contentEquals("CXOCAG01".toByteArray(Charsets.US_ASCII)))
            val fixtureFilms = int()
            val samples = int()
            check(fixtureFilms == 147 && samples == 4096)
            val source = ShortArray(samples * 3) { short() }
            var films = 0
            var nonIdentity = 0

            for (group in listOf("shared-catalog", "bw-catalog", "threecurve-catalog")) {
                val catalog = assets.open("$root/$group/catalog.cxocat").use { SharedFilmCatalog.read(it) }
                for (preset in catalog.presets) {
                    val base = requireNotNull(bases.find(preset.id))
                    check(preset.renderingTypes.contains(base.renderingType))
                    val film = catalog.load(preset.id) { assets.open("$root/$group/$it") }
                    val output = FloatArray(source.size)
                    FixedSrgbFilmPipeline(entry, film, NativeWhiteBalanceRgb.create(base)).renderRgb16(source, output)
                    nextExpected(preset.id, output)
                    films++
                    if (!base.isIdentity) nonIdentity++
                }
            }

            val rgbGroup = "rgb-catalog"
            val rgbCatalog = assets.open("$root/$rgbGroup/catalog.cxocat").use { RgbFilmCatalog.read(it) }
            for (preset in rgbCatalog.presets) {
                val base = requireNotNull(bases.find(preset.id))
                check(preset.renderingTypes.contains(base.renderingType))
                val film = rgbCatalog.load(preset.id) { assets.open("$root/$rgbGroup/$it") }
                val output = FloatArray(source.size)
                FixedSrgbRgbFilmPipeline(entry, film, NativeWhiteBalanceRgb.create(base)).renderRgb16(source, output)
                nextExpected(preset.id, output)
                films++
                if (!base.isIdentity) nonIdentity++
            }

            check(films == fixtureFilms && nonIdentity == 27)
            check(data.read() == -1) { "Unexpected prefixed-catalog fixture trailing bytes" }
            Log.i("FixedCatalogAndroidVerification", "films=$films source_rgb_samples=$samples components=${films * samples * 3} exact=true native_execution=false")
        }
    }
}
