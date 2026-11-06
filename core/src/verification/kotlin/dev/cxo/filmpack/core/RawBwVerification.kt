package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

private data class RawBwFixture(
    val input: ShortArray, val sensor: ShortArray, val expected: ShortArray,
    val grid: FloatArray, val post: FloatArray, val matrix: FloatArray, val ev: Float, val highlight: Float,
)

private fun readRawBwFixture(file: File): RawBwFixture {
    val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val magic = ByteArray(8).also { b.get(it) }.toString(Charsets.US_ASCII)
    require(magic == "CXORAW01")
    val count = b.int
    val gridSize = b.int
    val postSize = b.int
    require(b.int == 3 && count in 1..100000 && gridSize == 17 * 91 * 12 * 3 && postSize == 32769)
    val matrix = FloatArray(9) { b.float }
    val ev = b.float; val highlight = b.float
    val grid = FloatArray(gridSize) { b.float }
    val post = FloatArray(postSize) { b.float }
    val input = ShortArray(count * 3) { b.short }
    val sensor = ShortArray(count * 3) { b.short }
    val expected = ShortArray(count * 3) { b.short }
    require(!b.hasRemaining())
    return RawBwFixture(input, sensor, expected, grid, post, matrix, ev, highlight)
}

private fun bwMetric(name: String, actual: ShortArray, expected: ShortArray, tolerance: Int) {
    var squares = 0.0; var max = 0; var equal = 0
    for (i in actual.indices) {
        val d = actual[i].toInt() - expected[i]
        squares += d.toDouble() * d
        max = maxOf(max, abs(d))
        if (d == 0) equal++
    }
    val rmse = sqrt(squares / actual.size) * 255 / 32767
    println("$name components=${actual.size} equal=$equal maxQ15=$max rmse255=$rmse")
    check(max <= tolerance) { "$name exceeded $tolerance Q15: $max" }
}

/** Native BW runtime parity, independent of provider/curve construction. */
fun main(args: Array<String>) {
    require(args.size == 2)
    val directory = File(args[0])
    val localData = File(args[1])
    val catalogText = File(localData, "raw-films/catalog.json").readText()
    val catalogObjects = Regex("""\{\s*"id"\s*:\s*"([^"]+)"[^}]*}""").findAll(catalogText).toList()
    check(catalogObjects.size == 148)
    var controlledCount = 0
    var typeBwCount = 0
    var runtimeBwCount = 0
    for (match in catalogObjects) {
        val id = match.groupValues[1]
        val assets = localData.listFiles()!!.filter(File::isDirectory).map { File(it, "$id.cxohsv") }.filter(File::isFile)
        check(assets.size == 1) { "Ambiguous BW provenance: $id" }
        val header = ByteBuffer.wrap(assets.single().readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8).also(header::get).toString(Charsets.US_ASCII)
        check(magic in setOf("CXOHSV01", "CXOHSV02", "CXOHSV03"))
        val type = if (magic == "CXOHSV01") false else header.getInt(32) == 1
        val runtime = if (magic == "CXOHSV01") false else header.getInt(36) == 1
        fun flag(name: String): Boolean = Regex(""""$name"\s*:\s*(true|false)""").find(match.value)
            ?.groupValues?.get(1)?.toBooleanStrict() ?: error("Missing $name: $id")
        if (flag("controlledRaw")) controlledCount++
        check(flag("typeBw") == type && flag("runtimeBw") == runtime) { "BW provenance drift: $id" }
        if (type) typeBwCount++
        if (runtime) runtimeBwCount++
    }
    check(typeBwCount == 58 && runtimeBwCount == 58 && controlledCount == 80)
    println("RAW BW catalog flags verified against 148 source asset headers; type/runtime BW=58")
    val fixtures = directory.listFiles { file -> file.extension == "golden" }!!.sortedBy { it.name }
    check(fixtures.size == 3)
    for (file in fixtures) {
        val f = readRawBwFixture(file)
        val renderer = RawHsvFilmRenderer(f.grid, f.post, allowNegativeMultipliers = true, runtimeBw = true)
        val rendered = ShortArray(f.sensor.size)
        renderer.renderQ15(f.sensor, rendered)
        bwMetric(file.name + " BW renderOnly", rendered, f.expected, 2)
        for (i in rendered.indices step 3) {
            check(rendered[i] == rendered[i + 1] && rendered[i] == rendered[i + 2]) {
                "BW output retained chroma at pixel ${i / 3}"
            }
        }
        val inPlace = f.sensor.copyOf()
        renderer.renderQ15(inPlace, inPlace)
        check(inPlace.contentEquals(rendered))
        val working = ShortArray(f.input.size)
        RawSensorToWorking(f.matrix, f.ev, f.highlight).renderQ15(f.input, working)
        bwMetric(file.name + " BW sensorOnly", working, f.sensor, 1)
        renderer.renderQ15(working, working)
        bwMetric(file.name + " BW full", working, f.expected, 6)
    }
    // Rebuild a real fixed-BW film using product catalog/preTransform and an
    // Elite camera anchor, not the native Tri-X pre matrix as an input.
    val trixObject = catalogObjects.single { it.groupValues[1] == "ColorModeArgentiqueKodakTriX400" }.value
    fun numbers(name: String): DoubleArray = Regex(""""$name"\s*:\s*\[([^]]+)]""").find(trixObject)!!
        .groupValues[1].split(',').map { it.trim().toDouble() }.toDoubleArray()
    val parameters = ByteBuffer.wrap(File(directory, "kodaktrix400.parameters.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    check(ByteArray(8).also(parameters::get).toString(Charsets.US_ASCII) == "CXORBW01")
    val ev = parameters.float; val renderingEv = parameters.float
    val nativePre = FloatArray(9) { parameters.float }
    val nativePost = FloatArray(9) { parameters.float }
    val extra = FloatArray(9) { parameters.float }
    val nativeFilm = FloatArray(32769) { parameters.float }
    val nativeInverse = FloatArray(32769) { parameters.float }
    val nativeGrid = FloatArray(RawHsvGridBaker.GRID_FLOATS) { parameters.float }
    check(!parameters.hasRemaining())
    val elite = ByteBuffer.wrap(File(localData, "raw-bake/raw-bake-s1rii-p1176257-elite-v1.cxorbake").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    elite.position(21)
    val elitePre = DoubleArray(9) { elite.float.toDouble() }
    val pre = (Matrix3(numbers("preTransform")) * Matrix3(elitePre)).values().map(Double::toFloat).toFloatArray()
    check(pre.indices.all { abs(pre[it] - nativePre[it]) < 1E-6f }) { "Fixed BW pre matrix did not match native Tri-X" }
    val post = numbers("matrixB").map(Double::toFloat).toFloatArray()
    check(post.contentEquals(nativePost))
    val curveName = Regex(""""curve"\s*:\s*"([^"]+)"""").find(trixObject)!!.groupValues[1]
    val filmBytes = ByteBuffer.wrap(File(localData, "raw-films/curves/$curveName").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val curve = FloatArray(32769) { if (it == 32768) 0f else filmBytes.short.toFloat() }.also { it[32768] = it[32767] }
    check(curve.contentEquals(nativeFilm))
    val inverse = NativePostCurves.buildComposedInverse(curve, renderingEv)
    val baked = RawHsvGridBaker.bake(RawHsvBakeParameters(ev, renderingEv, pre, post, extra, arrayOf(curve), arrayOf(inverse), bw = true))
    val repaired = RawHsvGridBaker.repair(baked.grid, skipSaturationClamp = true)
    // Hue is undefined for near-neutral BW nodes. Judge the resulting grayscale
    // signal against true native output, rather than unstable near-gray hue.
    val trixFixture = readRawBwFixture(File(directory, "kodaktrix400.golden"))
    val produced = ShortArray(trixFixture.sensor.size)
    RawHsvFilmRenderer(repaired, NativePostCurves.buildForwardPostCurve(curve, renderingEv), runtimeBw = true).renderQ15(trixFixture.sensor, produced)
    bwMetric("Tri-X product pre/catalog/bake/render", produced, trixFixture.expected, 4)
    val svError = (repaired.indices).filter { it % 3 != 0 }.maxOf { abs(repaired[it] - nativeGrid[it]) }
    val inverseError = inverse.indices.maxOf { abs(inverse[it] - nativeInverse[it]) }
    println("Tri-X product bake: maxSV=$svError inverseMax=$inverseError (near-gray hue intentionally excluded)")
    // An identity grid isolates the runtime BW switch from provider matrices,
    // which may already make the captured native grids almost achromatic.
    val identityGrid = FloatArray(RawHsvGridBaker.GRID_FLOATS) { index -> if (index % 3 == 0) 0f else 1f }
    val identityCurve = FloatArray(32769) { it.coerceAtMost(32767).toFloat() }
    val colors = shortArrayOf(8192, 16384, 24576, 24576, 8192, 16384)
    val colorOutput = ShortArray(colors.size)
    val bwOutput = ShortArray(colors.size)
    RawHsvFilmRenderer(identityGrid, identityCurve).renderQ15(colors, colorOutput)
    RawHsvFilmRenderer(identityGrid, identityCurve, runtimeBw = true).renderQ15(colors, bwOutput)
    check(!colorOutput.contentEquals(bwOutput))
    for (i in bwOutput.indices step 3) {
        check(bwOutput[i] == bwOutput[i + 1] && bwOutput[i] == bwOutput[i + 2])
    }
    println("RAW BW runtime: three native contexts, full sensor/render chain and isolated BW switch passed")
}
