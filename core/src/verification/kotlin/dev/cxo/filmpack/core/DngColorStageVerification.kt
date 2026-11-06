package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Native oracle assets stay outside production and are supplied by path. */
fun main(args: Array<String>) {
    val root = File(args.single())
    fun bytes(name: String) = ByteBuffer.wrap(File(root, name).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    fun shorts(name: String): ShortArray {
        val b = bytes(name)
        return ShortArray(b.remaining() / 2) { b.short }
    }
    val prefix = "small-grid-apple-original-dng-grid-v1.grid-"
    val source = shorts(prefix + "input.q15.bin")
    val expectedPre = shorts(prefix + "sensor-output.q15.bin")
    val expectedFull = shorts(prefix + "full-output.q15.bin")
    val context = bytes("small-grid-apple-original-grid-v1.prepared.actual-interface.bin")
    fun matrix(offset: Int) = FloatArray(9) { context.getFloat(offset + it * 4) }
    check(DngRenderingProfile.originalExtraMatrix().contentEquals(matrix(0x1610)))
    check(DngRenderingProfile.workingToProPhotoMatrix().contentEquals(matrix(0x1634)))
    val portraContext = bytes("small-grid-apple-portra-grid-v1.prepared.actual-interface.bin")
    check(DngRenderingProfile.filmExtraMatrix().contentEquals(
        FloatArray(9) { portraContext.getFloat(0x15c8 + it * 4) }))
    check(DngRenderingProfile.sensorToWorking().indices.all {
        abs(DngRenderingProfile.sensorToWorking()[it] - portraContext.getFloat(0x17b0 + it * 4)) <= 1f / 65536f
    })
    val stage = DngColorMatrixStage(matrix(0x17b0), context.getFloat(0x18e8), context.getFloat(0x1710))
    val pre = ShortArray(source.size)
    stage.renderQ15(source, pre)
    fun compare(name: String, actual: ShortArray, expected: ShortArray, maximum: Int, rmsLimit: Double) {
        val delta = actual.indices.map { actual[it].toInt() - expected[it].toInt() }
        val max = delta.maxOf { abs(it) }
        val rms = sqrt(delta.sumOf { it.toDouble() * it } / delta.size)
        check(max <= maximum && rms <= rmsLimit) { "$name max=$max rms=$rms" }
        println("$name ${delta.size} native components: max=$max Q15, rms=$rms Q15")
    }
    compare("DNG matrix/gamut", pre, expectedPre, 1, .05)
    val inplace = source.copyOf()
    stage.renderQ15(inplace, inplace)
    check(inplace.contentEquals(pre))
    // Validate LUT separately against the native pre-stage, isolating errors.
    val extra = matrix(0x1610)
    val transformed = ShortArray(expectedPre.size)
    for (i in expectedPre.indices step 3) for (c in 0..2) {
        val value = ((expectedPre[i] * extra[c * 3] + 0f) + expectedPre[i + 1] * extra[c * 3 + 1]) + expectedPre[i + 2] * extra[c * 3 + 2]
        transformed[i + c] = Math.rint(value.coerceIn(0f, 32767f).toDouble()).toInt().toShort()
    }
    val gridBytes = bytes("small-grid-apple-original-grid-v1.prepared.interface-storage-grid.bin")
    val grid = FloatArray(gridBytes.remaining() / 4) { gridBytes.float }
    val lut = DngRenderingLutStage(grid)
    val output = ShortArray(transformed.size)
    lut.renderQ15(transformed, output)
    compare("DNG runtime LUT", output, expectedFull, 8, .25)
    lut.renderQ15(transformed, transformed)
    check(transformed.contentEquals(output))
    DngRenderingProfile.originalRenderer()(source, output)
    compare("DNG generated neutral rendering", output, expectedFull, 32, 2.1)
    val actualPrefix = "dynamic-compute-apple-original-boundary-v1.tif.boundary.actual-"
    val actualInput = shorts(actualPrefix + "input-512.q15.bin")
    val actualOutput = ShortArray(actualInput.size)
    DngRenderingProfile.originalRenderer()(actualInput, actualOutput)
    compare("DNG image sample neutral rendering", actualOutput,
        shorts(actualPrefix + "output-512.q15.bin"), 32, 2.1)

    // This independent native rebake was also bitwise equal to the live
    // prepared Portra LUT. Oracle data are verification inputs only.
    val bakePrefix = "raw-bake-apple-portra-extra15c8-v1"
    fun floats(suffix: String): FloatArray {
        val b = bytes(bakePrefix + suffix)
        return FloatArray(b.remaining() / 4) { b.float }
    }
    fun hsvGrid(suffix: String): FloatArray {
        val native = floats(suffix)
        return FloatArray(RawHsvGridBaker.GRID_FLOATS).also { grid3 ->
            for (s in 0..16) for (h in 0..90) for (v in 0..11) for (c in 0..2) {
                grid3[((s * 91 + h) * 12 + v) * 3 + c] = native[((v * 91 + h) * 17 + s) * 4 + c]
            }
        }
    }
    val rendering = bytes(bakePrefix + ".argument.rendering1660.bin")
    val bakeParameters = RawHsvBakeParameters(
        floats(".argument.ev1714.f32.bin")[0], rendering.getFloat(0x28),
        FloatArray(9) { rendering.getFloat(4 + it * 4) },
        FloatArray(9) { rendering.getFloat(0x80 + it * 4) },
        DngRenderingProfile.filmExtraMatrix(),
        arrayOf(floats(".prepared.rendering-curve-48.f32le.bin")),
        arrayOf(floats(".prepared.raw-one-or-three-6232-curve-0.f32le.bin")),
        rendering.get(0).toInt() != 0,
    )
    val baked = RawHsvGridBaker.bake(bakeParameters, hsvGrid(".input.grid.f32.bin"))
    fun compareGrid(name: String, actual: FloatArray, expected: FloatArray, limit: Float) {
        check(actual.all(Float::isFinite)) { "$name produced nonfinite output" }
        val max = actual.indices.maxOf { abs(actual[it] - expected[it]) }
        check(max < limit) { "$name max=$max" }
        println("$name ${actual.size} native components: max=$max")
    }
    val nativeBake = hsvGrid(".pre-repair.grid.f32.bin")
    for (i in nativeBake.indices step 3) check((baked.grid[i] == -1000f) == (nativeBake[i] == -1000f)) {
        "Portra invalid mask mismatch at node ${i / 3}"
    }
    compareGrid("ProRAW Portra pre-repair", baked.grid, nativeBake, .01f)
    compareGrid("ProRAW Portra repaired", RawHsvGridBaker.repair(baked.grid),
        hsvGrid(".repaired.grid.f32.bin"), .002f)

    val local = File("local-data")
    val providers = File(local, "raw-static-providers/catalog.cxornp").inputStream().use(RawNegativeProviderCatalog::read)
    val provider = requireNotNull(providers.film("ColorModeArgentiqueKodakPortra400"))
    val providerBake = provider.bake(5600.52197265625f, 0f, DngRenderingProfile.filmExtraMatrix()) { name ->
        File(local, "raw-films/curves/$name").inputStream().use(RawNegativeProviderCatalog::readQ15Curve)
    }
    compareGrid("ProRAW Portra static provider pre-repair", providerBake.preRepairGrid, nativeBake, .01f)
    compareGrid("ProRAW Portra static provider repaired", providerBake.repairedGrid,
        hsvGrid(".repaired.grid.f32.bin"), .002f)
    val portraPrefix = "small-grid-apple-portra-grid-v1.grid-"
    val portraInput = shorts(portraPrefix + "input.q15.bin")
    val sensorOutput = ShortArray(portraInput.size)
    RawSensorToWorking(FloatArray(9) { portraContext.getFloat(0x17b0 + it * 4) }, 0f, 0f)
        .renderQ15(portraInput, sensorOutput)
    compare("ProRAW Portra sensor", sensorOutput, shorts(portraPrefix + "sensor-output.q15.bin"), 32, 2.1)
    val filmOutput = ShortArray(portraInput.size)
    providerBake.renderer().renderQ15(sensorOutput, filmOutput)
    compare("ProRAW Portra static full pipeline", filmOutput, shorts(portraPrefix + "full-output.q15.bin"), 32, 2.1)
}
