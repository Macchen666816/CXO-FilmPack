@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.cxo.filmpack.engine

import dev.cxo.filmpack.core.NativeGrain8Geometry
import dev.cxo.filmpack.core.PostProcessSettings
import dev.cxo.filmpack.core.ToneCurvePreset
import dev.cxo.filmpack.core.openFileSource
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create

/** Bundle/asset directory on disk, e.g. the app's `Assets` folder. */
class FileAssetProvider(private val root: String) : AssetProvider {
    override fun read(path: String): ByteArray {
        val source = openFileSource("$root/$path")
        try { return source.read(0, source.size.toInt()) } finally { source.close() }
    }
}

@Throws(Throwable::class)
fun makeEngine(assetsRoot: String, grainPackPath: String?): FilmEngine = FilmEngine(FileAssetProvider(assetsRoot), grainPackPath)

private fun NSData.toBytes(expected: Int): ByteArray {
    require(length.toInt() == expected) { "Pixel buffer has ${length} bytes, expected $expected" }
    return bytes!!.reinterpret<ByteVar>().readBytes(expected)
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

/** RGBA8 (R,G,B,A byte order) in, opaque RGBA8 out, for rows [top, top+rows). Safe to call concurrently. */
@Throws(Throwable::class)
fun renderBand(job: RenderJob, rgba: NSData, top: Int, rows: Int): NSData {
    val bytes = rgba.toBytes(job.width * rows * 4)
    val count = job.width * rows
    val argb = IntArray(count) { i ->
        val o = i * 4
        ((bytes[o + 3].toInt() and 255) shl 24) or ((bytes[o].toInt() and 255) shl 16) or
            ((bytes[o + 1].toInt() and 255) shl 8) or (bytes[o + 2].toInt() and 255)
    }
    val out = job.renderBand(argb, top, rows)
    val result = ByteArray(count * 4)
    for (i in 0 until count) {
        val p = out[i]; val o = i * 4
        result[o] = (p shr 16).toByte(); result[o + 1] = (p shr 8).toByte(); result[o + 2] = p.toByte(); result[o + 3] = 255.toByte()
    }
    return result.toNSData()
}

/** RGBA16 little-endian (8 bytes/pixel, alpha ignored), e.g. CIContext `.RGBA16` output, in sRGB. */
@Throws(Throwable::class)
fun renderBand16(job: RenderJob, rgba16: NSData, top: Int, rows: Int): NSData {
    val count = job.width * rows
    val bytes = rgba16.toBytes(count * 8)
    val rgb = ShortArray(count * 3)
    for (i in 0 until count) {
        val o = i * 8; val d = i * 3
        rgb[d] = ((bytes[o].toInt() and 255) or ((bytes[o + 1].toInt() and 255) shl 8)).toShort()
        rgb[d + 1] = ((bytes[o + 2].toInt() and 255) or ((bytes[o + 3].toInt() and 255) shl 8)).toShort()
        rgb[d + 2] = ((bytes[o + 4].toInt() and 255) or ((bytes[o + 5].toInt() and 255) shl 8)).toShort()
    }
    val out = job.renderBandRgb16(rgb, top, rows)
    val result = ByteArray(count * 4)
    for (i in 0 until count) {
        val p = out[i]; val o = i * 4
        result[o] = (p shr 16).toByte(); result[o + 1] = (p shr 8).toByte(); result[o + 2] = p.toByte(); result[o + 3] = 255.toByte()
    }
    return result.toNSData()
}

@Throws(Throwable::class)
fun prepareJob(
    engine: FilmEngine, width: Int, height: Int, film: FilmChoice, intensity: Int,
    grain: GrainSettings, post: PostEditingSettings, loadTexture: (String) -> Texture,
): RenderJob = engine.prepare(width, height, film, intensity, grain, post, loadTexture)

fun filmsOf(engine: FilmEngine): List<FilmChoice> = engine.films

@Throws(Throwable::class)
fun makeTexture(rgba: NSData, width: Int, height: Int): Texture {
    val bytes = rgba.toBytes(width * height * 4)
    val pixels = IntArray(width * height) { i ->
        val o = i * 4
        ((bytes[o + 3].toInt() and 255) shl 24) or ((bytes[o].toInt() and 255) shl 16) or
            ((bytes[o + 1].toInt() and 255) shl 8) or (bytes[o + 2].toInt() and 255)
    }
    return Texture(pixels, width, height)
}

@Throws(Throwable::class)
fun makeGrainSettings(enabled: Boolean, identifier: String, intensity: Int, format: Int, manualSize: Float, seed: Long): GrainSettings =
    GrainSettings(enabled, identifier, intensity.coerceIn(0, 200),
        NativeGrain8Geometry.Format.entries[format.coerceIn(0, NativeGrain8Geometry.Format.entries.size - 1)],
        manualSize.takeIf { it.isFinite() }?.coerceIn(.5f, 5f) ?: 1f, seed.coerceIn(0, 0xFFFFFFFFL))

fun grainFormatNames(): List<String> = NativeGrain8Geometry.Format.entries.map { it.name }
fun toneCurveNames(): List<String> = ToneCurvePreset.entries.map { it.name }
fun edgePositionNames(): List<String> = EdgePosition.entries.map { it.name }

@Throws(Throwable::class)
fun makePostSettings(
    exposureEv: Float, brightness: Int, contrast: Int, temperature: Int, tint: Int, hue: Int,
    saturation: Int, vibrance: Int, highlights: Int, shadows: Int, whites: Int, blacks: Int, curve: Int,
    edgeId: String, edgeIntensity: Int, edgePosition: Int, edgeSeed: Long,
): PostEditingSettings {
    fun v(x: Int) = x.coerceIn(-100, 100)
    return PostEditingSettings(
        PostProcessSettings(
            exposureEv = exposureEv.takeIf { it.isFinite() }?.coerceIn(-3f, 3f) ?: 0f,
            brightness = v(brightness), contrast = v(contrast), temperature = v(temperature), tint = v(tint),
            hue = hue.coerceIn(-180, 180), saturation = v(saturation), vibrance = v(vibrance),
            highlights = v(highlights), shadows = v(shadows), whites = v(whites), blacks = v(blacks),
            curve = ToneCurvePreset.entries[curve.coerceIn(0, ToneCurvePreset.entries.size - 1)],
        ),
        EdgeDefectSettings(edgeId, edgeIntensity.coerceIn(0, 100),
            EdgePosition.entries[edgePosition.coerceIn(0, EdgePosition.entries.size - 1)], edgeSeed),
    )
}


@Throws(Throwable::class)
fun rawSupportsCamera(engine: FilmEngine, model: String, make: String): Boolean = engine.raw.supportsCamera(model, make)

@Throws(Throwable::class)
fun prepareRawJob(
    engine: FilmEngine, path: String, fullW: Int, fullH: Int, model: String, make: String,
    rawBps: Int, maximumAfterProcess: Int, camMul0: Float, camMul1: Float, camMul2: Float, camMul3: Float,
    width: Int, height: Int, film: FilmChoice?, intensity: Int, grain: GrainSettings,
    post: PostEditingSettings, loadTexture: (String) -> Texture,
): RenderJob = engine.prepareRaw(
    RawSource(path, fullW, fullH, model, make, rawBps, maximumAfterProcess, floatArrayOf(camMul0, camMul1, camMul2, camMul3)),
    width, height, film, intensity, grain, post, loadTexture)
