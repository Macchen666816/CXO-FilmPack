package dev.cxo.filmpack.app

import android.net.Uri
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Log
import dev.cxo.filmpack.core.FixedWideGamutToSrgb
import dev.cxo.filmpack.core.NativeGrain8Geometry
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Actual user-supplied camera files are staged by ADB, never packaged in the APK. */
@Suppress("DEPRECATION")
class RawPipelineTest : InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private fun fixture(name: String)=File(context.cacheDir,"raw-fixtures/$name.RW2").also {
        assertTrue("Stage actual RW2 fixture $name via ADB",it.isFile)
    }

    fun testBothCamerasAllSamplesPrecisionCropOrientationAndCache() {
        RawPhotoStore(context).use { store ->
            for (name in listOf("P1040758","P1107056","P1176256","P1176257","P1055542","P1066283","P1035087")) {
                val uri=Uri.fromFile(fixture(name)); val start=SystemClock.elapsedRealtime()
                requireNotNull(store.openIfRaw(uri)).use { source ->
                    val s9=name=="P1035087"
                    assertEquals(if(s9) "DC-S9" else "DC-S1RM2",source.model)
                    assertEquals(if(s9) 12 else 14,source.rawBps)
                    assertEquals(if(s9) 6000 else 8144,source.width)
                    assertEquals(if(s9) 4000 else 5424,source.height)
                    assertEquals(if(name in listOf("P1055542","P1066283")) 8 else 1,source.orientation)
                    val row=ShortArray(source.width*3);source.readRows(source.height/2,1,row)
                    assertTrue("RGB16 must retain more than 8-bit expanded samples",row.count { (it.toInt() and 65535)%257!=0 }>row.size/2)
                    val preview=source.preview(1280,2_000_000)
                    assertTrue(maxOf(preview.width,preview.height)<=1280)
                    assertEquals(source.orientation>=5,preview.height>preview.width)
                    preview.recycle()
                    Log.i("CXORaw","$name ${source.model} ${source.rawBps}bit ${source.width}x${source.height} EXIF${source.orientation} importPreviewMs=${SystemClock.elapsedRealtime()-start}")
                }
                // A new open after use/close must return an independent live reader.
                val tick=SystemClock.elapsedRealtime()
                requireNotNull(store.openIfRaw(uri)).use { it.readRows(0,1,ShortArray(it.width*3)) }
                Log.i("CXORaw","$name cachedReopenMs=${SystemClock.elapsedRealtime()-tick}")
                try { store.openIfRaw(uri) { true }; fail("Cancelled cache hit must stop") } catch(_:CancellationException) { }
            }
        }
    }

    fun testThreeFilmBranchesOriginalSizeAndRgb16OutputReference() {
        PhotoProcessor(context).use { engine ->
            val cmm=FixedWideGamutToSrgb.read(context.assets.open("films/output-cmm/widegamut-to-srgb.lcmsbin").use { it.readBytes() })
            for((name,token) in listOf("P1035087" to "KodakPortra400","P1176257" to "DigitalFujiMonochromeRedFilter","P1055542" to "KodakEIR")) {
                val uri=Uri.fromFile(fixture(name))
                val film=engine.films.first { it.token==token }
                val preview=engine.decodePreview(uri); preview.recycle()
                val output=File(context.cacheDir,"raw-$name-$token.jpg")
                val result=engine.export(uri,film,Uri.fromFile(output),{}, { false })
                assertFalse(result.resized)
                RawPhotoStore(context).use { referenceStore ->
                    requireNotNull(referenceStore.openIfRaw(uri)).use { raw ->
                        assertEquals(raw.displayWidth,result.width);assertEquals(raw.displayHeight,result.height)
                        requireNotNull(RegionSrgbPhoto.open(context,Uri.fromFile(output))).use { jpeg ->
                            assertEquals(raw.width,jpeg.width);assertEquals(raw.height,jpeg.height)
                            assertEquals(raw.orientation,jpeg.orientation)
                            val src=ShortArray(raw.width*3);val dst=FloatArray(src.size);val actual=IntArray(raw.width)
                            val apply=engine.transform(film,100)
                            val pipeline=RawRenderPipeline(context)
                            // Reference the same color domain as the product export.
                            // S1RII supported films no longer use the sRGB RAW bridge.
                            referenceStore.openCameraIfRaw(uri).use { camera ->
                                val cameraRoute=camera != null && pipeline.supports(camera,film)
                                val normalized=ShortArray(src.size);val working=ShortArray(src.size);val encoded=ShortArray(src.size)
                                var squared=0.0;var count=0L;var maxError=0
                                for(y in listOf(raw.height/8,raw.height/3,raw.height/2,raw.height*2/3,raw.height*7/8)) {
                                    if(cameraRoute) {
                                        requireNotNull(camera).readRows(y,1,src)
                                        pipeline.renderRows(camera,film,src,normalized,working,encoded) { false }
                                        for(i in dst.indices) dst[i]=encoded[i].toInt()/32767f
                                    } else {
                                        raw.readRows(y,1,src);apply(src,dst,raw.width,0)
                                    }
                                    cmm.render(dst,dst)
                                    jpeg.readRows(y,1,actual)
                                    for(x in 0 until raw.width step 13) for(c in 0..2) {
                                        val expected=(dst[x*3+c].coerceIn(0f,1f)*255).roundToInt()
                                        val got=(actual[x] ushr (16-c*8)) and 255
                                        val d=abs(got-expected);squared+=d*d;count++;maxError=maxOf(maxError,d)
                                    }
                                }
                                val rmse=sqrt(squared/count)
                                Log.i("CXORaw","$name $token ${result.width}x${result.height} exportMs=${result.elapsedMillis} bytes=${output.length()} referenceRgb16Rmse=$rmse max=$maxError")
                                assertTrue("RAW16 reference differs from exported JPEG: $rmse",rmse<4.0)
                            }
                        }
                    }
                }
            }
        }
    }

    fun testNativeCancellationMalformedInputAndRecovery() {
        val output=File(context.cacheDir,"raw-native-cancel.rgb16le")
        val source=fixture("P1176257")
        val tick=SystemClock.elapsedRealtime()
        try {
            NativeRawBridge.decode(source.path,output.path,RawCancellationProbe { SystemClock.elapsedRealtime()-tick>=100 })
            fail("Long native work should observe cancellation")
        } catch(_:CancellationException) { }
        assertFalse(output.exists())
        val malformed=File(context.cacheDir,"invalid-rw2.RW2").apply { writeBytes(byteArrayOf(0x49,0x49,0x55,0,0,0,0,0)) }
        RawPhotoStore(context).use { store ->
            try { store.openIfRaw(Uri.fromFile(malformed));fail("Broken RW2 must fail") } catch(_:java.io.IOException) { }
            requireNotNull(store.openIfRaw(Uri.fromFile(fixture("P1035087")))).use { assertEquals(6000,it.width) }
        }
    }

    /** Exercises the generic shared-HSV camera-linear branch rather than the
     * captured Elite fixture or the legacy sRGB RAW fallback. */
    fun testS1RiiSharedHsvRuntimeBakeExport() {
        PhotoProcessor(context).use { engine ->
            for ((name, token) in listOf(
                "P1176257" to "KodakTriX400",
                "P1176257" to "KodakPortra400",
                "P1176257" to "FujiSuperia200E6",
                "P1176256" to "FujiSuperia200E6",
                "P1107056" to "KodakTriX400",
                "P1055542" to "KodakEktar100",
                "P1066283" to "FujiAstia100",
                // Dynamic-direct JPEG and RAW routes intentionally share the
                // UI identity; this exercises the new S1RII Astia RAW bake.
                "P1176257" to "Astia",
                "P1176257" to "Provia",
                "P1176257" to "Velvia",
                "P1176257" to "Ekta100VS",
                "P1176257" to "Kod64",
                "P1176257" to "KodakEIR",
                "P1176257" to "KodakHIE",
                "P1176257" to "CineTealAndPink",
                "P1176257" to "KodakHIEFiltre",
                "P1176257" to "RolleiIR400",
            )) {
                val source = fixture(name)
                val film = engine.films.first { it.token == token }
                val output = File(context.cacheDir, "raw-runtime-$name-$token.jpg")
                val result = engine.export(Uri.fromFile(source), film, Uri.fromFile(output), {}, { false })
                assertFalse(result.resized)
                assertTrue(output.isFile && output.length() > 0)
                Log.i("CXORaw", "runtime-bake $name $token ${result.width}x${result.height} exportMs=${result.elapsedMillis}")
            }
        }
    }

    fun testControlledRawCatalogIncludesAllStaticProviders() {
        PhotoProcessor(context).use { engine ->
            RawPhotoStore(context).use { store ->
                val pipeline = RawRenderPipeline(context)
                requireNotNull(store.openCameraIfRaw(Uri.fromFile(fixture("P1040758")))).use { camera ->
                    val controlled = engine.films.filter { pipeline.supports(camera, it) }
                    assertEquals("EV0/provider/RGB plus all 68 static negative providers", 153, controlled.size)
                    assertTrue(controlled.any { it.token == "KodakTriX400" })
                    assertTrue(controlled.any { it.token == "FujiSuperia200E6" })
                    assertTrue(controlled.any { it.token == "Astia" })
                    assertTrue(pipeline.supports(camera, engine.films.single { it.token == "FujiClassicChrome" }))
                    assertTrue(controlled.any { it.token == "DigitalFujiMonochromeRedFilter" })
                    assertTrue(pipeline.supports(camera, engine.films.single { it.token == "NegativeAmberT200" }))
                    for (token in listOf("KodakEIR", "KodakHIE", "CineTealAndPink", "KodakHIEFiltre", "RolleiIR400")) {
                        assertTrue(pipeline.supports(camera, engine.films.single { it.token == token }))
                    }
                }
            }
        }
    }

    /** Checks our exported JPEG against the same pipeline plus output CMM;
     * this is export consistency, not original-DxO photograph parity. */
    fun testAmberT200NativeSizeExportAndPipelineCmmReference() {
        PhotoProcessor(context).use { engine ->
            val uri = Uri.fromFile(fixture("P1176257"))
            val film = engine.films.single { it.token == "NegativeAmberT200" }
            val output = File(context.cacheDir, "raw-P1176257-NegativeAmberT200-stage.jpg")
            val result = engine.export(uri, film, Uri.fromFile(output), {}, { false })
            assertFalse(result.resized)
            assertTrue(output.isFile && output.length() > 0)
            val cmm = FixedWideGamutToSrgb.read(context.assets.open("films/output-cmm/widegamut-to-srgb.lcmsbin").use { it.readBytes() })
            RawPhotoStore(context).use { store ->
                requireNotNull(store.openCameraIfRaw(uri)).use { camera ->
                    assertEquals(8144, camera.width); assertEquals(5424, camera.height)
                    assertEquals(camera.displayWidth, result.width); assertEquals(camera.displayHeight, result.height)
                    val pipeline = RawRenderPipeline(context)
                    assertTrue(pipeline.supports(camera, film))
                    val intent = pipeline.colorIntent(camera, film) { false }
                    assertTrue(intent.isFinite() && intent in 0f..1f)
                    requireNotNull(RegionSrgbPhoto.open(context, Uri.fromFile(output))).use { jpeg ->
                        assertEquals(camera.width, jpeg.width); assertEquals(camera.height, jpeg.height)
                        assertEquals(camera.orientation, jpeg.orientation)
                        val source = ShortArray(camera.width * 3)
                        val normalized = ShortArray(source.size); val working = ShortArray(source.size)
                        val encoded = ShortArray(source.size); val reference = FloatArray(source.size)
                        val pixels = IntArray(camera.width)
                        var squared = 0.0; var count = 0L; var maxError = 0
                        for (y in listOf(camera.height / 8, camera.height / 3, camera.height / 2,
                            camera.height * 2 / 3, camera.height * 7 / 8)) {
                            camera.readRows(y, 1, source)
                            pipeline.renderRows(camera, film, source, normalized, working, encoded) { false }
                            for (i in reference.indices) reference[i] = encoded[i].toInt() / 32767f
                            cmm.render(reference, reference)
                            jpeg.readRows(y, 1, pixels)
                            for (x in 0 until camera.width step 13) for (c in 0..2) {
                                val expected = (reference[x * 3 + c].coerceIn(0f, 1f) * 255f).roundToInt()
                                val actual = (pixels[x] ushr (16 - c * 8)) and 255
                                val error = abs(actual - expected)
                                squared += error * error; count++; maxError = maxOf(maxError, error)
                            }
                        }
                        val rmse = sqrt(squared / count)
                        Log.i("CXORaw", "scale-filter-export P1176257 ${film.token} ${result.width}x${result.height} exportMs=${result.elapsedMillis} bytes=${output.length()} sceneIntent=$intent pipelineCmmJpegRmse=$rmse max=$maxError samples=$count originalDxoPhotoParity=false")
                        assertTrue("Pipeline plus CMM differs from exported JPEG: $rmse", rmse.isFinite() && rmse < 4.0)
                    }
                }
            }
        }
    }

    fun testDynamicDirectRawPreviewAtBothCapturedWbAnchors() {
        PhotoProcessor(context).use { engine ->
            RawPhotoStore(context).use { store ->
                val pipeline=RawRenderPipeline(context)
                for(name in listOf("P1040758","P1176257")) {
                    requireNotNull(store.openCameraIfRaw(Uri.fromFile(fixture(name)))).use { camera ->
                        for(token in listOf("Astia","Provia","Velvia","Ekta100VS","Kod64")) {
                            val film=engine.films.first { it.token==token }
                            assertTrue("$name/$token must select camera-linear RAW",pipeline.supports(camera,film))
                            val preview=pipeline.renderPreview(camera,film,256,65_536,cancelled = { false })
                            try {
                                assertTrue(preview.width in 1..256 && preview.height in 1..256)
                                val pixels=IntArray(preview.width*preview.height)
                                preview.getPixels(pixels,0,preview.width,0,0,preview.width,preview.height)
                                assertTrue("$name/$token preview must contain image variation",pixels.toSet().size>16)
                            } finally { preview.recycle() }
                        }
                    }
                }
            }
        }
    }

    fun testRgbAndProviderBwRawPreviewAtBothCapturedWbAnchors() {
        PhotoProcessor(context).use { engine ->
            RawPhotoStore(context).use { store ->
                val pipeline = RawRenderPipeline(context)
                for (name in listOf("P1040758", "P1176257")) {
                    requireNotNull(store.openCameraIfRaw(Uri.fromFile(fixture(name)))).use { camera ->
                        for (token in listOf("KodakEIR", "KodakHIE", "CineTealAndPink", "KodakHIEFiltre", "RolleiIR400")) {
                            val film = engine.films.single { it.token == token }
                            assertTrue(pipeline.supports(camera, film))
                            val preview = pipeline.renderPreview(camera, film, 128, 16_384, cancelled = { false })
                            try {
                                val pixels = IntArray(preview.width * preview.height)
                                preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
                                assertTrue("$name/$token preview needs image variation", pixels.toSet().size > 16)
                            } finally { preview.recycle() }
                            // Each export row batch must reuse the same RGB bake and
                            // remain deterministic across repeated invocations.
                            val row = ShortArray(camera.width * 3)
                            camera.readRows(camera.height / 2, 1, row)
                            val normalized = ShortArray(row.size); val working = ShortArray(row.size)
                            val first = ShortArray(row.size); val second = ShortArray(row.size)
                            pipeline.renderRows(camera, film, row, normalized, working, first) { false }
                            pipeline.renderRows(camera, film, row, normalized, working, second) { false }
                            assertTrue("$name/$token repeated row mismatch", first.contentEquals(second))
                            if (token in listOf("KodakHIE", "KodakHIEFiltre", "RolleiIR400")) {
                                assertTrue("$name/$token must be gray before output CMM",
                                    (first.indices step 3).all { first[it] == first[it + 1] && first[it] == first[it + 2] })
                            }
                        }
                    }
                }
            }
        }
    }

    fun testRawGrainPreviewFullExportAndCancellation() {
        PhotoProcessor(context).use { engine ->
            val uri=Uri.fromFile(fixture("P1035087"))
            val film=engine.films.first { it.token=="KodakPortra400" }
            val preview=engine.decodeGrainPreview(uri)
            try {
                assertEquals(2,preview.invocationScale);assertEquals(1500,preview.bitmap.width);assertEquals(1000,preview.bitmap.height)
                GrainPackStore(context).use { grains ->
                    assertEquals(99,grains.openInstalled().size)
                    val request=engine.grainRequest(preview,film,"KodakPortra400",NativeGrain8Geometry.Format.SMALL,1f,100,17)
                    val layer=grains.prepare(request)
                    val rendered=engine.render(preview.bitmap,film,{}, { false },grain=layer);rendered.recycle()
                    val full=grains.prepare(request.copy(invocationScale=0))
                    val output=File(context.cacheDir,"raw-s9-grain.jpg")
                    var stop=false
                    try {
                        engine.export(uri,film,Uri.fromFile(output),{ if(it>=10)stop=true },{ stop },grain=full)
                        fail("Cancelled RAW save must stop")
                    } catch(_:CancellationException) { }
                    assertFalse(output.exists())
                    val result=engine.export(uri,film,Uri.fromFile(output),{}, { false },grain=full)
                    assertEquals(6000,result.width);assertEquals(4000,result.height);assertFalse(result.resized)
                    Log.i("CXORaw","S9 with grain exportMs=${result.elapsedMillis} bytes=${output.length()}")
                }
            } finally { preview.bitmap.recycle() }
        }
    }
}
