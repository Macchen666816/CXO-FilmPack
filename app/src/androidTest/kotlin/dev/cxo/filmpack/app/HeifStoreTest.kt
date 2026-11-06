package dev.cxo.filmpack.app

import android.net.Uri
import android.test.InstrumentationTestCase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** Input identity must not rely on a filename that can disguise a camera RAW. */
@Suppress("DEPRECATION")
class HeifStoreTest : InstrumentationTestCase() {
    fun testHeaderBrandsCannotConfuseRenamedRawJpegAvifOrBoxPayload() {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "header-admission.heic")
        fun box(major: String, compatible: String): ByteArray =
            ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).putInt(20)
                .put("ftyp".toByteArray()).put(major.toByteArray()).putInt(0).put(compatible.toByteArray()).array()
        try {
            HeifJpegStore(context).use { store ->
                val uri = Uri.fromFile(file)
                file.writeBytes(byteArrayOf(0x49,0x49,0x2a,0,8,0,0,0))
                assertFalse("A RAW renamed .heic stays outside the JPEG bridge", store.isHeif(uri))
                instrumentation.context.assets.open("orientation6.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
                assertFalse(store.isHeif(uri))
                file.writeBytes(box("avif", "mif1")); assertFalse(store.isHeif(uri))
                file.writeBytes(box("heic", "mif1")); assertTrue(store.isHeif(uri))
                file.writeBytes(box("abcd", "abcd") + "heic".toByteArray())
                assertFalse("Do not inspect outside declared ftyp box", store.isHeif(uri))
                file.writeBytes(box("heic", "mif1").also { it[3] = 100 })
                assertFalse("Truncated box must not pass", store.isHeif(uri))
                try { store.normalize(uri) { true }; fail("Cancelled work must stop") }
                catch (_: CancellationException) { }
            }
        } finally { file.delete() }
    }
}
