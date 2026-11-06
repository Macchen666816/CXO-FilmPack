package dev.cxo.filmpack.app

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.test.InstrumentationTestCase
import java.io.File
import java.io.IOException

/** Opt-in actual files staged in /data/local/tmp. Does not claim DxO photo parity. */
@Suppress("DEPRECATION")
class PublicBetaRawSmokeTest : InstrumentationTestCase() {
    fun testActualCameraImportsAndReducedExportsRetainCalibratedRoute() {
        val args = (instrumentation as android.test.InstrumentationTestRunner).arguments
        val names = args.getString("auditRawFixtures")?.split(',') ?: return
        val context = instrumentation.targetContext
        for (name in names) {
            require(name.matches(Regex("[A-Za-z0-9._-]+")))
            val source = File(context.cacheDir, "audit-$name")
            val output = File(context.cacheDir, "audit-raw-output.jpg")
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cat /data/local/tmp/$name")).use { input -> source.outputStream().use { input.copyTo(it) } }
            try {
                PhotoProcessor(context).use { engine ->
                    val uri = Uri.fromFile(source)
                    if (name.startsWith("unknown-camera") || name.startsWith("IMG20261105173001")) {
                        try { engine.decodePreview(uri, 320).recycle(); fail("Missing camera profile must fail") }
                        catch (failure: IOException) { assertTrue(failure.message, failure.message?.contains("缺少机身标定") == true) }
                        assertFalse(output.exists())
                    } else {
                        val preview = engine.decodePreview(uri, 640)
                        try {
                            val film = engine.films.first { it.token == "KodakPortra400" }
                            val rendered = engine.render(preview, film, {}, { false }, rawUri = uri)
                            try { assertEquals(preview.width, rendered.width); assertEquals(preview.height, rendered.height) }
                            finally { rendered.recycle() }
                            val result = engine.export(uri, film, Uri.fromFile(output), {}, { false },
                                settings = ExportSettings(maxLongEdge = 640))
                            assertEquals(640, maxOf(result.width, result.height))
                            assertTrue(output.length() > 1024)
                            instrumentation.sendStatus(2, Bundle().apply {
                                putString("cxoRawAudit", "$name ${result.width}x${result.height} exportMs=${result.elapsedMillis}")
                            })
                        } finally { preview.recycle() }
                    }
                }
            } finally { source.delete(); output.delete() }
        }
    }
}
