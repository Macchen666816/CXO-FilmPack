package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Predictions use only source codes, parameter assets and static base scales.
 * Native reference images are read by the independent comparison script later.
 */
fun main(args: Array<String>) {
    require(args.size == 5) { "Expected local-data root, base catalog, entry, source golden and output" }
    val local = File(args[0])
    val bases = File(args[1]).inputStream().use { FilmRgbBaseScaleCatalog.read(it) }
    val entry = File(args[2]).inputStream().use { FixedSrgbEntryLutIO.read(it) }
    val samples = 4096
    val source = DataInputStream(File(args[3]).inputStream().buffered()).use { input ->
        val magic = ByteArray(8).also { input.readFully(it) }
        check(magic.contentEquals("CXOCAG01".toByteArray(Charsets.US_ASCII)))
        Integer.reverseBytes(input.readInt())
        check(Integer.reverseBytes(input.readInt()) >= samples)
        ShortArray(samples * 3) { java.lang.Short.reverseBytes(input.readShort()) }
    }
    val ids = HashSet<String>()
    var prefixed = 0
    DataOutputStream(File(args[4]).outputStream().buffered()).use { output ->
        fun int(value: Int) = output.writeInt(Integer.reverseBytes(value))
        fun string(value: String) {
            val bytes = value.toByteArray(Charsets.US_ASCII); int(bytes.size); output.write(bytes)
        }
        fun predictions(id: String, render: (ShortArray, FloatArray) -> Unit,
                        sample: (Int, Int, Int, FloatArray) -> Unit) {
            check(ids.add(id))
            val values = FloatArray(source.size)
            render(source, values)
            check(values.all { it.isFinite() && it in 0f..1f })
            val single = FloatArray(3)
            for (i in intArrayOf(0, 127, samples - 1)) {
                sample(source[i * 3].toInt() and 65535, source[i * 3 + 1].toInt() and 65535,
                    source[i * 3 + 2].toInt() and 65535, single)
                repeat(3) { check(single[it].toRawBits() == values[i * 3 + it].toRawBits()) }
            }
            string(id); values.forEach { int(it.toRawBits()) }
        }
        output.write("CXOCAG01".toByteArray(Charsets.US_ASCII)); int(147); int(samples)
        source.forEach { output.writeShort(java.lang.Short.reverseBytes(it).toInt()) }
        for (group in listOf("shared-catalog", "bw-catalog", "threecurve-catalog")) {
            val directory = File(local, group)
            val catalog = File(directory, "catalog.cxocat").inputStream().use { SharedFilmCatalog.read(it) }
            for (preset in catalog.presets) {
                val base = requireNotNull(bases.find(preset.id))
                check(preset.renderingTypes.contains(base.renderingType))
                val stage = NativeWhiteBalanceRgb.create(base)
                if (!base.isIdentity) prefixed++
                val film = catalog.load(preset.id) { File(directory, it).inputStream() }
                val pipeline = FixedSrgbFilmPipeline(entry, film, stage)
                predictions(preset.id, { src, dst -> pipeline.renderRgb16(src, dst) },
                    { r, g, b, out -> pipeline.sampleSrgb16(r, g, b, out) })
            }
        }
        val directory = File(local, "rgb-catalog")
        val catalog = File(directory, "catalog.cxocat").inputStream().use { RgbFilmCatalog.read(it) }
        for (preset in catalog.presets) {
            val base = requireNotNull(bases.find(preset.id))
            val film = catalog.load(preset.id) { File(directory, it).inputStream() }
            val pipeline = FixedSrgbRgbFilmPipeline(entry, film, NativeWhiteBalanceRgb.create(base))
            if (!base.isIdentity) prefixed++
            predictions(preset.id, { src, dst -> pipeline.renderRgb16(src, dst) },
                { r, g, b, out -> pipeline.sampleSrgb16(r, g, b, out) })
        }
        check(ids.size == 147 && prefixed == 27)
    }
    println("{\"prefixed_catalog_presets\":147,\"nonidentity_prefixes\":$prefixed,\"source_samples\":$samples," +
        "\"prediction_components\":${147 * samples * 3},\"native_pixels_read\":false,\"android_execution\":false}")
}
