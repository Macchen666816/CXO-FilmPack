package dev.cxo.filmpack.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Log
import android.view.WindowManager
import dev.cxo.filmpack.app.post.*
import dev.cxo.filmpack.core.NativeGrain8Geometry
import dev.cxo.filmpack.core.PostProcessSettings
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Opt-in nondebug product benchmark, separate from functional test suites. */
@Suppress("DEPRECATION")
class CurrentPerformanceTest : InstrumentationTestCase() {
    fun testWorkflow() {
        val args=(instrumentation as android.test.InstrumentationTestRunner).arguments
        val name=args.getString("perfFixture") ?: return // Explicit opt-in only, like the existing benchmarks.
        require(name.matches(Regex("[A-Za-z0-9._-]+")))
        val label=args.getString("perfLabel") ?: "current"
        val source=Uri.parse("content://dev.cxo.filmpack.app.test.photos/$name")
        val context=instrumentation.targetContext
        val grainEnabled=args.getString("perfGrain")=="true"
        val postEnabled=args.getString("perfPost")=="true"
        val export=ExportSettings(format=if(args.getString("perfFormat")=="TIFF") ExportFormat.TIFF else ExportFormat.JPEG,
            jpegQuality=args.getString("perfQuality")?.toInt() ?: 98,maxLongEdge=args.getString("perfEdge")?.toInt() ?: 0)
        val token=args.getString("perfFilm") ?: "KodakEliteColor400"
        val results=JSONObject().put("label",label).put("fixture",name).put("film",token)
            .put("grain",grainEnabled).put("post",postEnabled).put("format",export.format.name)
            .put("quality",export.jpegQuality).put("longEdge",export.maxLongEdge)
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val output=File(context.cacheDir,"current-perf.${export.format.extension}")
        try {
            // Wait for the product's one-time bundled grain setup, rather than racing it on the worker.
            val field=MainActivity::class.java.getDeclaredField("processor").apply {isAccessible=true}
            val deadline=SystemClock.elapsedRealtime()+120000
            var ready=false
            while(!ready && SystemClock.elapsedRealtime()<deadline) {
                instrumentation.runOnMainSync {ready=field.get(activity)!=null}
                if(!ready)SystemClock.sleep(50)
            }
            assertTrue("Editor initialization",ready)
            PhotoProcessor(context).use {engine ->
                GrainPackStore(context).use {store ->
                    val film=engine.films.single {it.token==token}
                    val post=if(postEnabled) PostEditingSettings(PostProcessSettings(exposureEv=.25f,contrast=20,saturation=-10),
                        EdgeDefectSettings("fuitelum1",60,EdgePosition.LEFT,72)) else PostEditingSettings()
                    val started=SystemClock.elapsedRealtime()
                    val metadata=if(grainEnabled)engine.decodeGrainPreview(source)else null
                    val original=metadata?.bitmap ?: engine.decodePreview(source)
                    results.put("importMs",SystemClock.elapsedRealtime()-started)
                    var layer:PhotoGrainLayer?=null
                    if(metadata!=null) {
                        val tick=SystemClock.elapsedRealtime()
                        layer=store.prepare(engine.grainRequest(metadata,film,film.token,NativeGrain8Geometry.Format.SMALL,1f,100,17))
                        results.put("previewGrainPrepareMs",SystemClock.elapsedRealtime()-tick)
                    }
                    try {
                        for(pass in 1..2) {
                            val tick=SystemClock.elapsedRealtime()
                            val rendered=engine.render(original,film,{}, {false},grain=layer,rawUri=source,post=post)
                            results.put("filmPreview${pass}Ms",SystemClock.elapsedRealtime()-tick)
                            try {
                                results.put("previewWidth",rendered.width).put("previewHeight",rendered.height)
                                val pixels=IntArray(rendered.width*rendered.height)
                                rendered.getPixels(pixels,0,rendered.width,0,0,rendered.width,rendered.height)
                                val hash=MessageDigest.getInstance("SHA-256")
                                for(pixel in pixels) {hash.update((pixel ushr 16).toByte());hash.update((pixel ushr 8).toByte());hash.update(pixel.toByte())}
                                results.put("preview${pass}Sha256",hash.digest().joinToString("") {"%02x".format(it.toInt() and 255)})
                            }finally{rendered.recycle()}
                        }
                    }finally{original.recycle()}
                    var exportLayer:PhotoGrainLayer?=null
                    if(metadata!=null) {
                        val tick=SystemClock.elapsedRealtime()
                        exportLayer=store.prepare(engine.grainRequest(metadata,film,film.token,NativeGrain8Geometry.Format.SMALL,1f,100,17,fullResolution=true))
                        results.put("exportGrainPrepareMs",SystemClock.elapsedRealtime()-tick)
                    }
                    val result=engine.export(source,film,Uri.fromFile(output),{}, {false},grain=exportLayer,post=post,settings=export)
                    results.put("exportMs",result.elapsedMillis).put("width",result.width).put("height",result.height)
                        .put("bytes",output.length()).put("memoryFallbackResize",result.resized)
                    val hash=MessageDigest.getInstance("SHA-256")
                    output.inputStream().use {input -> val buffer=ByteArray(65536)
                        while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}}
                    results.put("exportSha256",hash.digest().joinToString("") {"%02x".format(it.toInt() and 255)})
                    assertEquals(results.getString("preview1Sha256"),results.getString("preview2Sha256"))
                    assertFalse(result.resized)
                    Log.i("CXOPerf","CURRENT_WORKFLOW $results")
                    instrumentation.sendStatus(2,Bundle().apply {putString("cxoPerf",results.toString())})
                }
            }
        } finally {
            output.delete()
            instrumentation.runOnMainSync {activity.finish()}
        }
    }
}
