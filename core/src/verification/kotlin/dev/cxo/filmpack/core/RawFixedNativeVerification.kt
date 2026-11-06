package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

private fun rawFixedFloats(file: File): FloatArray {
    val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(b.remaining() / 4) { b.float }
}
private fun rawFixedShorts(file: File): ShortArray {
    val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    return ShortArray(b.remaining() / 2) { b.short }.copyOf(4913 * 3)
}
private fun rawFixedMax(a: FloatArray, b: FloatArray): Float = a.indices.maxOf { abs(a[it] - b[it]) }
private fun rawFixedMetrics(label: String, a: ShortArray, b: ShortArray, tolerance: Int) {
    val maximum = a.indices.maxOf { abs(a[it].toInt() - b[it]) }
    val rmse = sqrt(a.indices.sumOf { val d = a[it].toInt() - b[it]; d.toDouble() * d } / a.size) * 255 / 32767
    println("$label maxQ15=$maximum rmse255=$rmse")
    check(maximum <= tolerance) { "$label exceeded $tolerance Q15: $maximum" }
}

/** Native parameter/runtime oracles for the production fixed-film pre and post selection. */
fun main(args: Array<String>) {
    val local = File(args[0]); val native = File(args[1])
    val text = File(local, "raw-films/catalog.json").readText()
    val objects = Regex("""\{\s*"id"\s*:\s*"([^"]+)"[^}]*}""").findAll(text).associate { it.groupValues[1] to it.value }
    fun array(row: String, name: String) = Regex(""""$name"\s*:\s*\[([^]]+)]""").find(row)!!.groupValues[1].split(',').map { it.trim().toDouble() }.toDoubleArray()
    fun curves(id: String): Array<FloatArray> {
        val row = objects.getValue(id)
        val names = Regex(""""curves"\s*:\s*\[([^]]+)]""").find(row)?.groupValues?.get(1)?.let { list ->
            Regex(""""([^"]+)"""").findAll(list).map { it.groupValues[1] }.toList()
        } ?: listOf(Regex(""""curve"\s*:\s*"([^"]+)"""").find(row)!!.groupValues[1])
        return names.map { name ->
            val b = ByteBuffer.wrap(File(local, "raw-films/curves/$name").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(32769) { if (it == 32768) 0f else b.short.toFloat() }.also { it[32768] = it[32767] }
        }.toTypedArray()
    }
    val elitePrefixes = mapOf(
        "p1040758" to "raw-bake-s1rii-p1040758-elite-v2",
        "p1176257" to "raw-bake-s1rii-p1176257-elite-v1",
        "p1055542" to "raw-provider-s1rii-p1055542-elite-root-v1",
    )
    fun file(prefix: String, suffix: String) = File(native, prefix + ".prepared." + suffix)
    var forwardContexts = 0
    for ((_, prefix) in elitePrefixes) {
        val rendering = ByteBuffer.wrap(file(prefix, "rendering-data.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val ev = rendering.getFloat(0x28)
        val built = NativePostCurves.buildForwardPostCurve(curves("ColorModeArgentiqueKodakEliteColor400")[0], ev)
        val expected = rawFixedFloats(file(prefix, "raw-one-or-three-6144-curve-0.f32le.bin"))
        val error = rawFixedMax(built, expected)
        println("RAW Elite forward ${prefix} max=$error")
        check(error < .1f)
        forwardContexts++
    }
    for ((token, slug) in listOf("Astia" to "astia", "Provia" to "provia", "Velvia" to "velvia", "Ekta100VS" to "ekta100vs", "Kod64" to "kod64")) {
        for (photo in listOf("p1040758", "p1176257")) {
            val prefix = if (slug == "astia") "raw-bake-s1rii-$photo-astia-camera-root-v1" else "raw-bake-s1rii-$photo-$slug-v1"
            val rendering = ByteBuffer.wrap(file(prefix, "rendering-data.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val built = NativePostCurves.buildForwardPostCurve(curves("ColorModeArgentique$token")[0], rendering.getFloat(0x28))
            val error = rawFixedMax(built, rawFixedFloats(file(prefix, "raw-one-or-three-6144-curve-0.f32le.bin")))
            println("RAW dynamic forward $token $photo max=$error")
            check(error < .1f)
            forwardContexts++
        }
    }
    var fixedContexts = 0
    for ((token, slug) in listOf("KodakPortra400" to "portra400", "FujiSuperia200E6" to "superia200e6")) {
        val id = "ColorModeArgentique$token"; val row = objects.getValue(id)
        for (photo in listOf("p1040758", "p1176257")) {
            val prefix = "small-grid-fixedpre-$slug-$photo-grid17-v1"
            val rendering = ByteBuffer.wrap(file(prefix, "rendering-data.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val interfaceBytes = file(prefix, "actual-interface.bin").readBytes()
            val iface = ByteBuffer.wrap(interfaceBytes).order(ByteOrder.LITTLE_ENDIAN)
            val eliteRendering = ByteBuffer.wrap(file(elitePrefixes.getValue(photo), "rendering-data.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val elitePre = DoubleArray(9) { eliteRendering.getFloat(4 + it * 4).toDouble() }
            val pre = (Matrix3(array(row, "preTransform")) * Matrix3(elitePre)).values().map(Double::toFloat).toFloatArray()
            check(rawFixedMax(pre, FloatArray(9) { rendering.getFloat(4 + it * 4) }) < 1E-6f)
            val matrixB = array(row, "matrixB").map(Double::toFloat).toFloatArray()
            check(rawFixedMax(matrixB, FloatArray(9) { rendering.getFloat(0x80 + it * 4) }) == 0f)
            val ev = iface.getFloat(0x1714); val renderingEv = rendering.getFloat(0x28)
            val film = curves(id)
            val extra = FloatArray(9) { iface.getFloat(0x15c8 + it * 4) }
            val post = if (film.size == 3) NativePostCurves.buildChannelForwardPostCurves(film, renderingEv, matrixB, extra)
                else arrayOf(NativePostCurves.buildForwardPostCurve(film[0], renderingEv))
            val inverse = Array(film.size) { NativePostCurves.inverseComposedPostCurve(post[it]) }
            for (channel in film.indices) {
                val expected = rawFixedFloats(file(prefix, "raw-one-or-three-6144-curve-$channel.f32le.bin"))
                println("RAW fixed forward $token $photo channel=$channel max=${rawFixedMax(post[channel], expected)}")
                check(rawFixedMax(post[channel], expected) < .25f)
            }
            val bake = RawHsvGridBaker.bake(RawHsvBakeParameters(ev, renderingEv, pre, matrixB, extra, film, inverse))
            val grid = RawHsvGridBaker.repair(bake.grid)
            val renderer = RawHsvFilmRenderer(grid, post[0], allowNegativeMultipliers = true, channelPostCurves = post.takeIf { it.size == 3 })
            val sensor = rawFixedShorts(File(native, "$prefix.grid-sensor-output.q15.bin"))
            val expected = rawFixedShorts(File(native, "$prefix.grid-full-output.q15.bin"))
            val actual = ShortArray(sensor.size)
            renderer.renderQ15(sensor, actual)
            rawFixedMetrics("RAW fixed $token $photo production render", actual, expected, 32)
            val input = rawFixedShorts(File(native, "$prefix.grid-input.q15.bin"))
            val working = ShortArray(input.size)
            RawSensorToWorking(FloatArray(9) { iface.getFloat(0x17b0 + it * 4) }, ev, iface.getFloat(0x1710)).renderQ15(input, working)
            renderer.renderQ15(working, working)
            rawFixedMetrics("RAW fixed $token $photo production full", working, expected, 32)
            fixedContexts++
        }
    }
    // Real recovered shared curve with descending samples: runtime must retain
    // its forward response; MakeLUTIncreasing belongs only to inverse building.
    val nonmonotone = NativePostCurves.buildForwardPostCurve(curves("ColorModeNegativeLomographyColorNegative800")[0], 0f)
    check((1 until nonmonotone.size).any { nonmonotone[it] < nonmonotone[it - 1] })
    val identity = FloatArray(RawHsvGridBaker.GRID_FLOATS) { if (it % 3 == 0) 0f else 1f }
    RawHsvFilmRenderer(identity, nonmonotone).renderQ15(shortArrayOf(2000, 9000, 24000), ShortArray(3))
    check(forwardContexts == 13 && fixedContexts == 4)
    println("RAW product post selection and fixed pre: $forwardContexts native forward curves / $fixedContexts native full 17 cubed contexts passed")
}
