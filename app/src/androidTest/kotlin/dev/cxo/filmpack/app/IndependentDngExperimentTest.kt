package dev.cxo.filmpack.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject
import java.io.File

/** Explicit experiment only; this path is not wired to normal RAW import. */
@Suppress("DEPRECATION")
class IndependentDngExperimentTest : ForegroundEditorTest() {
    fun testSdkRenderedSrgbThroughRgbFilm() {
        val args = (instrumentation as android.test.InstrumentationTestRunner).arguments
        val name = args.getString("independentSdkSrgbFixture") ?: return
        require(name.matches(Regex("[A-Za-z0-9._-]+")) && !name.startsWith("."))
        activity
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, name)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "cat /data/local/tmp/$name")).use { input -> source.outputStream().use { input.copyTo(it) } }
        check(source.length() > 1024)
        val uri = Uri.fromFile(source)
        val out = File(context.getExternalFilesDir(null), "independent-dng-sdk-experiment").also { it.mkdirs() }
        val report = JSONObject().put("fixture", name)
            .put("path", "Pre-rendered Adobe DNG SDK sRGB; explicit JPEG100 transport; Android RGB film pipeline")
        PhotoProcessor(context).use { engine ->
            val base = engine.decodePreview(uri, 1280)
            try {
                val film = engine.films.single { it.token == "KodakPortra400" }
                val preview = engine.render(base, film, {}, { false })
                try {
                    File(out, "sdk-portra400-preview.png").outputStream().use {
                        check(preview.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { if (preview !== base) preview.recycle() }
                for (format in ExportFormat.entries) {
                    val destination = File(out, "sdk-portra400.${format.extension}")
                    val result = engine.export(uri, film, Uri.fromFile(destination), {}, { false },
                        settings = ExportSettings(format = format))
                    check(result.width == 3072 && result.height == 4096)
                    report.put("export${format.name}", JSONObject()
                        .put("bytes", destination.length()).put("width", result.width)
                        .put("height", result.height).put("elapsedMs", result.elapsedMillis))
                }
            } finally { base.recycle() }
        }
        source.delete()
        File(out, "result.json").writeText(report.toString(2) + "\n")
        instrumentation.sendStatus(2, Bundle().apply { putString("cxoIndependentSdkSrgb", report.toString()) })
    }

    fun testLibRawSrgbDevelopThenRgbFilm() {
        val args = (instrumentation as android.test.InstrumentationTestRunner).arguments
        val name = args.getString("independentDngFixture") ?: return
        require(name.matches(Regex("[A-Za-z0-9._-]+")) && !name.startsWith("."))
        activity
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, name)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "cat /data/local/tmp/$name")).use { input -> source.outputStream().use { input.copyTo(it) } }
        check(source.length() > 1024) { "Stage fixture in /data/local/tmp" }
        val uri = Uri.fromFile(source)
        val out = File(context.getExternalFilesDir(null), "independent-dng-experiment").also { it.mkdirs() }
        val started = android.os.SystemClock.elapsedRealtime()
        val report = JSONObject().put("fixture", name)
            .put("path", "LibRaw camera-WB basic develop to sRGB; standard RGB film pipeline; not DxO calibrated")
        RawPhotoStore(context).use { store ->
            val raw = requireNotNull(store.openIfRaw(uri)) { "LibRaw did not identify the DNG as RAW" }
            raw.use {
                val base = raw.preview(1280, 2_000_000L)
                try {
                    val baseFile = File(out, "IMG20261105173001-libraw-independent-original.png")
                    baseFile.outputStream().use { check(base.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    PhotoProcessor(context).use { engine ->
                        val film = engine.films.single { it.token == "KodakPortra400" }
                        val rendered = engine.render(base, film, {}, { false }, intensity = 100)
                        try {
                            val filmFile = File(out, "IMG20261105173001-libraw-independent-portra400.png")
                            filmFile.outputStream().use { check(rendered.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                            report.put("model", raw.model).put("rawBps", raw.rawBps)
                                .put("rawWidth", raw.width).put("rawHeight", raw.height)
                                .put("displayWidth", base.width).put("displayHeight", base.height)
                                .put("film", film.token).put("intensity", 100)
                                .put("grain", false).put("base", baseFile.absolutePath)
                                .put("filmPreview", filmFile.absolutePath)
                                .put("pixelCount", base.width * base.height)
                        } finally { if (rendered !== base) rendered.recycle() }
                        // Full-size transport is deliberately JPEG100 in this isolated
                        // prototype. Do not describe it as a production RGB16 pipeline.
                        val developed = File(out, "independent-developed-srgb.jpg")
                        val rgb = ShortArray(raw.width * 8 * 3)
                        developed.outputStream().use { stream ->
                            ExportImageWriter.write(stream, raw.width, raw.height, raw.orientation,
                                ExportSettings(jpegQuality = 100)) { top, count, pixels ->
                                raw.readRows(top, count, rgb)
                                for (i in 0 until raw.width * count) {
                                    val j = i * 3
                                    pixels[i] = android.graphics.Color.rgb(
                                        (rgb[j].toInt() and 65535) ushr 8,
                                        (rgb[j + 1].toInt() and 65535) ushr 8,
                                        (rgb[j + 2].toInt() and 65535) ushr 8)
                                }
                            }
                        }
                        for (format in ExportFormat.entries) {
                            val destination = File(out, "independent-portra400.${format.extension}")
                            val result = engine.export(Uri.fromFile(developed), film,
                                Uri.fromFile(destination), {}, { false },
                                settings = ExportSettings(format = format))
                            check(result.width == raw.displayWidth && result.height == raw.displayHeight)
                            report.put("export${format.name}", JSONObject()
                                .put("path", destination.absolutePath).put("bytes", destination.length())
                                .put("width", result.width).put("height", result.height)
                                .put("elapsedMs", result.elapsedMillis))
                        }
                        report.put("transport", "Experimental JPEG100 intermediate, 8-bit sRGB")
                    }
                } finally { base.recycle() }
            }
        }
        source.delete()
        report.put("totalMs", android.os.SystemClock.elapsedRealtime() - started)
        File(out, "result.json").writeText(report.toString(2) + "\n")
        instrumentation.sendStatus(2, Bundle().apply { putString("cxoIndependentDng", report.toString()) })
    }
}
