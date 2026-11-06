package dev.cxo.filmpack.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.test.InstrumentationTestCase
import dev.cxo.filmpack.android.ExifOrientation
import dev.cxo.filmpack.app.post.*
import dev.cxo.filmpack.core.FixedWideGamutToSrgb
import dev.cxo.filmpack.core.NativeGrain8Geometry
import dev.cxo.filmpack.core.PostProcessSettings
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Real product integration. Sources and destinations are app-owned local files;
 * no test content provider or external storage grant is involved. The S9 method
 * is deliberately separate and requires the staged actual camera fixture. */
@Suppress("DEPRECATION")
class ExportPipelineTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private fun output(name: String) = File(context.cacheDir, "export-pipeline-$name")
    private fun fixture(name: String): File = output(name).also { target ->
        instrumentation.context.assets.open(name).use { input -> target.outputStream().use(input::copyTo) }
    }
    private fun post() = PostEditingSettings(
        PostProcessSettings(exposureEv = .35f, contrast = 22, saturation = -13),
        EdgeDefectSettings("fuitelum1", 63, EdgePosition.LEFT, 72),
    )

    fun testPngGrainPostTiffAndFractionalReductionMatchFullRender() {
        val sourceFile = output("source.png")
        val fullFile = output("full.tif")
        val smallFile = output("small.tif")
        // An odd 347x229 grid exercises source and output partial eight-row batches.
        val source = Bitmap.createBitmap(347, 229, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(source.width * source.height) { i ->
            val x = i % source.width; val y = i / source.width
            (255 shl 24) or (((x * 7 + y * 3) and 255) shl 16) or
                (((x * 2 + y * 5) and 255) shl 8) or ((x * 11 + y) and 255)
        }
        source.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        sourceFile.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        try {
            PhotoProcessor(context).use { engine ->
                val film = engine.films.first { it.token == "KodakPortra400" }
                GrainPackStore(context).use { store ->
                    val preview = PhotoGrainPreview(source, source.width, source.height, 0)
                    val request = engine.grainRequest(preview, film, "KodakPortra400",
                        NativeGrain8Geometry.Format.MANUAL, 1f, 100, 17, fullResolution = true)
                    val layer = store.prepare(request)
                    val expected = engine.render(source, film, {}, { false }, grain = layer, post = post())
                    val withoutGrain = engine.render(source, film, {}, { false }, post = post())
                    val withoutPost = engine.render(source, film, {}, { false }, grain = layer)
                    try {
                        assertFalse("Real grain must affect this fixture", expected.sameAs(withoutGrain))
                        assertFalse("Post settings must affect this fixture", expected.sameAs(withoutPost))
                        val full = engine.export(Uri.fromFile(sourceFile), film, Uri.fromFile(fullFile), {}, { false },
                            grain = layer, post = post(), settings = ExportSettings(ExportFormat.TIFF))
                        assertEquals(source.width, full.width); assertEquals(source.height, full.height)
                        Tiff(fullFile).use { tiff ->
                            assertEquals(1, tiff.orientation)
                            val row = IntArray(tiff.width)
                            for (y in 0 until tiff.height) {
                                tiff.readRow(y, row)
                                for (x in row.indices) assertRgbNear(expected.getPixel(x, y), row[x], 1, "render $x,$y")
                            }
                        }
                        val settings = ExportSettings(ExportFormat.TIFF, maxLongEdge = 173)
                        val result = engine.export(Uri.fromFile(sourceFile), film, Uri.fromFile(smallFile), {}, { false },
                            grain = layer, post = post(), settings = settings)
                        assertEquals(173 to 114, result.width to result.height)
                        compareArea(fullFile, smallFile)
                    } finally { expected.recycle(); withoutGrain.recycle(); withoutPost.recycle() }
                }
            }
        } finally { source.recycle(); sourceFile.delete(); fullFile.delete(); smallFile.delete() }
    }

    fun testOrientationSixReducedJpegAndTiffKeepDisplayGeometryAndMetadata() {
        val source = fixture("orientation6.jpg")
        try {
            PhotoProcessor(context).use { engine ->
                val film = engine.films.first { it.token == "KodakPortra400" }
                for (format in ExportFormat.entries) {
                    val target = output("orientation.${format.extension}")
                    try {
                        val result = engine.export(Uri.fromFile(source), film, Uri.fromFile(target), {}, { false },
                            post = post(), settings = ExportSettings(format, maxLongEdge = 37))
                        assertEquals(19 to 37, result.width to result.height)
                        if (format == ExportFormat.TIFF) {
                            Tiff(target).use { tiff ->
                                assertEquals(37, tiff.width); assertEquals(19, tiff.height)
                                assertEquals(6, tiff.orientation)
                                assertEquals(result.width to result.height, tiff.displayDimensions)
                            }
                        } else {
                            target.inputStream().use { assertEquals(6, ExifOrientation.readJpeg(it)) }
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(target.path, bounds)
                            assertEquals(37, bounds.outWidth); assertEquals(19, bounds.outHeight)
                            val shown = engine.decodePreview(Uri.fromFile(target))
                            try { assertEquals(result.width, shown.width); assertEquals(result.height, shown.height) }
                            finally { shown.recycle() }
                        }
                    } finally { target.delete() }
                }
            }
        } finally { source.delete() }
    }

    fun testMidExportCancellationDeletesPartialLocalDestination() {
        val source = fixture("photo-mosaic.png")
        try {
            PhotoProcessor(context).use { engine ->
                val film = engine.films.first { it.token == "KodakPortra400" }
                for (format in ExportFormat.entries) {
                    val target = output("cancel.${format.extension}")
                    var cancel = false; var reachedRows = false; var hadDestination = false
                    try {
                        engine.export(Uri.fromFile(source), film, Uri.fromFile(target), { progress ->
                            if (progress in 10..98) {
                                reachedRows = true; hadDestination = target.exists(); cancel = true
                            }
                        }, { cancel }, settings = ExportSettings(format, maxLongEdge = 173))
                        fail("Cancellation must propagate")
                    } catch (_: CancellationException) {
                        assertTrue("Cancel after processing source rows", reachedRows)
                        assertTrue("Destination was opened before cancellation", hadDestination)
                        assertFalse("Partial destination must be deleted", target.exists())
                    } finally { target.delete() }
                }
            }
        } finally { source.delete() }
    }

    /** Slow opt-in method: run explicitly on the emulator with P1035087.RW2
     * staged in target cache/raw-fixtures. Performs two full source-size renders,
     * checks full TIFF against RAW camera+film+CMM rows, then independently area
     * averages that full TIFF to verify the 173px export. Never uses a RAW preview. */
    fun testS9RawReducedTiffMatchesFullCameraPipelineAndAreaReference() {
        val source = File(context.cacheDir, "raw-fixtures/P1035087.RW2")
        assertTrue("Stage actual S9 RAW in app cache/raw-fixtures", source.isFile)
        val full = output("s9-full.tif"); val small = output("s9-173.tif")
        val uri = Uri.fromFile(source)
        try {
            PhotoProcessor(context).use { engine ->
                val film = engine.films.first { it.token == "KodakPortra400" }
                val settings = PostEditingSettings(PostProcessSettings(exposureEv = .2f, contrast = 15))
                val result = engine.export(uri, film, Uri.fromFile(full), {}, { false }, post = settings,
                    settings = ExportSettings(ExportFormat.TIFF))
                RawPhotoStore(context).use { store ->
                    requireNotNull(store.openCameraIfRaw(uri)).use { camera ->
                        assertEquals("DC-S9", camera.model)
                        assertEquals(camera.displayWidth to camera.displayHeight, result.width to result.height)
                        val pipeline = RawRenderPipeline(context)
                        assertTrue("Actual camera provider must support selected film", pipeline.supports(camera, film))
                        val apply = pipeline.prepareRows(camera, film, 100) { false }
                        val cmm = FixedWideGamutToSrgb.read(context.assets.open("films/output-cmm/widegamut-to-srgb.lcmsbin").use { it.readBytes() })
                        val input = ShortArray(camera.width * 3)
                        val normalized = ShortArray(input.size); val working = ShortArray(input.size); val encoded = ShortArray(input.size)
                        val floats = FloatArray(input.size); val expected = IntArray(camera.width); val actual = IntArray(camera.width)
                        val postRenderer = PostEditingRenderer(context, settings, camera.width, camera.height, camera.orientation)
                        Tiff(full).use { tiff ->
                            assertEquals(camera.width, tiff.width); assertEquals(camera.height, tiff.height)
                            assertEquals(camera.orientation, tiff.orientation)
                            for (y in listOf(camera.height / 5, camera.height / 2, camera.height * 4 / 5)) {
                                camera.readRows(y, 1, input); apply(input, normalized, working, encoded)
                                for (i in floats.indices) floats[i] = encoded[i].toInt().coerceIn(0, 32767) / 32767f
                                cmm.render(floats, floats)
                                for (x in expected.indices) {
                                    fun channel(c: Int) = (floats[x * 3 + c].coerceIn(0f, 1f) * 255f).roundToInt()
                                    expected[x] = (255 shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
                                }
                                postRenderer.apply(expected, y); tiff.readRow(y, actual)
                                for (x in actual.indices step 19) assertRgbNear(expected[x], actual[x], 1, "RAW $x,$y")
                            }
                        }
                    }
                }
                val reduced = engine.export(uri, film, Uri.fromFile(small), {}, { false }, post = settings,
                    settings = ExportSettings(ExportFormat.TIFF, maxLongEdge = 173))
                assertEquals(173, max(reduced.width, reduced.height))
                Tiff(small).use { assertEquals(reduced.width to reduced.height, it.displayDimensions) }
                compareArea(full, small)
                android.util.Log.i("CXOExportPipeline", "S9 RAW TIFF full=${result.width}x${result.height} small=${reduced.width}x${reduced.height} fullMs=${result.elapsedMillis} smallMs=${reduced.elapsedMillis}")
            }
        } finally { full.delete(); small.delete() }
    }

    /** Independent floating-point area reference; it reads only source rows and
     * never calls ExportImageWriter or its resizer. Bounded by source row width. */
    private fun compareArea(fullFile: File, smallFile: File) {
        Tiff(fullFile).use { full -> Tiff(smallFile).use { small ->
            assertEquals(full.orientation, small.orientation)
            val source = IntArray(full.width); val actual = IntArray(small.width)
            val sums = DoubleArray(small.width * 3)
            for (y in 0 until small.height) {
                sums.fill(0.0)
                val top = y.toDouble() * full.height / small.height
                val bottom = (y + 1.0) * full.height / small.height
                for (sy in top.toInt() until kotlin.math.ceil(bottom).toInt().coerceAtMost(full.height)) {
                    full.readRow(sy, source)
                    val wy = min(bottom, sy + 1.0) - max(top, sy.toDouble())
                    for (x in 0 until small.width) {
                        val left = x.toDouble() * full.width / small.width
                        val right = (x + 1.0) * full.width / small.width
                        for (sx in left.toInt() until kotlin.math.ceil(right).toInt().coerceAtMost(full.width)) {
                            val weight = wy * (min(right, sx + 1.0) - max(left, sx.toDouble()))
                            for (c in 0..2) sums[x * 3 + c] += ((source[sx] ushr (16 - c * 8)) and 255) * weight
                        }
                    }
                }
                small.readRow(y, actual)
                val area = full.width.toDouble() / small.width * full.height / small.height
                for (x in actual.indices) for (c in 0..2) {
                    val expected = (sums[x * 3 + c] / area + .5).toInt()
                    val observed = (actual[x] ushr (16 - c * 8)) and 255
                    assertTrue("Area $x,$y channel=$c expected=$expected actual=$observed", abs(expected - observed) <= 1)
                }
            }
        } }
    }

    private fun assertRgbNear(expected: Int, actual: Int, tolerance: Int, label: String) {
        for (shift in intArrayOf(0, 8, 16)) assertTrue("$label shift=$shift",
            abs(((expected ushr shift) and 255) - ((actual ushr shift) and 255)) <= tolerance)
    }

    /** Independent baseline TIFF reader, including true stored RGB8 readback.
     * Seeks the test file only; production writer still accepts arbitrary streams. */
    private class Tiff(file: File) : java.io.Closeable {
        private val input = RandomAccessFile(file, "r")
        private val tags = HashMap<Int, Pair<Long, Long>>()
        val width: Int; val height: Int; val orientation: Int
        private val pixels: Long
        private val rowBytes: ByteArray
        val displayDimensions get() = if (orientation >= 5) height to width else width to height
        init {
            check(input.readUnsignedByte() == 73 && input.readUnsignedByte() == 73 && short() == 42)
            input.seek(long())
            repeat(short()) {
                val tag = short(); short(); val count = long(); val value = long()
                tags[tag] = count to value
            }
            width = value(256).toInt(); height = value(257).toInt(); orientation = value(274).toInt()
            check(value(259) == 1L && value(262) == 2L && value(277) == 3L)
            input.seek(value(258)); repeat(3) { check(short() == 8) }
            pixels = value(273); check(value(279) == width.toLong() * height * 3)
            check(pixels + value(279) == input.length())
            check(requireNotNull(tags[34675]).first > 128)
            input.seek(value(34675) + 36); val signature = ByteArray(4); input.readFully(signature)
            check(String(signature, Charsets.US_ASCII) == "acsp")
            rowBytes = ByteArray(width * 3)
        }
        private fun value(tag: Int) = requireNotNull(tags[tag]).second
        private fun short(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
        private fun long(): Long = short().toLong() or (short().toLong() shl 16)
        fun readRow(y: Int, destination: IntArray) {
            input.seek(pixels + y.toLong() * rowBytes.size); input.readFully(rowBytes)
            for (x in 0 until width) destination[x] = (255 shl 24) or
                ((rowBytes[x * 3].toInt() and 255) shl 16) or
                ((rowBytes[x * 3 + 1].toInt() and 255) shl 8) or (rowBytes[x * 3 + 2].toInt() and 255)
        }
        override fun close() = input.close()
    }
}
