package dev.cxo.filmpack.engine

import dev.cxo.filmpack.core.*
import java.io.ByteArrayInputStream

/*
 * The original camera-RAW route of the Android app (RawRenderPipeline / RawCameraProfiles /
 * RawCameraBwCatalog), ported without the Android framework. Input is LibRaw's camera-linear
 * RGB16 (output_color=0, linear gamma, camera white balance), already rotated to display
 * orientation by LibRaw; output is the same wide-gamut Q15 the RGB film path produces, so grain,
 * the output CMM and post-editing are shared with RenderJob.
 */

/** A decoded camera-linear RGB16LE cache on disk (headerless, width*height*6 bytes). */
class RawSource(
    val path: String, val width: Int, val height: Int,
    val model: String, val make: String,
    val rawBps: Int, val maximumAfterProcess: Int, val camMul: FloatArray,
) {
    init {
        require(width > 0 && height > 0 && rawBps in 1..16 && maximumAfterProcess in 1..65535)
        require(camMul.size == 4)
    }

    fun readRows(top: Int, count: Int, dst: ShortArray) {
        require(top >= 0 && count > 0 && top + count <= height && dst.size >= width * count * 3)
        val source = openFileSource(path)
        try {
            val bytes = source.read(top.toLong() * width * 6, width * count * 6)
            for (i in 0 until width * count * 3) {
                dst[i] = ((bytes[i * 2].toInt() and 255) or ((bytes[i * 2 + 1].toInt() and 255) shl 8)).toShort()
            }
        } finally { source.close() }
    }
}

internal class RawCameraProfiles(private val assets: AssetProvider) {
    private class Entry(val id: String, val fidelityAsset: String, val wbAsset: String)
    class Camera(val id: String, val fidelity: RawFidelityProfile, val whiteBalance: RawWhiteBalance, val exposureBiasEv: Float)

    private val profiles = HashMap<String, Camera>()
    private val entries: Map<String, Entry> by lazy {
        val byModel = HashMap<String, Entry>()
        val ids = HashSet<String>()
        val bodyIds = HashSet<Int>()
        for (path in listOf("raw/cameras.json", "raw/batch-generated/cameras.json")) {
            @Suppress("UNCHECKED_CAST")
            val root = MiniJson.parse(assets.read(path).decodeToString()) as Map<String, Any?>
            require(root["schema"] == "CXOCAMERAS1")
            @Suppress("UNCHECKED_CAST")
            for (row in root["cameras"] as List<Map<String, Any?>>) {
                val entry = Entry(row["id"] as String, row["fidelity"] as String, row["whiteBalance"] as String)
                require(ids.add(entry.id) && bodyIds.add((row["bodyId"] as Double).toInt()))
                require(entry.id.matches(Regex("[A-Za-z0-9_.-]+")))
                require(listOf(entry.fidelityAsset, entry.wbAsset).all(::isSafe))
                @Suppress("UNCHECKED_CAST")
                for (model in row["models"] as List<String>) {
                    val key = model.trim().lowercase()
                    require(key.isNotEmpty() && byModel.put(key, entry) == null) { "Duplicate RAW model alias" }
                }
            }
        }
        byModel
    }

    private fun isSafe(path: String) = path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.all { c -> c.isLetterOrDigit() || c in "_.-" } }

    fun forModel(model: String, make: String = ""): Camera? {
        val key = model.trim().lowercase()
        val qualified = "${make.trim()} ${model.trim()}".trim().lowercase()
        val legacy = when (make.trim().lowercase()) {
            "fujifilm" -> "finepix $key"
            "leica" -> "$key digital camera"
            else -> null
        }
        val entry = entries[key] ?: entries[qualified] ?: legacy?.let { entries[it] } ?: return null
        return profiles.getOrPut(entry.id) { load(entry) }
    }

    private fun load(entry: Entry): Camera {
        @Suppress("UNCHECKED_CAST")
        val json = MiniJson.parse(assets.read("raw/${entry.fidelityAsset}").decodeToString()) as Map<String, Any?>
        fun matrix(name: String): FloatArray {
            @Suppress("UNCHECKED_CAST")
            val rows = json[name] as List<List<Double>>
            require(rows.size == 3)
            return FloatArray(9) { i -> rows[i / 3][i % 3].toFloat() }
        }
        val fidelity = RawFidelityProfile(matrix("fidelityTungsten"), matrix("fidelityDaylight"),
            (json["tempLow"] as Double).toFloat(), (json["tempHigh"] as Double).toFloat())
        val bias = (json["exposureBiasEv"] as Double).toFloat().also { require(it.isFinite()) }
        val b = assets.read("raw/${entry.wbAsset}")
        require(b.size >= 20 && b.copyOfRange(0, 8).decodeToString() == "CXOWBG01") { "Unsupported RAW WB asset" }
        var o = 8
        fun int(): Int { val v = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or
            ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24); o += 4; return v }
        fun float(): Float = Float.fromBits(int())
        val nt = int(); val ni = int(); val ns = int()
        require(nt in 2..128 && ni in 2..128 && ns == nt * ni * 3)
        require(b.size == 20 + (nt + ni + ns) * 4)
        val temperatures = FloatArray(nt) { float() }
        val tints = FloatArray(ni) { float() }
        val scales = FloatArray(ns) { float() }
        require(o == b.size)
        return Camera(entry.id, fidelity, RawWhiteBalance(temperatures, tints, scales), bias)
    }
}

internal class RawCameraBwCatalog(assets: AssetProvider) {
    private class Camera(val tone: FloatArray, val fidelity: RawTargetFidelityProfile, val jpegBeforeTc: RawTargetFidelityProfile)
    private val cameras: Map<String, Camera>
    private val films: Map<String, RawCameraBwProvider>

    init {
        @Suppress("UNCHECKED_CAST")
        val root = MiniJson.parse(assets.read("raw-camera-bw/catalog.json").decodeToString()) as Map<String, Any?>
        require(root["schema"] == "CXOCAMERABW1")
        fun curve(name: String): FloatArray {
            require(name.endsWith(".bin") && name.all { it.isLetterOrDigit() || it in "_.-" })
            return RawNegativeProviderCatalog.readQ15Curve(ByteArrayInputStream(assets.read("raw-camera-bw/curves/$name")))
        }
        fun matrix(v: Any?): FloatArray {
            @Suppress("UNCHECKED_CAST") val a = v as List<Double>
            require(a.size == 9)
            return FloatArray(9) { a[it].toFloat() }.also { m -> require(m.all { it.isFinite() }) }
        }
        fun profile(v: Any?): RawTargetFidelityProfile {
            @Suppress("UNCHECKED_CAST") val r = v as Map<String, Any?>
            return RawTargetFidelityProfile(matrix(r["tungsten"]), matrix(r["daylight"]),
                (r["temperature_low"] as Double).toFloat(), (r["temperature_high"] as Double).toFloat())
        }
        @Suppress("UNCHECKED_CAST")
        cameras = (root["cameras"] as List<Map<String, Any?>>).associate { row ->
            (row["id"] as String) to Camera(curve(row["tone_curve"] as String), profile(row["fidelity"]), profile(row["jpeg_before_tc"]))
        }
        @Suppress("UNCHECKED_CAST")
        films = (root["films"] as List<Map<String, Any?>>).associate { row ->
            require(row["type_bw"] == true && row["runtime_bw"] == true)
            val id = row["id"] as String
            id to RawCameraBwProvider(id, curve(row["curve"] as String), matrix(row["post_matrix"]), row["use_camera_color_matrix"] as Boolean)
        }
        require(cameras.keys == setOf("s1rii", "s9") && films.size == 2)
    }

    fun supports(id: String): Boolean = id in films
    fun supports(cameraId: String, filmId: String): Boolean = cameraId in cameras && filmId in films

    fun bake(cameraId: String, temperature: Float, exposureBiasEv: Float, extraMatrix: FloatArray, filmId: String): RawNegativeProviderCatalog.Bake {
        val camera = requireNotNull(cameras[cameraId])
        return requireNotNull(films[filmId]).bake(exposureBiasEv, extraMatrix, camera.tone,
            camera.jpegBeforeTc.matrixAt(temperature), camera.fidelity.matrixAt(temperature))
    }
}

/** Camera-linear RAW → film, with per-body calibration. Single-threaded use (the app renders serially). */
class RawFilmPipeline internal constructor(private val assets: AssetProvider) {
    private val cameraProfiles = RawCameraProfiles(assets)
    private val runtimeBakes = LinkedHashMap<String, RawNegativeProviderCatalog.Bake>()
    private val rgbContexts = LinkedHashMap<String, RawRgbContext>()
    private val rgbRenderers = LinkedHashMap<String, RawRgbFilmRenderer>()
    private val negativeProviders by lazy { catalog("raw-negative-providers/catalog.cxornp") }
    private val fixedProviders by lazy { catalog("raw-static-providers/catalog.cxornp") }
    private val specialProviders by lazy { catalog("raw-special-providers/catalog.cxornp") }
    private val cameraBw by lazy { RawCameraBwCatalog(assets) }

    private var intentSourceId: String? = null
    private var intentSample: RawColorIntentEstimator.SampledImage? = null
    private var sceneRenderingIntent: Float? = null
    private val sceneIntents = HashMap<String, Float>()

    private fun catalog(path: String) = RawNegativeProviderCatalog.read(ByteArrayInputStream(assets.read(path)))
    private fun readCurve(name: String): FloatArray =
        RawNegativeProviderCatalog.readQ15Curve(ByteArrayInputStream(assets.read("raw-films/curves/$name")))

    fun supportsCamera(model: String, make: String): Boolean = cameraProfiles.forModel(model, make) != null

    private fun providerFor(film: FilmChoice): RawNegativeProviderCatalog.Film? =
        negativeProviders.film(film.id) ?: fixedProviders.film(film.id) ?: specialProviders.film(film.id)

    fun supports(source: RawSource, film: FilmChoice?): Boolean {
        val camera = cameraProfiles.forModel(source.model, source.make) ?: return false
        return film == null || if (cameraBw.supports(film.id)) cameraBw.supports(camera.id, film.id) else providerFor(film) != null
    }

    fun requireSupported(source: RawSource, film: FilmChoice?) {
        if (cameraProfiles.forModel(source.model, source.make) == null)
            throw IllegalStateException("暂不支持这台相机的 RAW：${source.make} ${source.model}（缺少机身标定）")
        if (film != null && !supports(source, film))
            throw IllegalStateException("该相机暂不支持「${film.name}」的 RAW 效果，请更换胶片")
    }

    private fun <K, V> LinkedHashMap<K, V>.bounded(limit: Int, key: K, make: () -> V): V {
        get(key)?.let { remove(key); put(key, it); return it }
        val value = make()
        put(key, value)
        while (size > limit) remove(keys.first())
        return value
    }

    private fun selectIntentSource(source: RawSource) {
        if (intentSourceId != source.path) {
            intentSourceId = source.path; intentSample = null; sceneRenderingIntent = null; sceneIntents.clear()
        }
    }

    private fun sampleFor(source: RawSource, cancelled: () -> Boolean): RawColorIntentEstimator.SampledImage {
        selectIntentSource(source)
        return intentSample ?: run {
            val raw = ShortArray(source.width * 3)
            RawColorIntentEstimator.meanDownsampleQ15(source.width, source.height, { y, normalized ->
                source.readRows(y, 1, raw)
                RawCameraNormalization.render(raw, normalized, source.maximumAfterProcess, source.rawBps)
            }, cancelled).also { intentSample = it }
        }
    }

    private fun colorIntent(source: RawSource, film: FilmChoice, intensity: Int, cancelled: () -> Boolean): Float {
        val camera = requireNotNull(cameraProfiles.forModel(source.model, source.make))
        val provider = requireNotNull(providerFor(film))
        if (!provider.requiresAdditionalColorStage || intensity == 0) return 0f
        val key = "${film.id}:${minOf(intensity, 100)}"
        selectIntentSource(source)
        sceneIntents[key]?.let { return it }
        val temperature = camera.whiteBalance.estimate(source.camMul).temperatureKelvin
        val intents = RawColorIntentEstimator.estimateIntentsSampledQ15(
            sampleFor(source, cancelled).pixels, camera.fidelity.sensorToWorking(temperature), camera.exposureBiasEv - .84f,
            provider.scaleFilterMatrix(intensity), estimateRenderingIntent = sceneRenderingIntent == null, isCancelled = cancelled)
        intents.rendering?.let { sceneRenderingIntent = RawColorIntentEstimator.throughNativePercent(it) }
        return RawColorIntentEstimator.throughNativePercent(requireNotNull(intents.scalesAndFilter)).also { sceneIntents[key] = it }
    }

    private fun renderingIntent(source: RawSource, cancelled: () -> Boolean): Float {
        selectIntentSource(source)
        sceneRenderingIntent?.let { return it }
        val camera = requireNotNull(cameraProfiles.forModel(source.model, source.make))
        val temperature = camera.whiteBalance.estimate(source.camMul).temperatureKelvin
        val intents = RawColorIntentEstimator.estimateIntentsSampledQ15(
            sampleFor(source, cancelled).pixels, camera.fidelity.sensorToWorking(temperature),
            camera.exposureBiasEv - .84f, isCancelled = cancelled)
        return RawColorIntentEstimator.throughNativePercent(requireNotNull(intents.rendering)).also { sceneRenderingIntent = it }
    }

    /** Camera-linear RGB16 rows → wide-gamut film output in 0..1 floats. */
    fun transform(source: RawSource, film: FilmChoice?, intensity: Int, cancelled: () -> Boolean = { false }): (ShortArray, FloatArray, Int, Int) -> Unit {
        require(intensity in 0..200) { "强度必须在 0 到 200 之间" }
        requireSupported(source, film)
        val camera = requireNotNull(cameraProfiles.forModel(source.model, source.make))
        val temperature = camera.whiteBalance.estimate(source.camMul).temperatureKelvin
        val matrix = camera.fidelity.sensorToWorking(temperature)
        val extra = camera.fidelity.engineExtraMatrix()
        val rgbKey = film?.takeIf { it.id in RGB_IDS }?.let { "${camera.id}:${it.id}@rgb:${temperature.toRawBits()}:${camera.exposureBiasEv.toRawBits()}" }
        val rgbContext = rgbKey?.let { key ->
            rgbContexts.bounded(2, key) { requireNotNull(providerFor(film)).rgbContext(temperature, camera.exposureBiasEv, extra, ::readCurve) }
        }
        val bake = film?.takeIf { rgbContext == null }?.let { selected ->
            runtimeBakes.bounded(3, "${camera.id}:${selected.id}@${temperature.toRawBits()}:${camera.exposureBiasEv.toRawBits()}") {
                if (cameraBw.supports(selected.id)) cameraBw.bake(camera.id, temperature, camera.exposureBiasEv, extra, selected.id)
                else requireNotNull(providerFor(selected)).bake(temperature, camera.exposureBiasEv, extra, ::readCurve)
            }
        }
        val runtimeBw = rgbContext?.runtimeBw ?: bake?.runtimeBw ?: false
        val sensor = RawSensorToWorking(matrix, exposureEv = camera.exposureBiasEv,
            highlightControl = if (film != null && !runtimeBw) renderingIntent(source, cancelled) else 0f)
        val rgbRenderer = rgbContext?.let { context ->
            rgbRenderers.bounded(2, requireNotNull(rgbKey)) { RawRgbFilmRenderer(RawRgbGridBaker.bake(context.bakeParameters()), context.runtimeBw) }
        }
        val renderer = bake?.let { prepared ->
            val selected = requireNotNull(film)
            val stage = providerFor(selected)?.let { provider ->
                if (provider.requiresAdditionalColorStage && intensity > 0) provider.colorStage(colorIntent(source, selected, intensity, cancelled), intensity) else null
            }
            prepared.renderer(stage)
        }
        val maximum = source.maximumAfterProcess; val bps = source.rawBps
        var normalized = ShortArray(0); var working = ShortArray(0); var encoded = ShortArray(0)
        return { input, output, _, _ ->
            val n = output.size
            if (normalized.size != n) {
                normalized = ShortArray(n); working = ShortArray(n); encoded = ShortArray(n)
            }
            val raw = if (input.size == n) input else input.copyOf(n)
            RawCameraNormalization.render(raw, normalized, maximum, bps)
            sensor.renderQ15(normalized, working)
            when {
                rgbRenderer != null -> rgbRenderer.renderQ15(working, encoded, intensity)
                renderer != null -> renderer.renderQ15(working, encoded, intensity)
                else -> RawOriginalTone.renderQ15(working, encoded)
            }
            for (i in 0 until n) output[i] = encoded[i].toInt().coerceIn(0, 32767) / 32767f
        }
    }

    companion object {
        private val RGB_IDS = setOf("ColorModeArgentiqueKodakEIR", "ColorModeArgentiqueKodakHIE", "ColorModeCineTealAndPink")
    }
}
