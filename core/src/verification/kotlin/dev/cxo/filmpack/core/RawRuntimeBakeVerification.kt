package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

private data class CatalogFilm(val id: String, val matrixB: FloatArray, val curve: String, val typeBw: Boolean, val runtimeBw: Boolean, val preTransform: DoubleArray, val controlledRaw: Boolean) {
    fun preMatrix(context: FloatArray): FloatArray =
        (Matrix3(preTransform) * Matrix3(DoubleArray(9) { context[it].toDouble() })).values().map(Double::toFloat).toFloatArray()
}

private fun readCatalog(file: File): Map<String, CatalogFilm> {
    val text = file.readText()
    val objectPattern = Regex("""\{\s*\"id\"\s*:\s*\"([^\"]+)\"\s*,\s*\"matrixB\"\s*:\s*\[([^]]+)]\s*,\s*\"curve\"\s*:\s*\"([^\"]+)\"""")
    return objectPattern.findAll(text).associate { match ->
        val matrix = match.groupValues[2].split(',').map { it.trim().toFloat() }.toFloatArray()
        require(matrix.size == 9)
        val end = text.indexOf('}', match.range.last)
        require(end > match.range.last)
        val objectText = text.substring(match.range.first, end + 1)
        fun flag(name: String): Boolean = Regex(""""$name"\s*:\s*(true|false)""").find(objectText)?.groupValues?.get(1)?.toBooleanStrict()
            ?: error("Missing explicit $name for ${match.groupValues[1]}")
        val transform = Regex(""""preTransform"\s*:\s*\[([^]]+)]""").find(objectText)!!.groupValues[1]
            .split(',').map { it.trim().toDouble() }.toDoubleArray()
        val film = CatalogFilm(match.groupValues[1], matrix, match.groupValues[3], flag("typeBw"), flag("runtimeBw"), transform, flag("controlledRaw"))
        film.id to film
    }
}

private fun readCurve(file: File): FloatArray {
    val bytes = file.readBytes()
    require(bytes.size == 32768 * 2)
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(32769) { i ->
        if (i == 32768) 0f else input.short.toInt().also { require(it >= 0) }.toFloat()
    }.also { it[32768] = it[32767] }
}

private data class BakeFixture(
    val ev: Float, val renderingEv: Float, val pre: FloatArray, val post: FloatArray, val extra: FloatArray,
    val film: FloatArray, val inverse: FloatArray, val repaired: FloatArray,
)

private fun readFixture(file: File): BakeFixture = DataInputStream(file.inputStream().buffered()).use { input ->
    val magic = ByteArray(9).also(input::readFully)
    check(magic.contentEquals("CXORBAKE1".toByteArray()))
    check(Integer.reverseBytes(input.readInt()) == 1)
    fun f() = Float.fromBits(Integer.reverseBytes(input.readInt()))
    val ev = f(); val renderingEv = f()
    val matrices = FloatArray(27) { f() }
    val film = FloatArray(32769) { f() }
    val inverse = FloatArray(32769) { f() }
    repeat(RawHsvGridBaker.GRID_FLOATS) { f() }
    val repaired = FloatArray(RawHsvGridBaker.GRID_FLOATS) { f() }
    BakeFixture(ev, renderingEv, matrices.copyOfRange(0, 9), matrices.copyOfRange(9, 18), matrices.copyOfRange(18, 27), film, inverse, repaired)
}

private fun maxAbs(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size)
    var result = 0f
    for (i in a.indices) result = maxOf(result, abs(a[i] - b[i]))
    return result
}

/** Checks the product catalog's shared-HSV runtime bake without Android APIs. */
fun main(args: Array<String>) {
    require(args.size in 3..4) { "Expected catalog directory, fixture and curve directory" }
    val catalog = readCatalog(File(args[0]))
    check(catalog.size == 130) { "Unexpected shared RAW catalog size: ${catalog.size}" }
    val fixtureFiles = File(args[1]).parentFile.listFiles { file ->
        file.extension == "cxorbake" && file.name.contains("elite", ignoreCase = true) && !file.name.contains("s9", ignoreCase = true)
    }!!.sortedBy { it.name }
    val fixtures = fixtureFiles.map(::readFixture)
    val fixture = readFixture(File(args[1]))
    val elite = catalog.getValue("ColorModeArgentiqueKodakEliteColor400")
    val eliteCurve = readCurve(File(args[2], elite.curve))
    check(maxAbs(eliteCurve, fixture.film) == 0f) { "Elite curve conversion changed Float values" }
    val eliteInverse = NativePostCurves.buildComposedInverse(eliteCurve, fixture.renderingEv)
    val inverseDrift = maxAbs(eliteInverse, fixture.inverse)
    val eliteBake = RawHsvGridBaker.bake(RawHsvBakeParameters(
        fixture.ev, fixture.renderingEv, fixture.pre, elite.matrixB, fixture.extra,
        arrayOf(eliteCurve), arrayOf(eliteInverse),
    ))
    val repaired = RawHsvGridBaker.repair(eliteBake.grid)
    val bakeDrift = maxAbs(repaired, fixture.repaired)
    check(bakeDrift < .01f) { "Elite dynamic bake drifted: $bakeDrift" }
    println("Elite dynamic bake inverseMax=$inverseDrift bakeMax=$bakeDrift satMin=${repaired.filterIndexed { i, _ -> i % 3 == 1 }.minOrNull()}")

    val probes = listOf(
        "ColorModeArgentiqueKodakPortra400",
        "ColorModeArgentiqueKodakTriX400",
    )
    for (id in probes) {
        val film = catalog.getValue(id)
        val curve = readCurve(File(args[2], film.curve))
        val inverse = NativePostCurves.buildComposedInverse(curve, fixture.renderingEv)
        val baked = RawHsvGridBaker.bake(RawHsvBakeParameters(
            fixture.ev, fixture.renderingEv, film.preMatrix(fixture.pre), film.matrixB, fixture.extra,
            arrayOf(curve), arrayOf(inverse), bw = film.typeBw,
        ))
        val fixed = RawHsvGridBaker.repair(baked.grid, skipSaturationClamp = film.typeBw)
        check(fixed.all(Float::isFinite)) { "$id produced non-finite repaired grid" }
        // The independent repair can retain negative multipliers for some
        // high-gamut matrices. The product renderer has an explicit
        // experimental mode for those finite nodes instead of silently using
        // the old sRGB RAW bridge.
        val negativeNodes = (fixed.indices step 3).count { fixed[it + 1] < 0f || fixed[it + 2] < 0f }
        val byV = IntArray(12)
        (fixed.indices step 3).forEach { i -> if (fixed[i + 1] < 0f || fixed[i + 2] < 0f) byV[(i / 3) % 12]++ }
        RawHsvFilmRenderer(fixed, NativePostCurves.buildForwardPostCurve(curve, fixture.renderingEv), allowNegativeMultipliers = negativeNodes > 0, runtimeBw = film.runtimeBw)
        println("$id invalid=${baked.invalidNodes} clampInvalid=${baked.clampInvalidNodes} negative=$negativeNodes byV=${byV.joinToString(",")} satMin=${fixed.filterIndexed { i, _ -> i % 3 == 1 }.minOrNull()} vMin=${fixed.filterIndexed { i, _ -> i % 3 == 2 }.minOrNull()}")
    }
    // Dynamic providers must be rebuilt against their own context: an Elite
    // pre matrix can produce finite output while selecting the wrong colors.
    val dynamicNames = listOf("astia", "provia", "velvia", "ekta100vs", "kod64")
    var dynamicContexts = 0
    for (name in dynamicNames) {
        val token = when (name) {
            "astia" -> "Astia"; "provia" -> "Provia"; "velvia" -> "Velvia"
            "ekta100vs" -> "Ekta100VS"; else -> "Kod64"
        }
        val film = catalog.getValue("ColorModeArgentique$token")
        val curve = readCurve(File(args[2], film.curve))
        val ownFiles = File(args[1]).parentFile.listFiles { file ->
            file.extension == "cxorbake" && file.name.contains("-$name-", ignoreCase = true)
        }!!.sortedBy { it.name }
        check(ownFiles.size == 2) { "$token needs two independent captured contexts" }
        for (file in ownFiles) {
            val own = readFixture(file)
            check(curve.contentEquals(own.film)) { "$token catalog curve differs from native fixture" }
            check(film.matrixB.contentEquals(own.post)) { "$token post matrix differs from native fixture" }
            val inverse = NativePostCurves.buildComposedInverse(curve, own.renderingEv)
            val baked = RawHsvGridBaker.bake(RawHsvBakeParameters(
                own.ev, own.renderingEv, film.preMatrix(own.pre), film.matrixB, own.extra,
                arrayOf(curve), arrayOf(inverse), bw = film.typeBw,
            ))
            val fixed = RawHsvGridBaker.repair(baked.grid, skipSaturationClamp = film.typeBw)
            val drift = maxAbs(fixed, own.repaired)
            check(drift < .002f) { "$token runtime bake differs from native context: $drift" }
            RawHsvFilmRenderer(fixed, NativePostCurves.buildForwardPostCurve(curve, own.renderingEv), allowNegativeMultipliers = true)
            dynamicContexts++
            println("RAW dynamic $token fixture=${file.name} repairedMax=$drift")
        }
    }
    var safe = 0
    var unsafe = 0
    val unsafeIds = mutableListOf<String>()
    val safety = linkedMapOf<String, Boolean>()
    catalog.values.filter { it.controlledRaw }.filter { it.id.removePrefix("ColorModeArgentique").lowercase() !in dynamicNames }.forEach { film ->
        val curve = readCurve(File(args[2], film.curve))
        val safeForEveryContext = fixtures.all { context ->
            val inverse = NativePostCurves.buildComposedInverse(curve, context.renderingEv)
            val baked = RawHsvGridBaker.bake(RawHsvBakeParameters(
                context.ev, context.renderingEv, film.preMatrix(context.pre), film.matrixB, context.extra,
                arrayOf(curve), arrayOf(inverse), bw = film.typeBw,
            ))
            val fixed = RawHsvGridBaker.repair(baked.grid, skipSaturationClamp = film.typeBw)
            val bad = (fixed.indices step 3).count { fixed[it + 1] < 0f || fixed[it + 2] < 0f }
            RawHsvFilmRenderer(fixed, NativePostCurves.buildForwardPostCurve(curve, context.renderingEv), allowNegativeMultipliers = bad > 0, runtimeBw = film.runtimeBw)
            bad == 0
        }
        safety[film.id] = safeForEveryContext
        if (safeForEveryContext) safe++ else { unsafe++; unsafeIds += film.id }
    }
    if (args.size == 4) File(args[3]).apply { parentFile.mkdirs() }.writeText(safety.entries.joinToString(",\n", "{\n", "\n}\n") { "  \"${it.key}\": ${it.value}" })
    println("RAW shared-HSV validated=$safe experimentalSignedSaturation=$unsafe experimentalIds=${unsafeIds.joinToString(",")}")
    println("RAW runtime bake: shared=${safe + unsafe}, dynamic=${dynamicNames.size}, dynamicContexts=$dynamicContexts, catalog=${catalog.size}, Elite inverseMax=$inverseDrift bakeMax=$bakeDrift")
}
