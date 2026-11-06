package dev.cxo.filmpack.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.test.InstrumentationTestCase
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.DataOutputStream
import java.io.File

/** Diagnostic-only product-entry captures for a real imported RAW fixture. */
@Suppress("DEPRECATION")
class RawImportDiagnosticTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext

    fun testCaptureP104ImportPreviewsUsingActualProductEntry() = captureImportPreviews("P1040758", "RW2")

    fun testCaptureNikonImportPreviewsUsingActualProductEntry() = captureImportPreviews("DSC_3271", "NEF")

    fun testCaptureDngImportPreviewsUsingActualProductEntry() = captureImportPreviews("DSC_5180", "dng")

    private fun captureImportPreviews(name: String, extension: String) {
        val fixture = File(context.cacheDir, "raw-fixtures/$name.$extension").also {
            assertTrue("Stage actual RAW fixture $name.$extension via ADB", it.isFile)
        }
        val uri = Uri.fromFile(fixture)
        val directory = File(context.cacheDir, "raw-import-diagnostic").also { assertTrue(it.mkdirs() || it.isDirectory) }
        val originalFile = File(directory, "$name-Original-preview.png")
        val portraFile = File(directory, "$name-Portra400-render.png")
        val eliteFile = File(directory, "$name-KodakEliteColor400-render.png")
        val metadataFile = File(directory, "$name-diagnostic.json")
        val intensity = 100

        PhotoProcessor(context).use { engine ->
            val original = engine.decodePreview(uri, maxEdge = 1280)
            try {
                assertPreviewBounds(original, "Original import preview")
                writePng(originalFile, original)
                captureOriginalBoundary(uri, directory, original, name)

                val portra = engine.films.single { it.token == "KodakPortra400" }
                val elite = engine.films.single { it.token == "KodakEliteColor400" }
                val rendered = linkedMapOf<String, Pair<FilmChoice, File>>(
                    portra.token to (portra to portraFile),
                    elite.token to (elite to eliteFile),
                )
                val dimensions = LinkedHashMap<String, List<Int>>()
                dimensions["Original"] = listOf(original.width, original.height)
                for ((token, pair) in rendered) {
                    val output = engine.render(
                        original, pair.first, {}, { false },
                        intensity = intensity, grain = null, rawUri = uri,
                    )
                    try {
                        // The actual render entry applies both edge and pixel
                        // budgets again; record its returned sampling geometry.
                        assertPreviewBounds(output, "$token render")
                        writePng(pair.second, output)
                        dimensions[token] = listOf(output.width, output.height)
                    } finally {
                        if (output !== original) output.recycle()
                    }
                }

                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val versionCode = if (Build.VERSION.SDK_INT >= 28) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
                }
                val json = JSONObject()
                    .put("fixture", "$name.$extension")
                    .put("entry", "PhotoProcessor.decodePreview + PhotoProcessor.render")
                    .put("maxEdge", 1280)
                    .put("intensity", intensity)
                    .put("grainOff", true)
                    .put("versionCode", versionCode)
                    .put("previews", JSONObject().apply {
                        put("Original", JSONObject().apply {
                            put("token", "Original")
                            put("width", dimensions.getValue("Original")[0])
                            put("height", dimensions.getValue("Original")[1])
                            put("path", originalFile.absolutePath)
                        })
                        for ((token, pair) in rendered) {
                            put(token, JSONObject().apply {
                                put("token", token)
                                put("width", dimensions.getValue(token)[0])
                                put("height", dimensions.getValue(token)[1])
                                put("path", pair.second.absolutePath)
                            })
                        }
                    })
                    .put("selectionNote", "Independent diagnostic renders; does not claim the current UI selection")
                metadataFile.writeText(json.toString(2) + "\n")
                assertTrue(originalFile.isFile && originalFile.length() > 0)
                assertTrue(portraFile.isFile && portraFile.length() > 0)
                assertTrue(eliteFile.isFile && eliteFile.length() > 0)
                assertTrue(metadataFile.isFile && metadataFile.length() > 0)
                Log.i("CXORaw", "raw-import-diagnostic fixture=$name.$extension " +
                    "original=${original.width}x${original.height} " +
                    "portra=${dimensions.getValue("KodakPortra400").joinToString("x")} " +
                    "elite=${dimensions.getValue("KodakEliteColor400").joinToString("x")} " +
                    "intensity=$intensity grainOff=true versionCode=$versionCode " +
                    "metadata=${metadataFile.absolutePath}")
            } finally {
                original.recycle()
            }
        }
    }

    private fun assertPreviewBounds(bitmap: Bitmap, label: String) {
        assertTrue("$label must have positive dimensions", bitmap.width > 0 && bitmap.height > 0)
        assertTrue("$label must preserve the 1280-edge product bound",
            maxOf(bitmap.width, bitmap.height) <= 1280)
        assertTrue("$label must contain image variation", bitmapConfigVariation(bitmap) > 8)
    }

    private fun captureOriginalBoundary(uri: Uri, directory: File, original: Bitmap, name: String) {
        RawPhotoStore(context).use { store ->
            requireNotNull(store.openCameraIfRaw(uri)).use { camera ->
                val rawRow = ShortArray(camera.width * 3)
                val cameraFile = File(directory, "$name-camera-sampled.u16be.bin")
                val workingFile = File(directory, "$name-Original-pre-cmm.f32be.bin")
                DataOutputStream(cameraFile.outputStream().buffered()).use { rawOutput ->
                    DataOutputStream(workingFile.outputStream().buffered()).use { workingOutput ->
                        val replica = RawRenderPipeline(context).renderPreview(camera, null, 1280,
                            2_000_000L, { false }) { row, width, y ->
                            camera.readRows((y.toLong() * camera.height / original.height).toInt(), 1, rawRow)
                            for (x in 0 until width) {
                                val sourceX = (x.toLong() * camera.width / width).toInt()
                                for (channel in 0..2) rawOutput.writeShort(rawRow[sourceX * 3 + channel].toInt())
                            }
                            for (value in row) workingOutput.writeFloat(value)
                        }
                        try {
                            assertTrue("Direct pipeline must reproduce actual decodePreview", replica.sameAs(original))
                        } finally { replica.recycle() }
                    }
                }
                val json = JSONObject().put("model", camera.model).put("make", camera.make).put("width", camera.width)
                    .put("height", camera.height).put("orientation", camera.orientation)
                    .put("rawBps", camera.rawBps).put("black", camera.black)
                    .put("maximumAfterProcess", camera.maximumAfterProcess)
                    .put("dataMaximumAfterProcess", camera.dataMaximumAfterProcess)
                    .put("camMul", JSONArray(camera.camMul.toList())).put("preMul", JSONArray(camera.preMul.toList()))
                    .put("sampleWidth", original.width).put("sampleHeight", original.height)
                    .put("sampling", "sourceX=floor(x*sourceWidth/outputWidth); same formula for Y")
                    .put("cameraCarrier", "unsigned RGB16 big endian").put("beforeCmm", "encoded working RGB Float32 big endian")
                    .put("actualDecodePreviewEqualsDirectPipeline", true)
                File(directory, "$name-camera-boundary.json").writeText(json.toString(2) + "\n")
            }
        }
    }

    private fun bitmapConfigVariation(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.toSet().size
    }

    private fun writePng(file: File, bitmap: Bitmap) {
        file.parentFile?.mkdirs()
        file.outputStream().use { output ->
            assertTrue("PNG encode failed: ${file.absolutePath}",
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}
