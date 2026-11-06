package dev.cxo.filmpack.android

import android.test.AndroidTestCase
import dev.cxo.filmpack.core.DynamicDirectFilmCatalog
import dev.cxo.filmpack.core.FixedSrgbEntryLutIO

/** Android asset and pipeline boundary for the isolated five-film direct catalog. */
class DynamicDirectAndroidVerificationTest : AndroidTestCase() {
    fun testDynamicDirectAssetsLoadAndRenderFinite() {
        val assets = context.assets
        val root = "fixed-film"
        val catalog = DynamicDirectFilmCatalog.create()
        val entry = assets.open("$root/srgb-entry-pix65.cxoentry").use { FixedSrgbEntryLutIO.read(it) }
        var rendered = 0
        for (preset in catalog.presets) {
            val data = catalog.load(preset.id) {
                assets.open("$root/dynamic-direct-catalog/$it")
            }
            val pipeline = data.pipeline(entry)
            val out = FloatArray(3)
            pipeline.sampleSrgb16(1234, 32768, 54321, out)
            check(out.all { it.isFinite() && it in 0f..1f })
            rendered++
        }
        check(rendered == 5)
    }
}
