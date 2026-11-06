package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private data class Context(val ev: Float, val renderingEv: Float, val pre: FloatArray, val extra: FloatArray)

private fun readContext(file: File): Context = DataInputStream(file.inputStream().buffered()).use { input ->
    val magic = ByteArray(9).also(input::readFully)
    check(magic.contentEquals("CXORBAKE1".toByteArray()))
    check(Integer.reverseBytes(input.readInt()) == 1)
    fun f() = Float.fromBits(Integer.reverseBytes(input.readInt()))
    val ev = f(); val renderingEv = f()
    val matrices = FloatArray(27) { f() }
    Context(ev, renderingEv, matrices.copyOfRange(0, 9), matrices.copyOfRange(18, 27))
}

private fun readCurve(file: File): FloatArray {
    val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    require(buffer.remaining() == 32768 * 2)
    return FloatArray(32769) { index ->
        if (index == 32768) 0f else buffer.short.toInt().also { require(it >= 0) }.toFloat()
    }.also { it[32768] = it[32767] }
}

/** Product-side numerical smoke test for all 18 recovered per-channel RAW entries. */
fun main(args: Array<String>) {
    require(args.size in 3..4) { "Expected catalog, bake fixture and curve directory" }
    val catalog = File(args[0]).readText()
    val contexts = File(args[1]).parentFile.listFiles { file ->
        file.extension == "cxorbake" && file.name.contains("elite", ignoreCase = true) && !file.name.contains("s9", ignoreCase = true)
    }!!.sortedBy { it.name }.map(::readContext)
    val curvesDir = File(args[2])
    val objectPattern = Regex("""\{\s*\"id\"\s*:\s*\"([^\"]+)\"\s*,\s*\"matrixB\"\s*:\s*\[([^]]+)]\s*,\s*\"curves\"\s*:\s*\[([^]]+)]""")
    var count = 0
    var disabled = 0
    var negative = 0
    val safety = linkedMapOf<String, Boolean>()
    for (match in objectPattern.findAll(catalog)) {
        val id = match.groupValues[1]
        val matrix = match.groupValues[2].split(',').map { it.trim().toFloat() }.toFloatArray()
        require(matrix.size == 9)
        val names = Regex("\"([^\"]+)\"").findAll(match.groupValues[3]).map { it.groupValues[1] }.toList()
        require(names.size == 3)
        val film = names.map { readCurve(File(curvesDir, it)) }.toTypedArray()
        val end = catalog.indexOf('}', match.range.last)
        val objectText = catalog.substring(match.range.first, end + 1)
        if (!Regex(""""controlledRaw"\s*:\s*(true|false)""").find(objectText)!!.groupValues[1].toBooleanStrict()) {
            disabled++
            continue
        }
        val transform = Regex(""""preTransform"\s*:\s*\[([^]]+)]""").find(objectText)!!.groupValues[1]
            .split(',').map { it.trim().toDouble() }.toDoubleArray()
        var maxNegative = 0
        for (context in contexts) {
            val pre = (Matrix3(transform) * Matrix3(DoubleArray(9) { context.pre[it].toDouble() }))
                .values().map(Double::toFloat).toFloatArray()
            val posts = NativePostCurves.buildChannelForwardPostCurves(film, context.renderingEv, matrix, context.extra)
            val inverse = Array(3) { NativePostCurves.inverseComposedPostCurve(posts[it]) }
            val baked = RawHsvGridBaker.bake(RawHsvBakeParameters(
                context.ev, context.renderingEv, pre, matrix, context.extra, film, inverse,
            ))
            val repaired = RawHsvGridBaker.repair(baked.grid)
            require(repaired.all(Float::isFinite)) { "$id produced non-finite repaired node" }
            val bad = (repaired.indices step 3).count { repaired[it + 1] < 0f || repaired[it + 2] < 0f }
            maxNegative = maxOf(maxNegative, bad)
            RawHsvFilmRenderer(repaired, posts[0], allowNegativeMultipliers = bad > 0, channelPostCurves = posts)
                .renderQ15(shortArrayOf(1200, 10000, 25000, 8000, 18000, 2200), ShortArray(6))
        }
        count++
        safety[id] = maxNegative == 0
        if (maxNegative > 0) negative++
        println("$id contexts=${contexts.size} maxNegative=$maxNegative")
    }
    check(count == 3 && disabled == 15) { "Expected 3 controlled / 15 disabled per-channel entries, got $count / $disabled" }
    if (args.size == 4) File(args[3]).apply { parentFile.mkdirs() }.writeText(safety.entries.joinToString(",\n", "{\n", "\n}\n") { "  \"${it.key}\": ${it.value}" })
    println("RAW three-curve bake: controlled=$count fallback=$disabled experimentalSignedSaturation=$negative finite=passed")
}
