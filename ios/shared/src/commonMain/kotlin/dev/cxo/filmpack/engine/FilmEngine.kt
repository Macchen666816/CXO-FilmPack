package dev.cxo.filmpack.engine

import dev.cxo.filmpack.core.*
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/** Bounded rows in display sRGB, after the film/ICC pipeline (ported from the Android app). */
class PostEditingRenderer(assets: AssetProvider, loadTexture: (String) -> Texture, settings: PostEditingSettings,
                          private val width: Int, height: Int, orientation: Int = 1) {
    private val development = PostProcessTransform(settings.development)
    private val edge = EdgeDefects.prepare(assets, loadTexture, settings.edge, width, height, orientation)
    fun apply(pixels: IntArray, top: Int, count: Int = pixels.size) {
        development.applyArgbInPlace(pixels, count)
        if (edge != null) {
            var offset = 0
            while (offset < count) {
                val size = minOf(width, count - offset)
                edge.applyRow(pixels, top + offset / width, 0, size, offset)
                offset += size
            }
        }
    }
}

/**
 * Render plan for one photograph, used only on the app’s single serial RenderQueue.
 * RAW plans reuse scratch buffers between bands.
 */
class RenderJob internal constructor(
    val width: Int, val height: Int,
    private val apply: (ShortArray, FloatArray, Int, Int) -> Unit,
    private val grain: PhotoGrainLayer?,
    private val outputCmm: FixedWideGamutToSrgb,
    private val post: PostEditingRenderer?,
) {
    /** [argb]: straight-alpha ARGB for rows [top, top+rows). Returns opaque ARGB for the same rows. */
    fun renderBand(argb: IntArray, top: Int, rows: Int): IntArray {
        require(rows > 0 && top >= 0 && top + rows <= height && argb.size >= width * rows)
        val count = width * rows
        val input = ShortArray(count * 3)
        for (i in 0 until count) {
            val p = argb[i]; val o = i * 3
            input[o] = (((p shr 16) and 255) * 257).toShort()
            input[o + 1] = (((p shr 8) and 255) * 257).toShort()
            input[o + 2] = ((p and 255) * 257).toShort()
        }
        return finish(input, IntArray(count) { (argb[it] ushr 24) and 255 }, top, rows)
    }

    /**
     * Native 16-bit sRGB input (packed R,G,B unsigned16 per pixel, e.g. from Apple's RAW pipeline).
     * The photograph is opaque. Returns opaque ARGB for rows [top, top+rows).
     */
    fun renderBandRgb16(rgb16: ShortArray, top: Int, rows: Int): IntArray {
        require(rows > 0 && top >= 0 && top + rows <= height && rgb16.size >= width * rows * 3)
        return finish(rgb16, null, top, rows)
    }

    private fun finish(input: ShortArray, alphas: IntArray?, top: Int, rows: Int): IntArray {
        val count = width * rows
        val output = FloatArray(count * 3)
        apply(input, output, width, 0)
        grain?.renderSourceLayout(output, output, width, 0, top)
        outputCmm.render(output, output)
        val out = IntArray(count)
        for (i in 0 until count) {
            val o = i * 3
            val alpha = alphas?.get(i) ?: 255
            fun channel(k: Int): Int {
                val value = output[o + k]
                require(value.isFinite()) { "胶片计算产生无效色彩" }
                val encoded = (value.coerceIn(0f, 1f) * 255f).roundToInt()
                return (encoded * alpha + 255 * (255 - alpha) + 127) / 255
            }
            out[i] = (255 shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
        }
        post?.apply(out, top, count)
        return out
    }
}

/**
 * Platform-neutral core of the Android `PhotoProcessor` for JPEG/PNG/HEIC-style sRGB photographs.
 * Decoding, EXIF orientation, and encoding stay in the host app; this class only sees pixels.
 */
class FilmEngine(private val assets: AssetProvider, grainPackPath: String? = null) {
    val films: List<FilmChoice> = loadFilms()
    val raw by lazy { RawFilmPipeline(assets) }

    private val entry by lazy { FixedSrgbEntryLutIO.read(ByteArrayInputStream(assets.read("films/srgb-entry-pix65.cxoentry"))) }
    private val outputCmm by lazy { FixedWideGamutToSrgb.read(assets.read("films/output-cmm/widegamut-to-srgb.lcmsbin")) }
    private val grainContexts by lazy { NativeGrain8RenderingContext.read(assets.read("films/no-toning-context.cxoctx")) }
    private val grainResources: NativeGrain8Resources? = grainPackPath?.let { NativeGrain8Resources.open(it) }
    private val grainLoader: PhotoGrainLoader? = grainResources?.let { PhotoGrainLoader.fromResources(it) }
    private val edgeChoices by lazy { EdgeDefects.catalog(assets) }

    private var cachedId: String? = null
    private var cachedLut: Any? = null
    private var cachedIntensity = -1
    private var cachedTransform: ((ShortArray, FloatArray, Int, Int) -> Unit)? = null

    val grainAvailable: Boolean get() = grainLoader != null
    fun grainIdentifiers(): List<String> = grainResources?.catalog?.definitions()?.map { it.identifier } ?: emptyList()
    fun edgeDefects(): List<EdgeDefectChoice> = edgeChoices

    private fun loadFilms(): List<FilmChoice> =
        MiniJson.rows(assets.read("films.json").decodeToString()).map { row ->
            FilmChoice(row["id"] as String, row["token"] as String, row["name"] as String, row["category"] as String,
                row["experimental"] as Boolean, row["model"] as String, row["asset"] as String,
                (row["baseRed"] as Double).toFloat(), (row["baseGreen"] as Double).toFloat(), (row["baseBlue"] as Double).toFloat())
        }.also { rows -> require(rows.isNotEmpty() && rows.map { it.id }.toSet().size == rows.size) }

    /** Film strength 0..200; same operation order as the Android build. */
    private fun transform(film: FilmChoice, intensity: Int): (ShortArray, FloatArray, Int, Int) -> Unit {
        require(intensity in 0..200) { "强度必须在 0 到 200 之间" }
        if (film.id == cachedId && intensity == cachedIntensity) return requireNotNull(cachedTransform)
        val lut = if (film.id == cachedId) requireNotNull(cachedLut) else when (film.model) {
            "rgb" -> RgbFilmLutIO.read(ByteArrayInputStream(assets.read(film.asset)))
            "hsv", "direct", "provider-bw" ->
                HsvFilmLutIO.read(ByteArrayInputStream(assets.read(film.asset)), allowEndpoint32768 = film.model == "direct")
            else -> throw IllegalStateException("该胶片暂不可用")
        }
        val t = intensity.toFloat() / 100f
        val gains = NativeWhiteBalanceRgb.fromGains((1f - t) + t * film.baseRed, (1f - t) + t * film.baseGreen, (1f - t) + t * film.baseBlue)
        val render: (ShortArray, FloatArray, Int, Int) -> Unit = when (lut) {
            is RgbFilmLut -> {
                val pipeline = FixedSrgbRgbFilmPipeline(entry, lut, gains, minOf(t, 1f))
                val function: (ShortArray, FloatArray, Int, Int) -> Unit =
                    { src, dst, width, x -> pipeline.renderRgb16ExactSampling0(src, dst, width, x) }
                function
            }
            is HsvFilmLut -> {
                val pipeline = FixedSrgbFilmPipeline(entry, lut, gains, minOf(t, 1f))
                val function: (ShortArray, FloatArray, Int, Int) -> Unit =
                    { src, dst, width, x -> pipeline.renderRgb16ExactSampling0(src, dst, width, x) }
                function
            }
            else -> throw IllegalStateException("该胶片暂不可用")
        }
        cachedId = film.id; cachedLut = lut; cachedIntensity = intensity; cachedTransform = render
        return render
    }

    /** Prepare a grain layer on the photograph's display grid (EXIF already applied by the host). */
    fun grainLayer(settings: GrainSettings, film: FilmChoice, width: Int, height: Int): PhotoGrainLayer? {
        if (!settings.enabled) return null
        val loader = requireNotNull(grainLoader) { "内置颗粒尚未就绪" }
        val id = settings.resolve(film, grainIdentifiers())
        val flags = grainContexts.resolve(film.token)
        return loader.prepare(PhotoGrainRequest(width, height, 1, id, settings.format, settings.manualSize,
            settings.intensity, settings.seed, flags.color, flags.neutralizeRedBlue))
    }

    /** Build the immutable plan. Heavy one-time work (LUT load, grain preparation) happens here. */
    fun prepare(width: Int, height: Int, film: FilmChoice, intensity: Int, grain: GrainSettings,
                post: PostEditingSettings, loadTexture: (String) -> Texture): RenderJob {
        require(width > 0 && height > 0)
        val layer = grainLayer(grain, film, width, height)
        val postRenderer = if (post.isIdentity) null else PostEditingRenderer(assets, loadTexture, post, width, height, 1)
        return RenderJob(width, height, transform(film, intensity), layer, outputCmm, postRenderer)
    }

    fun prepareRaw(source: RawSource, width: Int, height: Int, film: FilmChoice?, intensity: Int,
                   grain: GrainSettings, post: PostEditingSettings, loadTexture: (String) -> Texture): RenderJob {
        require(width > 0 && height > 0)
        val apply = raw.transform(source, film, intensity)
        val layer = film?.let { grainLayer(grain, it, width, height) }
        val postRenderer = if (post.isIdentity) null else PostEditingRenderer(assets, loadTexture, post, width, height, 1)
        return RenderJob(width, height, apply, layer, outputCmm, postRenderer)
    }

    /** Convenience: render a whole image serially in bounded row batches. */
    fun render(argb: IntArray, width: Int, height: Int, job: RenderJob,
               progress: (Int) -> Unit = {}, cancelled: () -> Boolean = { false }): IntArray {
        require(argb.size == width * height && job.width == width && job.height == height)
        val out = IntArray(argb.size)
        val batch = (524_288 / width / 8 * 8).coerceIn(8, 32)
        var top = 0
        while (top < height) {
            if (cancelled()) throw CancellationException("已取消")
            val rows = minOf(batch, height - top)
            val band = job.renderBand(argb.copyOfRange(top * width, (top + rows) * width), top, rows)
            band.copyInto(out, top * width)
            top += rows
            progress((top.toLong() * 100 / height).toInt())
        }
        return out
    }

    fun close() { grainResources?.close() }
}
