package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File
import kotlin.math.abs

/** Cross-language application plus independent analytic storage/interpolation probes. */
fun main(args: Array<String>) {
    require(args.size == 3)
    val directory = File(args[0]); val catalogBytes = File(directory,"catalog.cxocat").readBytes()
    val catalog = RgbFilmCatalog.read(catalogBytes.inputStream())
    val entry = File(args[1]).inputStream().use { FixedSrgbEntryLutIO.read(it) }
    val input = DataInputStream(File(args[2]).inputStream().buffered())
    fun int() = Integer.reverseBytes(input.readInt())
    fun string(): String {
        val n = int(); check(n in 1..160)
        val bytes = ByteArray(n); input.readFully(bytes); return bytes.toString(Charsets.US_ASCII)
    }
    val magic = ByteArray(8); input.readFully(magic)
    check(magic.contentEquals("CXOCAG01".toByteArray(Charsets.US_ASCII)))
    check(int() == 3 && catalog.presets.size == 3)
    val n = int(); check(n == 6526)
    val codes = ShortArray(n*3) { java.lang.Short.reverseBytes(input.readShort()) }
    val internal = FloatArray(codes.size); entry.renderRgb16(codes,internal)
    var components = 0
    repeat(3) {
        val id = string(); val preset = catalog.presets[it]; check(preset.id == id)
        check(preset.renderingTypes.all { catalog.findByRenderingType(it) === preset })
        check(!preset.hasNativeHeldoutComparison)
        val expected = FloatArray(n*3) { Float.fromBits(int()) }
        val film = catalog.load(id) { File(directory,it).inputStream() }
        val pipeline = FixedSrgbRgbFilmPipeline(entry,film)
        val actual = FloatArray(n*3); pipeline.renderRgb16(codes,actual)
        val direct = internal.copyOf(); film.renderRgb(direct,direct)
        val pixel = FloatArray(5) { -99f }
        for (i in actual.indices) {
            check(actual[i].toRawBits() == expected[i].toRawBits()) { "$id component $i: ${actual[i]} != ${expected[i]}" }
            check(actual[i].toRawBits() == direct[i].toRawBits())
            check(actual[i].isFinite() && actual[i] in 0f..1f)
            components++
        }
        for (i in 0 until n step 19) {
            pipeline.sampleSrgb16(codes[i*3].toInt() and 65535,codes[i*3+1].toInt() and 65535,
                codes[i*3+2].toInt() and 65535,pixel,1)
            for (c in 0..2) check(pixel[c+1].toRawBits() == actual[i*3+c].toRawBits())
            check(pixel[0] == -99f && pixel[4] == -99f)
        }
    }
    check(input.read() == -1); input.close()
    check(catalog.find("unknown") == null && catalog.findByRenderingType(-1) == null)
    var opened = false
    check(runCatching { catalog.load("unknown") { opened = true; error("unreachable") } }.isFailure && !opened)
    val preset = catalog.presets.first(); val bytes = File(directory,preset.assetFile).readBytes()
    fun loadFails(b: ByteArray) = check(runCatching { catalog.load(preset.id) { b.inputStream() } }.isFailure)
    val bad = bytes.copyOf(); bad[100] = (bad[100].toInt() xor 1).toByte()
    loadFails(bad); loadFails(bytes.copyOf(bytes.size-1)); loadFails(bytes+byteArrayOf(0))
    var closed = false
    catalog.load(preset.id) { object : java.io.ByteArrayInputStream(bytes) {
        override fun close() { closed = true; super.close() }
    } }; check(closed)
    for (offset in listOf(0,8,12,16,20,24,28)) {
        val b = catalogBytes.copyOf(); b[offset] = (b[offset].toInt() xor 127).toByte()
        check(runCatching { RgbFilmCatalog.read(b.inputStream()) }.isFailure)
    }
    check(runCatching { RgbFilmCatalog.read((catalogBytes+byteArrayOf(0)).inputStream()) }.isFailure)

    // Independent multi-affine function: exact trilinear interpolation equals
    // direct evaluation, testing asymmetric axes, mixed terms, padding and guards.
    fun analytical(r: Double,g: Double,b: Double) = doubleArrayOf(
        .1+.2*r+.3*g+.1*b+.15*r*g,
        .05+.1*r+.15*g+.4*b+.1*g*b,
        .2+.2*r+.1*g+.15*b+.2*r*b)
    val nodes = FloatArray(RgbFilmLut.GRID_FLOATS)
    for (b in 0..64) for (g in 0..64) for (r in 0..64) {
        val x = analytical(minOf(r,63)/63.0,minOf(g,63)/63.0,minOf(b,63)/63.0)
        val k = ((b*65+g)*65+r)*4
        for (c in 0..2) nodes[k+c] = x[c].toFloat()
    }
    val curve = FloatArray(32769) { minOf(it,32767).toFloat() }
    val synthetic = RgbFilmLut(nodes,curve,curve)
    val rng = java.util.Random(20261031)
    val actual = FloatArray(3)
    repeat(1000) {
        val q = IntArray(3) { rng.nextInt(32768) }
        val encoded = FloatArray(3) { q[it].toFloat()/32767f }
        synthetic.sample(encoded[0],encoded[1],encoded[2],actual)
        val x = DoubleArray(3) { (q[it].toFloat()*Float.fromBits(0x38000100)).toDouble() }
        val expected = analytical(x[0],x[1],x[2])
        for (c in 0..2) check(abs(actual[c].toDouble()-expected[c]) <= 1.1/32767)
    }
    val invalid = nodes.copyOf(); invalid[3] = 1f
    check(runCatching { RgbFilmLut(invalid,curve,curve) }.isFailure)
    invalid[3] = 0f; invalid[64*4] += .1f
    check(runCatching { RgbFilmLut(invalid,curve,curve) }.isFailure)
    invalid[64*4] = Float.NaN
    check(runCatching { RgbFilmLut(invalid,curve,curve) }.isFailure)
    val unchanged = FloatArray(3) { -1f }
    check(runCatching { synthetic.renderRgb(floatArrayOf(0f,0f,Float.NaN),unchanged) }.isFailure)
    check(unchanged.all { it == -1f })
    // Exact half-integer raw outputs at zero input isolate ties-to-even.
    for ((sample,expected) in listOf(.5f to 0f,1.5f to 2f,2.5f to 2f)) {
        val constant = FloatArray(RgbFilmLut.GRID_FLOATS) { if (it%4==3) 0f else sample/32767f }
        RgbFilmLut(constant,curve,curve).sample(0f,0f,0f,actual)
        check(actual.all { it == expected/32767f })
    }
    println("{\"rgb_presets\":3,\"samples_per_preset\":$n,\"float_components\":$components,\"unequal_float_components\":0,\"analytical_probes\":1000,\"ties_even_probes\":3,\"native_heldout_compared_presets\":0,\"android_execution\":false}")
}
