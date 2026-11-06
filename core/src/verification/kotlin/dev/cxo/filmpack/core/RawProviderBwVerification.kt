package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private data class Fixture(
    val ev: Float, val renderingEv: Float, val pre: FloatArray, val post: FloatArray,
    val extra: FloatArray, val film: FloatArray, val inverse: FloatArray,
    val expectedPre: FloatArray, val expectedRepaired: FloatArray,
)

private fun readFixture(file: File): Fixture = DataInputStream(file.inputStream().buffered()).use { input ->
    val magic = ByteArray(9).also(input::readFully)
    check(magic.contentEquals("CXORBAKE1".toByteArray()))
    check(Integer.reverseBytes(input.readInt()) == 1)
    fun f() = Float.fromBits(Integer.reverseBytes(input.readInt()))
    val ev = f(); val renderingEv = f()
    val matrices = FloatArray(27) { f() }
    val film = FloatArray(32769) { f() }
    val inverse = FloatArray(32769) { f() }
    val expectedPre = FloatArray(RawHsvGridBaker.GRID_FLOATS) { f() }
    val expectedRepaired = FloatArray(RawHsvGridBaker.GRID_FLOATS) { f() }
    check(input.read() == -1)
    return Fixture(ev, renderingEv, matrices.copyOfRange(0, 9), matrices.copyOfRange(9, 18), matrices.copyOfRange(18, 27), film, inverse, expectedPre, expectedRepaired)
}

private fun floats(file: File): FloatArray = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).let { b -> FloatArray(b.remaining() / 4) { b.float } }

private fun nativeGrid(file: File): FloatArray {
    val native = floats(file)
    check(native.size == 17 * 91 * 12 * 4) { "unexpected native grid size ${native.size}" }
    val out = FloatArray(RawHsvGridBaker.GRID_FLOATS)
    var at = 0
    // Native capture is V,H,S,4; product/runtime order is S,H,V,3.
    for (s in 0 until 17) for (h in 0 until 91) for (v in 0 until 12) {
        val base = ((v * 91 + h) * 17 + s) * 4
        out[at++] = native[base]; out[at++] = native[base + 1]; out[at++] = native[base + 2]
    }
    return out
}

private fun maxSv(a: FloatArray, b: FloatArray): Float {
    var peak = 0f
    for (i in a.indices) if (i % 3 != 0) peak = max(peak, abs(a[i] - b[i]))
    return peak
}

private fun invalidMismatch(a: FloatArray, b: FloatArray): Int {
    var count = 0
    for (i in a.indices step 3) if ((a[i] == -1000f) != (b[i] == -1000f)) count++
    return count
}

private fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
    val sector = ((h % 360f) + 360f) % 360f / 60f
    val i = sector.toInt() % 6; val f = sector - sector.toInt()
    val p = v * (1f - s); val q = v * (1f - s * f); val t = v * (1f - s * (1f - f))
    return when (i) { 0 -> floatArrayOf(v, t, p); 1 -> floatArrayOf(q, v, p); 2 -> floatArrayOf(p, v, t); 3 -> floatArrayOf(p, q, v); 4 -> floatArrayOf(t, p, v); else -> floatArrayOf(v, p, q) }
}

private fun nodeInput(): ShortArray {
    val nodes = RawHsvGridBaker.hsvNodes(); val out = ShortArray(nodes.size)
    for (i in nodes.indices step 3) { val rgb = hsvToRgb(nodes[i], nodes[i + 1], nodes[i + 2]); for (c in 0..2) out[i + c] = (rgb[c].coerceIn(0f, 1f) * 32767f).roundToInt().toShort() }
    return out
}

fun main(args: Array<String>) {
    require(args.size == 2) { "Expected native evidence directory and provider-BW staging directory" }
    val native = File(args[0]); val staging = File(args[1]); val input = nodeInput()
    val cases = listOf(
        Triple("colormodeargentiquekodakhiefiltre-p1040758.cxorbake", "raw-bake-s1rii-p1040758-hiefiltre-v1", "KodakHIEFiltre/P1040758"),
        Triple("colormodeargentiquekodakhiefiltre-p1176257.cxorbake", "raw-bake-s1rii-p1176257-hiefiltre-v1", "KodakHIEFiltre/P1176257"),
        Triple("colormodeargentiquerolleiir400-p1040758.cxorbake", "raw-bake-s1rii-p1040758-rolleiir400-v1", "RolleiIR400/P1040758"),
        Triple("colormodeargentiquerolleiir400-p1176257.cxorbake", "raw-bake-s1rii-p1176257-rolleiir400-v1", "RolleiIR400/P1176257"),
    )
    for ((fileName, prefix, name) in cases) {
        val fixture = readFixture(File(staging, fileName))
        val post = NativePostCurves.buildForwardPostCurve(fixture.film, fixture.renderingEv)
        val inverse = NativePostCurves.inverseComposedPostCurve(post)
        val params = RawHsvBakeParameters(fixture.ev, fixture.renderingEv, fixture.pre, fixture.post, fixture.extra, arrayOf(fixture.film), arrayOf(inverse), bw = true)
        val baked = RawHsvGridBaker.bake(params)
        val repaired = RawHsvGridBaker.repair(baked.grid, skipSaturationClamp = true)
        val nativePre = nativeGrid(File(native, "$prefix.pre-repair.grid.f32.bin"))
        val nativeRepaired = nativeGrid(File(native, "$prefix.repaired.grid.f32.bin"))
        check(invalidMismatch(baked.grid, nativePre) == 0) { "$name invalid mask mismatch" }
        val preDrift = maxSv(baked.grid, nativePre); val repairDrift = maxSv(repaired, nativeRepaired)
        check(preDrift < .01f) { "$name pre SV drift $preDrift" }; check(repairDrift < .002f) { "$name repaired SV drift $repairDrift" }
        val nativePost = floats(File(native, "$prefix.prepared.raw-one-or-three-6144-curve-0.f32le.bin"))
        val ours = ShortArray(input.size); val nativeOut = ShortArray(input.size)
        RawHsvFilmRenderer(repaired, post, allowNegativeMultipliers = true, runtimeBw = true).renderQ15(input, ours)
        RawHsvFilmRenderer(nativeRepaired, nativePost, allowNegativeMultipliers = true, runtimeBw = true).renderQ15(input, nativeOut)
        var maxQ15 = 0
        for (i in ours.indices) maxQ15 = max(maxQ15, abs(ours[i].toInt() - nativeOut[i].toInt()))
        check(maxQ15 <= 4) { "$name runtime forward max Q15 $maxQ15" }
        println("{\"context\":\"$name\",\"preSVMax\":$preDrift,\"repairedSVMax\":$repairDrift,\"runtimeForwardMaxQ15\":$maxQ15,\"invalidMaskMismatch\":0}")
        // Two contexts additionally have true native full-pipeline Q15 output.
        // The other two above compare rebuilt/native grid application only.
        val slug = if (name.startsWith("KodakHIEFiltre")) "hiefiltre" else "rolleiir400"
        val photo = if (name.endsWith("P1040758")) "p1040758" else "p1176257"
        val fullPrefix = "small-grid-providerbw-$slug-$photo-grid17-v1"
        val fullFile = File(native, "$fullPrefix.grid-full-output.q15.bin")
        if (fullFile.isFile) {
            fun shorts(suffix: String): ShortArray {
                val b = ByteBuffer.wrap(File(native, fullPrefix + suffix).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                return ShortArray(4913 * 3) { b.short }
            }
            val source = shorts(".grid-input.q15.bin")
            val expected = shorts(".grid-full-output.q15.bin")
            val iface = ByteBuffer.wrap(File(native, "$fullPrefix.prepared.actual-interface.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val matrix = FloatArray(9) { iface.getFloat(0x17b0 + it * 4) }
            val working = ShortArray(source.size)
            check(fixture.ev == iface.getFloat(0x1714))
            RawSensorToWorking(matrix, fixture.ev, iface.getFloat(0x1710)).renderQ15(source, working)
            RawHsvFilmRenderer(repaired, post, runtimeBw = true).renderQ15(working, working)
            val peak = working.indices.maxOf { abs(working[it].toInt() - expected[it].toInt()) }
            check(peak <= 4) { "$name true native full max=$peak" }
            println("$name true native sensor/full17 maxQ15=$peak")
        }
    }
}
