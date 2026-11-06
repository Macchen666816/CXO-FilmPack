package dev.cxo.filmpack.core

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream

/** Cross-language batch verification. Only Portra has native comparison evidence. */
fun main(args: Array<String>) {
    require(args.size in 3..4) { "Expected catalog directory, entry asset, golden, optional output" }
    val directory = File(args[0])
    val manifest = File(directory, "catalog.cxocat").readBytes()
    val catalog = SharedFilmCatalog.read(ByteArrayInputStream(manifest))
    val entry = File(args[1]).inputStream().use { FixedSrgbEntryLutIO.read(it) }
    val input = DataInputStream(File(args[2]).inputStream().buffered())
    fun int() = Integer.reverseBytes(input.readInt())
    fun string(): String {
        val n = int(); check(n in 1..160)
        val bytes = ByteArray(n); input.readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }
    val magic = ByteArray(8); input.readFully(magic)
    check(magic.contentEquals("CXOCAG01".toByteArray(Charsets.US_ASCII)))
    val count = int(); val n = int()
    check(count == catalog.presets.size && count in 1..(if (catalog.postCurveChannels == 3) 18 else if (catalog.runtimeBw) 58 else 68) && n in 4096..10000)
    check(catalog.jpegToneMode == if (catalog.runtimeBw) 1 else 0)
    check(catalog.presets.all { it.repairBwType == catalog.runtimeBw })
    val codes = ShortArray(n * 3) { java.lang.Short.reverseBytes(input.readShort()) }
    val internal = FloatArray(codes.size); entry.renderRgb16(codes, internal)
    var unequal = 0L; var components = 0L
    val output = if (args.size == 4) FileOutputStream(args[3]).buffered() else null
    try {
        repeat(count) {
            val id = string()
            val preset = requireNotNull(catalog.find(id))
            check(preset == catalog.presets[it])
            check(preset.renderingTypes.all { type -> catalog.findByRenderingType(type) == preset })
            val expected = FloatArray(n * 3) { Float.fromBits(int()) }
            val film = catalog.load(id) { filename -> File(directory, filename).inputStream() }
            check(film.postCurveChannels == catalog.postCurveChannels)
            val actual = FloatArray(n * 3)
            FixedSrgbFilmPipeline(entry, film).renderRgb16(codes, actual)
            val direct = FloatArray(n * 3); film.renderRgb(internal, direct)
            if (catalog.postCurveChannels == 3 && it == 0) {
                val inPlace = internal.copyOf(); film.renderRgb(inPlace, inPlace)
                check(inPlace.contentEquals(direct))
                val pixel = FloatArray(5) { -99f }
                for (index in 0 until n step 17) {
                    film.sample(internal[index * 3], internal[index * 3 + 1], internal[index * 3 + 2], pixel, 1)
                    for (c in 0..2) check(pixel[c + 1].toRawBits() == direct[index * 3 + c].toRawBits())
                    check(pixel[0] == -99f && pixel[4] == -99f)
                }
            }
            for (i in actual.indices) {
                check(actual[i].isFinite() && actual[i] in 0f..1f)
                if (actual[i].toRawBits() != expected[i].toRawBits()) unequal++
                check(actual[i].toRawBits() == expected[i].toRawBits()) {
                    "$id bit mismatch at$i: ${actual[i]} vs ${expected[i]}"
                }
                check(actual[i].toRawBits() == direct[i].toRawBits())
                components++
                val bits = actual[i].toRawBits()
                if (output != null) repeat(4) { byte -> output.write(bits ushr (8 * byte) and 255) }
            }
        }
        check(input.read() == -1)
    } finally { output?.close(); input.close() }

    check(catalog.find("unknown") == null && catalog.findByRenderingType(-1) == null)
    var opened = false
    check(runCatching { catalog.load("unknown") { opened = true; error("must not open") } }.isFailure)
    check(!opened)
    val first = catalog.presets.first()
    val bytes = File(directory, first.assetFile).readBytes()
    val changed = bytes.copyOf(); changed[changed.size / 2] = (changed[changed.size / 2].toInt() xor 1).toByte()
    check(runCatching { catalog.load(first.id) { ByteArrayInputStream(changed) } }.isFailure)
    check(runCatching { catalog.load(first.id) { ByteArrayInputStream(bytes.copyOf(bytes.size - 1)) } }.isFailure)
    check(runCatching { catalog.load(first.id) { ByteArrayInputStream(bytes + byteArrayOf(0)) } }.isFailure)
    var closed = false
    val closeProbe = object : ByteArrayInputStream(bytes) {
        override fun close() { closed = true; super.close() }
    }
    catalog.load(first.id) { closeProbe }; check(closed)

    // Catalog context, bounds, identity/asset pairing and duplicate identity guards.
    fun readFails(data: ByteArray) = check(runCatching { SharedFilmCatalog.read(data.inputStream()) }.isFailure)
    for (offset in intArrayOf(0, 8, 12, 16, 20, 24, 28, 32, 36)) {
        val bad = manifest.copyOf(); bad[offset] = (bad[offset].toInt() xor 1).toByte(); readFails(bad)
    }
    val badCount = manifest.copyOf(); repeat(4) { badCount[40 + it] = 255.toByte() }; readFails(badCount)
    readFails(manifest.copyOf(manifest.size - 1)); readFails(manifest + byteArrayOf(0))
    fun le(at: Int): Int = (manifest[at].toInt() and 255) or ((manifest[at + 1].toInt() and 255) shl 8) or
        ((manifest[at + 2].toInt() and 255) shl 16) or ((manifest[at + 3].toInt() and 255) shl 24)
    val idLength = le(44); val filenameAt = 48 + idLength + 4
    val badFilename = manifest.copyOf(); badFilename[filenameAt] = '.'.code.toByte(); readFails(badFilename)
    val fileLength = le(48 + idLength)
    val recordEnd = filenameAt + fileLength + 32 + 4 + 4 + (if (manifest[7] != '1'.code.toByte()) 4 else 0) + 4 + first.renderingTypes.size * 4
    val record = manifest.copyOfRange(44, recordEnd)
    val duplicate = manifest.copyOfRange(0, 44) + record + record
    repeat(4) { duplicate[40 + it] = (2 ushr (it * 8)).toByte() }; readFails(duplicate)
    val nativeCount = catalog.presets.count { it.hasNativeHeldoutComparison }
    check(nativeCount == if (catalog.find("ColorModeArgentiqueKodakPortra400") == null) 0 else 1)
    if (catalog.runtimeBw) verifyHsvBranches(File(directory, "branches"))
    if (catalog.postCurveChannels == 3) verifyThreeCurveFormat(bytes)
    println("""{"presets":$count,"samples_per_preset":$n,"float_components":$components,"unequal_float_components":$unequal,"ev0_presets":${catalog.presets.count { it.exposureDelta == 0f }},"negative_ev_presets":${catalog.presets.count { it.exposureDelta < 0f }},"native_heldout_compared_presets":$nativeCount,"runtime_bw":${catalog.runtimeBw},"jpeg_tc":${catalog.jpegToneMode},"postcurve_channels":${catalog.postCurveChannels},"catalog_lookup_hash_context_and_format_checks":"passed","android_execution":false,"kotlin_runtime_bake":false,"new_native_heldout_validations":0}""")
}

private fun verifyThreeCurveFormat(bytes: ByteArray) {
    fun fails(bad: ByteArray) = check(runCatching { HsvFilmLutIO.read(bad.inputStream()) }.isFailure)
    for (offset in intArrayOf(28, 32, 36, 40)) {
        val bad = bytes.copyOf(); bad[offset] = 2; fails(bad)
    }
    fails(bytes.copyOf(bytes.size - 1)); fails(bytes + byteArrayOf(0))
    fun withFloat(at: Int, value: Float): ByteArray = bytes.copyOf().also { data ->
        repeat(4) { data[at + it] = (value.toRawBits() ushr (8 * it)).toByte() }
    }
    // Sratio may be negative after final native repair. Vratio, nonfinite nodes
    // and the invalid-H sentinel remain rejected; no new zero clamp is inserted.
    HsvFilmLutIO.read(withFloat(48, -.25f).inputStream())
    fails(withFloat(52, -.25f)); fails(withFloat(48, Float.NaN)); fails(withFloat(44, -1000f))
}
