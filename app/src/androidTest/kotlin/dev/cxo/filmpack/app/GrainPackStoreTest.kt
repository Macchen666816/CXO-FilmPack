package dev.cxo.filmpack.app

import android.net.Uri
import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.NativeGrain8Geometry
import java.io.File
import java.util.concurrent.CancellationException

class GrainPackStoreTest : InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext


    fun testImportReplacementFailuresKeepInstalledPackAndSurviveReopen() {
        val pack = File.createTempFile("grain-import-fixture-", ".cxograinpack", context.cacheDir)
        context.assets.open(GrainPackStore.BUNDLED_ASSET).use { input -> pack.outputStream().use { input.copyTo(it) } }
        val store=GrainPackStore(context)
        val request=PhotoGrainRequest(1024,1024,1,"KodakPortra400",NativeGrain8Geometry.Format.MANUAL,1f,100,17,true,false)
        try {
            val names=store.importPack(Uri.fromFile(pack),{}, { false })
            assertEquals(99,names.size)
            val baseline=store.prepare(request)
            val input=FloatArray(32*3) { .4f };val expected=FloatArray(input.size)
            baseline.kernel.renderNormalizedRgb(input,expected,32,13,7)
            val bad=File(context.cacheDir,"truncated-grain-pack.cxograinpack").apply { writeBytes(byteArrayOf(1,2,3)) }
            try {
                assertTrue(runCatching { store.importPack(Uri.fromFile(bad),{}, { false }) }.isFailure)
                var cancel=false
                val cancelled=runCatching { store.importPack(Uri.fromFile(pack),{ if (it>=1) cancel=true },{ cancel }) }.exceptionOrNull()
                assertTrue(cancelled is CancellationException)
                // Full-size bad-hash replacement must fail as well, not just a short header.
                val modified=File(context.cacheDir,"modified-grain-pack.cxograinpack")
                try {
                    pack.copyTo(modified,overwrite=true)
                    java.io.RandomAccessFile(modified,"rw").use { it.seek(4_000_000);it.write(99) }
                    assertTrue(runCatching { store.importPack(Uri.fromFile(modified),{}, { false }) }.isFailure)
                } finally { modified.delete() }
                val actual=FloatArray(input.size)
                store.prepare(request).kernel.renderNormalizedRgb(input,actual,32,13,7)
                assertTrue("failed import changed working resource",expected.contentEquals(actual))
                val directory=File(context.filesDir,"grain")
                assertEquals(1,directory.listFiles()!!.size)
            } finally { bad.delete() }
        } finally { store.close(); pack.delete() }
        GrainPackStore(context).use { reopened ->
            assertEquals(99,reopened.openInstalled().size)
            assertEquals(1024,reopened.prepare(request).width)
        }
    }

    fun testSettingsExactFollowAndIndependentRenderingContext() {
        val engine=PhotoProcessor(context)
        val prefs=context.getSharedPreferences("grain-test-settings",0)
        try {
            val settings=GrainSettings(true,"KodakPortra400",65,NativeGrain8Geometry.Format.MANUAL,1.5f,0xFFFFFFFFL)
            settings.save(prefs);assertEquals(settings,GrainSettings.read(prefs))
            assertEquals("KodakPortra400",settings.resolve(engine.films.first { it.token=="Astia" },listOf("KodakPortra400")))
            assertTrue(runCatching { settings.copy(identifier="").resolve(engine.films.first { it.token=="Astia" },listOf("KodakPortra400")) }.isFailure)
            val bitmap=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val preview=PhotoGrainPreview(bitmap,8,8,0)
                for ((token,color) in listOf("KodakPortra400" to true,"KodakTriX400" to false)) {
                    val request=engine.grainRequest(preview,engine.films.first { it.token==token },"KodakPortra400",
                        NativeGrain8Geometry.Format.MANUAL,1f,100,17)
                    assertEquals(color,request.color);assertFalse(request.neutralizeRedBlue)
                }
            } finally { bitmap.recycle() }
        } finally { prefs.edit().clear().commit() }
    }
}
