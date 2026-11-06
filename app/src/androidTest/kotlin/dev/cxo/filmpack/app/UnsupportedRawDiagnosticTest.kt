package dev.cxo.filmpack.app

import android.net.Uri
import android.os.Bundle
import org.json.JSONObject
import java.io.IOException

/** Opt-in investigation of a real RAW; fixture stays in the test provider cache. */
@Suppress("DEPRECATION")
class UnsupportedRawDiagnosticTest : ForegroundEditorTest() {
    fun testDecodeBoundaryAndActualImportRejection() {
        val args = (instrumentation as android.test.InstrumentationTestRunner).arguments
        val name = args.getString("unsupportedRawFixture") ?: return
        require(name.matches(Regex("[A-Za-z0-9._-]+")) && !name.startsWith("."))
        activity // Keep ColorOS from suspending background instrumentation.
        val context = instrumentation.targetContext
        val uri = Uri.parse("content://dev.cxo.filmpack.app.test.photos/$name")
        val report = JSONObject().put("fixture", name)
        RawPhotoStore(context).use { store ->
            requireNotNull(store.openCameraIfRaw(uri)).use { raw ->
                report.put("make", raw.make).put("model", raw.model)
                    .put("width", raw.width).put("height", raw.height)
                    .put("rawBps", raw.rawBps).put("orientation", raw.orientation)
                    .put("cameraLinearDecodeSucceeded", true)
                    .put("profileMatched", RawCameraProfiles(context).forModel(raw.model, raw.make) != null)
            }
        }
        PhotoProcessor(context).use { engine ->
            var rejection: IOException? = null
            try { engine.decodePreview(uri, 320).recycle() }
            catch (failure: IOException) { rejection = failure }
            assertNotNull("Fixture must reproduce the unsupported-camera import", rejection)
            assertTrue(rejection!!.message, rejection.message?.contains("缺少机身标定") == true)
            report.put("actualDecodePreviewError", rejection.message)
        }
        instrumentation.sendStatus(2, Bundle().apply { putString("cxoUnsupportedRaw", report.toString()) })
    }
}
